package dk.trustworks.intranet.batch;

import jakarta.batch.operations.JobOperator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.Properties;

/**
 * Launcher for bulk salary recalculation batch job.
 *
 * <p>Triggers the "bulk-salary-forward-recalc" job which recalculates salary data
 * for multiple users across a date range. Each user × date combination becomes
 * a separate partition processed in parallel.
 *
 * <p>Example usage:
 * <pre>{@code
 * List<String> userUuids = Arrays.asList("uuid1", "uuid2", "uuid3");
 * LocalDate start = LocalDate.of(2024, 7, 1);
 * LocalDate end = LocalDate.of(2025, 6, 30);
 * long executionId = launcher.launch(userUuids, start, end, 8);
 * }</pre>
 */
@ApplicationScoped
@JBossLog
public class BulkSalaryRecalcJobLauncher {

    private static final String JOB_NAME = "bulk-salary-forward-recalc";

    @Inject
    JobOperator jobOperator;

    /**
     * Launch bulk salary recalculation job.
     *
     * @param userUuids list of user UUIDs to process
     * @param start start date (inclusive)
     * @param end end date (inclusive)
     * @param requestedThreads thread count hint (0 for auto)
     * @return batch job execution ID
     */
    public long launch(List<String> userUuids, LocalDate start, LocalDate end, int requestedThreads) {
        if (userUuids == null || userUuids.isEmpty()) {
            throw new IllegalArgumentException("userUuids list cannot be null or empty");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end dates are required");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("end date must be on or after start date");
        }

        // Convert user UUIDs to comma-separated string
        String userUuidsStr = String.join(",", userUuids);

        Properties props = new Properties();
        props.setProperty("userUuids", userUuidsStr);
        props.setProperty("start", start.toString());
        props.setProperty("end", end.toString());
        props.setProperty("requestedThreads", String.valueOf(requestedThreads));

        long executionId = jobOperator.start(JOB_NAME, props);

        log.infof("Started bulk salary recalc job: executionId=%d, users=%d, start=%s, end=%s, threads=%d",
                  executionId, userUuids.size(), start, end, requestedThreads);

        return executionId;
    }
}
