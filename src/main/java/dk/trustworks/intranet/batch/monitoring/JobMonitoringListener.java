package dk.trustworks.intranet.batch.monitoring;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.perf.PerfMetrics;
import jakarta.batch.api.listener.JobListener;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

@JBossLog
@Dependent
@ActivateRequestContext
@Named("jobMonitoringListener")
public class JobMonitoringListener implements JobListener {

    @Inject JobContext jobContext;
    @Inject JobOperator jobOperator;
    @Inject BatchJobTrackingService trackingService;
    @Inject UserService userService;
    @Inject BatchExceptionRegistry exceptionRegistry;
    @Inject PerfMetrics perfMetrics;

    /** executionId -> start nanos, used to compute job duration for perf metrics. */
    private static final Map<Long, Long> PERF_START_NANOS = new ConcurrentHashMap<>();

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void beforeJob() {
        PERF_START_NANOS.put(jobContext.getExecutionId(), System.nanoTime());
        long executionId = jobContext.getExecutionId();
        String jobName = jobContext.getJobName();
        log.infof("[JOB-MONITOR] beforeJob() called for job '%s' (execution %d)", jobName, executionId);
        
        try {
            trackingService.onJobStart(executionId, jobName);
            log.infof("[JOB-MONITOR] Successfully initiated tracking for job '%s' (execution %d)", jobName, executionId);
        } catch (Exception e) {
            log.errorf(e, "[JOB-MONITOR] Failed to initiate tracking for job '%s' (execution %d)", jobName, executionId);
        }

        // Try to pre-compute total subtasks for partitioned jobs to get meaningful percent early
        try {
            Properties params = jobOperator.getParameters(executionId);
            if (params != null) {
                if (Objects.equals(jobName, "budget-aggregation")) {
                    String partitions = params.getProperty("partitions");
                    try {
                        if (partitions != null && !partitions.isBlank()) {
                            int p = Integer.parseInt(partitions.trim());
                            if (p > 0) {
                                log.infof("[JOB-MONITOR] Setting total subtasks to %d partitions for budget-aggregation", p);
                                trackingService.setTotalSubtasks(executionId, p);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {
            // Best-effort only; progress will still be updated via partition analyzer
        }
    }

    @Override
    @ActivateRequestContext
    public void afterJob() {
        long executionId = jobContext.getExecutionId();
        BatchStatus status = jobContext.getBatchStatus();
        String exitStatus = jobContext.getExitStatus();
        String jobName = jobContext.getJobName();
        
        log.infof("[JOB-MONITOR] afterJob() called for job '%s' (execution %d) - status: %s, exitStatus: %s", 
                 jobName, executionId, status, exitStatus);
        
        // Check if an exception was captured during job execution
        Throwable capturedError = null;
        try {
            BatchExceptionRegistry.ExceptionContext exceptionContext = 
                exceptionRegistry.retrieveException(executionId);
            
            if (exceptionContext != null) {
                capturedError = exceptionContext.exception;
                log.infof("[JOB-MONITOR] Retrieved captured exception for job %s (execution %d): %s - %s",
                         jobName, executionId, 
                         capturedError.getClass().getSimpleName(),
                         capturedError.getMessage());
            } else {
                log.debugf("[JOB-MONITOR] No captured exception found for job %s (execution %d)", 
                          jobName, executionId);
            }
        } catch (Exception e) {
            log.errorf(e, "[JOB-MONITOR] Failed to retrieve exception from registry for execution %d", executionId);
        }
        
        // Update tracking with status and any captured exception
        if (capturedError != null) {
            log.infof("[JOB-MONITOR] Updating job end with captured exception for execution %d", executionId);
            // Use the overloaded method that accepts the exception
            trackingService.onJobEnd(executionId, 
                                   status != null ? status.name() : "UNKNOWN", 
                                   exitStatus, 
                                   capturedError);
        } else if (status == BatchStatus.FAILED) {
            log.warnf("[JOB-MONITOR] Job failed but no exception captured for execution %d, creating synthetic error", executionId);
            // Job failed but no exception was captured - create a synthetic one
            String errorMsg = String.format("Job %s failed with status %s. Exit status: %s",
                                          jobName, status, exitStatus);
            Exception syntheticError = new Exception(errorMsg);
            
            trackingService.onJobEnd(executionId,
                                   status.name(),
                                   exitStatus,
                                   syntheticError);
        } else {
            // Normal completion without error
            log.infof("[JOB-MONITOR] Job completed normally for execution %d - calling onJobEnd", executionId);
            trackingService.onJobEnd(executionId, 
                                   status != null ? status.name() : "UNKNOWN", 
                                   exitStatus);
            log.infof("[JOB-MONITOR] onJobEnd completed for execution %d", executionId);
        }
        
        // Clean up registry to prevent memory leaks
        try {
            exceptionRegistry.clearException(executionId);
            log.debugf("[JOB-MONITOR] Cleared exception registry for execution %d", executionId);
        } catch (Exception e) {
            log.warnf("[JOB-MONITOR] Failed to clear exception registry for execution %d: %s",
                     executionId, e.getMessage());
        }

        try {
            Long startNanos = PERF_START_NANOS.remove(executionId);
            if (startNanos != null) {
                double durationMs = (System.nanoTime() - startNanos) / 1_000_000.0;
                emitJobPerf(jobName, status, executionId, durationMs);
            }
        } catch (Exception e) {
            log.warnf("perf emit failed for job %s: %s", jobName, e.getMessage());
        }
    }

    @ActivateRequestContext
    int safeUserCount() {
        try {
            int count = userService.listAll(true).size();
            log.debugf("[JOB-MONITOR] Retrieved user count: %d", count);
            return count;
        } catch (Exception e) {
            log.warnf("[JOB-MONITOR] Failed to get user count: %s", e.getMessage());
            return 0;
        }
    }

    void emitJobPerf(String jobName, BatchStatus status,
                     long executionId, double durationMs) {
        String outcome;
        if (status == null) {
            outcome = "other";
        } else {
            switch (status) {
                case COMPLETED -> outcome = "success";
                case FAILED -> outcome = "failed";
                case STOPPED -> outcome = "stopped";
                default -> outcome = "other";
            }
        }
        perfMetrics.emitTimer("BatchJobDurationMs", durationMs,
                Map.of("job", jobName), Map.of("executionId", executionId));
        perfMetrics.emitCount("BatchJobRuns", 1,
                Map.of("job", jobName, "outcome", outcome), Map.of("executionId", executionId));
    }
}
