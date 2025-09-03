package dk.trustworks.intranet.batch.monitoring;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import jakarta.batch.api.listener.JobListener;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Properties;

@Named("jobMonitoringListener")
@Dependent
@JBossLog
public class JobMonitoringListener implements JobListener {

    @Inject JobContext jobContext;
    @Inject JobOperator jobOperator;
    @Inject BatchJobTrackingService trackingService;
    @Inject UserService userService;
    @Inject BatchExceptionRegistry exceptionRegistry;

    @Override
    @ActivateRequestContext
    public void beforeJob() {
        long executionId = jobContext.getExecutionId();
        String jobName = jobContext.getJobName();
        trackingService.onJobStart(executionId, jobName);

        // Try to pre-compute total subtasks for partitioned jobs to get meaningful percent early
        try {
            Properties params = jobOperator.getParameters(executionId);
            if (params != null) {
                if (Objects.equals(jobName, "user-salary-forward-recalc") || Objects.equals(jobName, "contract-consultant-forward-recalc")) {
                    String start = params.getProperty("start");
                    String end   = params.getProperty("end");
                    if (start != null && end != null) {
                        LocalDate s = LocalDate.parse(start);
                        LocalDate e = LocalDate.parse(end);
                        long days = ChronoUnit.DAYS.between(s, e) + 1; // inclusive per job design
                        if (days > 0 && days < Integer.MAX_VALUE) {
                            trackingService.setTotalSubtasks(executionId, (int) days);
                        }
                    }
                } else if (Objects.equals(jobName, "bi-date-update")) {
                    String start = params.getProperty("startDate");
                    String end   = params.getProperty("endDate");
                    if (start != null && end != null) {
                        LocalDate s = LocalDate.parse(start);
                        LocalDate e = LocalDate.parse(end);
                        long days = ChronoUnit.DAYS.between(s, e); // exclusive upper bound in mapper
                        int users = safeUserCount();
                        long total = Math.max(0, days) * Math.max(users, 0);
                        if (total > 0 && total < Integer.MAX_VALUE) {
                            trackingService.setTotalSubtasks(executionId, (int) total);
                        }
                    }
                } else if (Objects.equals(jobName, "budget-aggregation")) {
                    String partitions = params.getProperty("partitions");
                    try {
                        if (partitions != null && !partitions.isBlank()) {
                            int p = Integer.parseInt(partitions.trim());
                            if (p > 0) trackingService.setTotalSubtasks(executionId, p);
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
        
        // Check if an exception was captured during job execution
        Throwable capturedError = null;
        try {
            BatchExceptionRegistry.ExceptionContext exceptionContext = 
                exceptionRegistry.retrieveException(executionId);
            
            if (exceptionContext != null) {
                capturedError = exceptionContext.exception;
                log.infof("Retrieved captured exception for job %s (execution %d): %s",
                         jobContext.getJobName(), executionId, 
                         capturedError.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.errorf(e, "Failed to retrieve exception from registry for execution %d", executionId);
        }
        
        // Update tracking with status and any captured exception
        if (capturedError != null) {
            // Use the overloaded method that accepts the exception
            trackingService.onJobEnd(executionId, 
                                   status != null ? status.name() : "UNKNOWN", 
                                   exitStatus, 
                                   capturedError);
        } else if (status == BatchStatus.FAILED) {
            // Job failed but no exception was captured - create a synthetic one
            String errorMsg = String.format("Job %s failed with status %s. Exit status: %s",
                                          jobContext.getJobName(), status, exitStatus);
            Exception syntheticError = new Exception(errorMsg);
            
            trackingService.onJobEnd(executionId,
                                   status.name(),
                                   exitStatus,
                                   syntheticError);
        } else {
            // Normal completion without error
            trackingService.onJobEnd(executionId, 
                                   status != null ? status.name() : "UNKNOWN", 
                                   exitStatus);
        }
        
        // Clean up registry to prevent memory leaks
        try {
            exceptionRegistry.clearException(executionId);
        } catch (Exception e) {
            log.warnf("Failed to clear exception registry for execution %d", executionId);
        }
    }

    @ActivateRequestContext
    int safeUserCount() {
        try {
            return userService.listAll(true).size();
        } catch (Exception e) {
            return 0;
        }
    }
}
