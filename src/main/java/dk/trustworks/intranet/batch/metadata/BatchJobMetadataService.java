package dk.trustworks.intranet.batch.metadata;

import dk.trustworks.intranet.batch.monitoring.BatchJobExecutionTracking;
import jakarta.annotation.PostConstruct;
import jakarta.batch.operations.JobOperator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for discovering and managing batch job metadata.
 *
 * This service scans the classpath for batch job XML definitions and provides
 * metadata about each job including schedules, parameters, and execution history.
 */
@JBossLog
@ApplicationScoped
public class BatchJobMetadataService {

    @Inject
    JobOperator jobOperator;

    @Inject
    JobParameterRegistry parameterRegistry;

    private Set<String> discoveredJobs = new HashSet<>();

    // Hardcoded schedule map extracted from BatchScheduler.java
    // Format: "cron expression" or "every Xm/Xh"
    private static final Map<String, String> SCHEDULES = Map.ofEntries(
        Map.entry("bi-date-update", "0 0 3 ? * 7-1"),
        Map.entry("finance-load-economics", "0 0 21 * * ?"),
        Map.entry("finance-invoice-sync", "0 0 22 * * ?"),
        Map.entry("slack-user-sync", "0 30 2 * * ?"),
        Map.entry("team-description", "0 0 10 10 * ?"),
        Map.entry("project-lock", "0 0 0 * * ?"),
        Map.entry("user-resume-update", "every 24h"),
        Map.entry("mail-send", "every 1m"),
        Map.entry("bulk-mail-send", "every 1m"),
        Map.entry("expense-consume", "every 1h"),
        Map.entry("expense-sync", "0 0 3 * * ?"),
        Map.entry("expense-orphan-detection", "0 15 * * * ?"),
        Map.entry("queued-internal-invoice-processor", "0 0 2 * * ?"),
        Map.entry("economics-upload-retry", "0 * * * * ?")
    );

