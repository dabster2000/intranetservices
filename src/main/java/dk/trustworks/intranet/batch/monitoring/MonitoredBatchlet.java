package dk.trustworks.intranet.batch.monitoring;

import jakarta.batch.api.AbstractBatchlet;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Abstract base class for batchlets that automatically captures and persists exceptions.
 * 
 * All batch jobs should extend this class instead of AbstractBatchlet directly to ensure
 * proper exception tracking. The template method pattern ensures exceptions are captured
 * even if the subclass doesn't handle them.
 * 
 * Usage:
 * <pre>
 * {@code
 * @Named("myBatchlet")
 * public class MyBatchlet extends MonitoredBatchlet {
 *     @Override
 *     protected String doProcess() throws Exception {
 *         // Your batch logic here
 *         return "COMPLETED";
 *     }
 * }
 * }
 * </pre>
 */
@JBossLog
public abstract class MonitoredBatchlet extends AbstractBatchlet {
    
    @Inject
    protected JobContext jobContext;
    
    @Inject
    private BatchExceptionRegistry exceptionRegistry;
    
    @Inject
    private BatchJobTrackingService trackingService;
    
    /**
     * Final implementation of process() that wraps the actual work with exception handling.
     * This method cannot be overridden to ensure exception capture always occurs.
     */
    @Override
    @ActivateRequestContext
    public final String process() throws Exception {
        long executionId = jobContext.getExecutionId();
        String jobName = jobContext.getJobName();
        
        log.debugf("Starting monitored execution of job %s (execution %d)", jobName, executionId);
        
        try {
            // Call the template method that subclasses implement
            String result = doProcess();
            
            log.debugf("Job %s (execution %d) completed successfully with result: %s", 
                      jobName, executionId, result);
            
            return result;
            
        } catch (Exception e) {
            // Capture exception in all available mechanisms
            handleException(executionId, jobName, e);
            
            // Re-throw to maintain JBeret's expected behavior
            throw e;
            
        } catch (Error e) {
            // Also catch Errors (OutOfMemoryError, etc.) for complete tracking
            handleException(executionId, jobName, e);
            throw e;
            
        } finally {
            // Ensure any thread-local data is cleaned up
            try {
                onFinally(executionId, jobName);
            } catch (Exception e) {
                log.errorf(e, "Error in finally block for job %s (execution %d)", jobName, executionId);
            }
        }
    }
    
    /**
     * Template method that subclasses must implement with their batch logic.
     * This method should contain the actual work of the batchlet.
     * 
     * @return Exit status string (e.g., "COMPLETED", "FAILED")
     * @throws Exception Any exception thrown will be captured and tracked
     */
    protected abstract String doProcess() throws Exception;
    
    /**
     * Optional hook for subclasses to perform cleanup.
     * Called in the finally block after doProcess() completes or fails.
     * 
     * @param executionId The batch execution ID
     * @param jobName The name of the job
     */
    protected void onFinally(long executionId, String jobName) {
        // Default implementation does nothing
        // Subclasses can override for resource cleanup
    }
    
    /**
     * Handles exception capture across all tracking mechanisms.
     */
    private void handleException(long executionId, String jobName, Throwable e) {
        log.errorf(e, "Job %s (execution %d) failed with exception", jobName, executionId);
        
        // 1. Register in ThreadLocal/Map registry for later retrieval
        try {
            exceptionRegistry.captureException(executionId, e);
        } catch (Exception regEx) {
            log.errorf(regEx, "Failed to register exception in registry for execution %d", executionId);
        }
        
        // 2. Immediately persist to database (uses REQUIRES_NEW transaction)
        try {
            String exitStatus = String.format("FAILED: %s: %s", 
                e.getClass().getSimpleName(), 
                e.getMessage() != null ? e.getMessage() : "No message");
            
            trackingService.onJobFailure(executionId, e, exitStatus);
        } catch (Exception dbEx) {
            log.errorf(dbEx, "Failed to persist exception to database for execution %d", executionId);
        }
        
        // 3. Store in JobContext as backup
        try {
            jobContext.setExitStatus("FAILED: " + e.getClass().getSimpleName());
            // Note: JobContext doesn't support arbitrary objects, only primitives
            // This is why we need the other mechanisms
        } catch (Exception ctxEx) {
            log.errorf(ctxEx, "Failed to update job context for execution %d", executionId);
        }
    }
    
    /**
     * Utility method for subclasses to manually report non-fatal errors.
     * Use this for errors that should be logged but shouldn't fail the job.
     * 
     * @param message Error message to append to tracking details
     * @param error Optional exception to include in trace
     */
    protected void reportNonFatalError(String message, Throwable error) {
        long executionId = jobContext.getExecutionId();
        
        try {
            trackingService.appendDetails(executionId, message);
            if (error != null) {
                trackingService.setTrace(executionId, error);
            }
        } catch (Exception e) {
            log.errorf(e, "Failed to report non-fatal error for execution %d", executionId);
        }
    }
    
    /**
     * Utility method for subclasses to update progress.
     * 
     * @param completed Number of completed items
     * @param total Total number of items
     */
    protected void updateProgress(int completed, int total) {
        long executionId = jobContext.getExecutionId();
        
        try {
            trackingService.setTotalSubtasks(executionId, total);
            // Note: This is a simplified approach. In reality, you'd track incremental progress
            int percent = (int)((completed * 100.0) / total);
            // The tracking service will calculate this based on completed/total subtasks
        } catch (Exception e) {
            log.warnf("Failed to update progress for execution %d", executionId);
        }
    }
}