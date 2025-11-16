package dk.trustworks.intranet.batch.metadata;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Metadata about a batch job, including its configuration, schedule, and execution history.
 *
 * @param jobName The unique job identifier (matches XML file name without .xml extension)
 * @param description Human-readable description of what this job does
 * @param type The job execution type (BATCHLET, CHUNK, or PARTITIONED)
 * @param parameters List of parameters this job accepts
 * @param cronExpression The cron schedule if this job is scheduled (null if manual-only)
 * @param lastRun Timestamp of the most recent execution (null if never run)
 * @param lastStatus Status of the most recent execution (COMPLETED, FAILED, STOPPED, etc.)
 * @param isRunning Whether this job is currently executing
 * @param runningCount Number of concurrent executions currently running
 */
public record JobMetadata(
    String jobName,
    String description,
    JobType type,
    List<JobParameter> parameters,
    String cronExpression,
    LocalDateTime lastRun,
    String lastStatus,
    boolean isRunning,
    int runningCount
) {

    public enum JobType {
        BATCHLET,      // Simple batchlet (single step, single execution)
        CHUNK,         // Chunk-oriented processing (reader-processor-writer)
        PARTITIONED    // Partitioned execution (parallel processing)
    }
}
