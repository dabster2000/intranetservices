package dk.trustworks.intranet.batch.execution;

import dk.trustworks.intranet.batch.metadata.BatchJobMetadataService;
import dk.trustworks.intranet.batch.metadata.JobParameter;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.operations.JobStartException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Service for manually executing batch jobs.
 *
 * Provides validation and execution capabilities for starting jobs via the UI.
 */
@JBossLog
@ApplicationScoped
public class BatchJobExecutionService {

    @Inject
    JobOperator jobOperator;

    @Inject
    BatchJobMetadataService metadataService;

    /**
     * Start a batch job with the given parameters.
     *
     * @param jobName The job identifier
     * @param parameters Map of parameter name → value (all values as strings)
     * @param requestedBy Username of the person requesting the job start (for audit logging)
     * @return Execution ID of the started job
     * @throws IllegalArgumentException if job doesn't exist or parameters are invalid
     * @throws JobStartException if the job fails to start
     */
    public long startJob(String jobName, Map<String, String> parameters, String requestedBy) {
        log.infof("Manual job start requested: job=%s, user=%s, params=%s", jobName, requestedBy, parameters);

        // Validate job exists
        if (!metadataService.getAllJobNames().contains(jobName)) {
            throw new IllegalArgumentException("Unknown job: " + jobName);
        }

        // Get job metadata to validate parameters
        var jobMetadata = metadataService.getJobMetadata(jobName)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobName));

        // Validate parameters
        validateParameters(jobMetadata.parameters(), parameters);

        // Convert Map to Properties
        Properties props = new Properties();
        if (parameters != null) {
            props.putAll(parameters);
        }

        // Add audit metadata
        props.setProperty("_manual_start", "true");
        props.setProperty("_requested_by", requestedBy);

        // Start the job
        try {
            long executionId = jobOperator.start(jobName, props);
            log.infof("Job started successfully: job=%s, executionId=%d, user=%s", jobName, executionId, requestedBy);
            return executionId;
        } catch (JobStartException e) {
            log.errorf(e, "Failed to start job: %s", jobName);
            throw e;
        }
    }

    /**
     * Validate parameters against job parameter schema.
     *
     * @throws IllegalArgumentException if validation fails
     */
    private void validateParameters(java.util.List<JobParameter> schema, Map<String, String> provided) {
        if (provided == null) {
            provided = Map.of();
        }

        // Check required parameters are provided
        for (JobParameter param : schema) {
            if (param.required() && !provided.containsKey(param.name())) {
                throw new IllegalArgumentException(
                    String.format("Required parameter missing: %s (%s)", param.name(), param.description())
                );
            }
        }

        // Validate parameter types
        for (Map.Entry<String, String> entry : provided.entrySet()) {
            String paramName = entry.getKey();
            String value = entry.getValue();

            // Skip audit metadata parameters
            if (paramName.startsWith("_")) {
                continue;
            }

            // Find parameter schema
            JobParameter paramSchema = schema.stream()
                .filter(p -> p.name().equals(paramName))
                .findFirst()
                .orElse(null);

            if (paramSchema == null) {
                // Unknown parameter - log warning but allow (might be added later)
                log.warnf("Unknown parameter provided: %s (will be passed to job anyway)", paramName);
                continue;
            }

            // Validate type
            validateParameterType(paramSchema, value);
        }
    }

    /**
     * Validate a single parameter value matches its expected type.
     */
    private void validateParameterType(JobParameter param, String value) {
        if (value == null || value.isBlank()) {
            if (param.required()) {
                throw new IllegalArgumentException(
                    String.format("Required parameter cannot be empty: %s", param.name())
                );
            }
            return; // Optional parameter, blank is OK
        }

        try {
            switch (param.type()) {
                case DATE:
                    LocalDate.parse(value); // ISO format: yyyy-MM-dd
                    break;
                case INTEGER:
                    int intValue = Integer.parseInt(value);
                    if (intValue <= 0 && param.name().contains("thread")) {
                        throw new IllegalArgumentException("Thread count must be positive");
                    }
                    break;
                case BOOLEAN:
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                        throw new IllegalArgumentException("Boolean must be 'true' or 'false'");
                    }
                    break;
                case STRING:
                    // String is always valid
                    break;
            }
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format("Invalid value for parameter '%s' (type=%s): %s - %s",
                    param.name(), param.type(), value, e.getMessage())
            );
        }
    }

    /**
     * Get currently running executions for a specific job.
     *
     * @param jobName The job identifier
     * @return Set of execution IDs currently running
     */
    public Set<Long> getRunningExecutions(String jobName) {
        try {
            return new HashSet<>(jobOperator.getRunningExecutions(jobName));
        } catch (Exception e) {
            log.debugf(e, "Failed to get running executions for job: %s", jobName);
            return Set.of();
        }
    }

    /**
     * Check if a job is currently running.
     *
     * @param jobName The job identifier
     * @return true if at least one execution is running
     */
    public boolean isJobRunning(String jobName) {
        return !getRunningExecutions(jobName).isEmpty();
    }
}