    // Job descriptions - extracted from job XML comments or batchlet class javadocs
    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
        Map.entry("bi-date-update", "Recalculate BI user day data across date range"),
        Map.entry("finance-load-economics", "Load financial data from Economics"),
        Map.entry("finance-invoice-sync", "Synchronize invoices with finance system"),
        Map.entry("slack-user-sync", "Synchronize user data with Slack"),
        Map.entry("team-description", "Generate AI-powered team descriptions using member resumes"),
        Map.entry("project-lock", "Lock completed projects daily"),
        Map.entry("user-resume-update", "Update user resume data"),
        Map.entry("mail-send", "Process and send pending emails"),
        Map.entry("bulk-mail-send", "Process bulk email campaigns"),
        Map.entry("expense-consume", "Process expense submissions"),
        Map.entry("expense-sync", "Synchronize expenses with external systems"),
        Map.entry("expense-orphan-detection", "Detect and handle orphaned expense records"),
        Map.entry("queued-internal-invoice-processor", "Process queued internal invoices"),
        Map.entry("economics-upload-retry", "Retry failed Economics invoice uploads"),
        Map.entry("user-salary-forward-recalc", "Recalculate user salary for date range"),
        Map.entry("user-salary-day-recalc", "Recalculate user salary for specific date")
    );

    @PostConstruct
    void init() {
        log.info("BatchJobMetadataService initializing...");
        discoverJobs();
        log.infof("Discovered %d batch jobs", discoveredJobs.size());
    }

    /**
     * Discover all batch job XML files from META-INF/batch-jobs/
     */
    private void discoverJobs() {
        try {
            // Get the resource URL for batch-jobs directory
            var classLoader = Thread.currentThread().getContextClassLoader();
            var resources = classLoader.getResources("META-INF/batch-jobs");

            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                log.debugf("Scanning batch jobs from: %s", url);

                try {
                    URI uri = url.toURI();
                    Path path;

                    // Handle both file system and JAR resources
                    if (uri.getScheme().equals("jar")) {
                        FileSystem fileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                        path = fileSystem.getPath("/META-INF/batch-jobs");
                    } else {
                        path = Paths.get(uri);
                    }

                    // List all .xml files
                    try (Stream<Path> files = Files.list(path)) {
                        files.filter(f -> f.toString().endsWith(".xml"))
                             .forEach(f -> {
                                 String fileName = f.getFileName().toString();
                                 String jobName = fileName.substring(0, fileName.length() - 4); // Remove .xml
                                 discoveredJobs.add(jobName);
                                 log.debugf("Discovered job: %s", jobName);
                             });
                    }
                } catch (IOException | URISyntaxException e) {
                    log.warnf(e, "Failed to scan directory: %s", url);
                }
            }
        } catch (IOException e) {
            log.error("Failed to discover batch jobs", e);
        }
    }

    /**
     * Get metadata for all discovered batch jobs.
     *
     * @return List of job metadata, sorted by job name
     */
    public List<JobMetadata> getAllJobs() {
        return discoveredJobs.stream()
            .map(this::getJobMetadata)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .sorted(Comparator.comparing(JobMetadata::jobName))
            .collect(Collectors.toList());
    }

    /**
     * Get metadata for a specific job.
     *
     * @param jobName The job identifier
     * @return JobMetadata if job exists, empty otherwise
     */
    public Optional<JobMetadata> getJobMetadata(String jobName) {
        if (!discoveredJobs.contains(jobName)) {
            return Optional.empty();
        }

        // Get parameters from registry
        List<JobParameter> parameters = parameterRegistry.getParameters(jobName);

        // Get schedule
        String schedule = SCHEDULES.get(jobName);

        // Get description
        String description = DESCRIPTIONS.getOrDefault(jobName, "No description available");

        // Get last execution info from database
        var lastExecution = getLastExecution(jobName);
        LocalDateTime lastRun = lastExecution.map(BatchJobExecutionTracking::getStartTime).orElse(null);
        String lastStatus = lastExecution.map(BatchJobExecutionTracking::getStatus).orElse(null);

        // Check if currently running
        int runningCount = getRunningExecutionCount(jobName);
        boolean isRunning = runningCount > 0;

        // Determine job type (simplified - would need XML parsing for accurate detection)
        JobMetadata.JobType type = determineJobType(jobName);

        return Optional.of(new JobMetadata(
            jobName,
            description,
            type,
            parameters,
            schedule,
            lastRun,
            lastStatus,
            isRunning,
            runningCount
        ));
    }

    /**
     * Get the most recent execution for a job.
     */
    private Optional<BatchJobExecutionTracking> getLastExecution(String jobName) {
        return BatchJobExecutionTracking.find(
            "jobName = ?1 ORDER BY startTime DESC",
            jobName
        ).firstResultOptional();
    }

    /**
     * Get count of currently running executions for a job.
     */
    private int getRunningExecutionCount(String jobName) {
        try {
            List<Long> runningExecutions = jobOperator.getRunningExecutions(jobName);
            return runningExecutions != null ? runningExecutions.size() : 0;
        } catch (Exception e) {
            log.debugf(e, "Failed to get running executions for job: %s", jobName);
            return 0;
        }
    }

    /**
     * Determine job type based on job name patterns.
     * This is a simplified heuristic - accurate detection would require XML parsing.
     */
    private JobMetadata.JobType determineJobType(String jobName) {
        // Jobs known to use partitioning
        if (jobName.contains("bi-date") || jobName.contains("recalc") || jobName.contains("forward")) {
            return JobMetadata.JobType.PARTITIONED;
        }
        // Jobs known to use chunk processing
        if (jobName.contains("bulk-mail") || jobName.contains("sync")) {
            return JobMetadata.JobType.CHUNK;
        }
        // Default to batchlet
        return JobMetadata.JobType.BATCHLET;
    }

    /**
     * Get all job names (useful for validation).
     */
    public Set<String> getAllJobNames() {
        return new HashSet<>(discoveredJobs);
    }
}
