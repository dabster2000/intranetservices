package dk.trustworks.intranet.batch.monitoring;

import jakarta.annotation.Priority;
import jakarta.batch.runtime.context.JobContext;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import lombok.extern.jbosslog.JBossLog;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI interceptor that captures exceptions from batch method executions.
 * 
 * This interceptor can be applied to existing batchlet classes or methods to add
 * exception tracking without modifying the implementation. It's particularly useful
 * for retrofitting exception tracking to legacy batch jobs.
 * 
 * Usage:
 * <pre>
 * {@code
 * @Named("myBatchlet")
 * @BatchExceptionTracking
 * public class MyBatchlet extends AbstractBatchlet {
 *     public String process() throws Exception {
 *         // Existing code - no changes needed
 *     }
 * }
 * }
 * </pre>
 * 
 * Or apply to specific methods:
 * <pre>
 * {@code
 * @BatchExceptionTracking
 * public String process() throws Exception {
 *     // Method-level application
 * }
 * }
 * </pre>
 */
@Interceptor
@BatchExceptionTracking
@Priority(Interceptor.Priority.APPLICATION + 100)
@JBossLog
@Deprecated
public class BatchExceptionInterceptor {
    
    @Inject
    private BatchExceptionRegistry exceptionRegistry;
    
    @Inject
    private BatchJobTrackingService trackingService;
    
    @Inject
    private JobContext jobContext;
    
    @AroundInvoke
    public Object interceptBatchMethod(InvocationContext context) throws Exception {
        // Only intercept if we're in a batch context
        if (jobContext == null) {
            return context.proceed();
        }
        
        long executionId = 0;
        String jobName = "unknown";
        
        try {
            executionId = jobContext.getExecutionId();
            jobName = jobContext.getJobName();
        } catch (Exception e) {
            // Not in a batch context, proceed normally
            return context.proceed();
        }
        
        String methodName = context.getMethod().getName();
        log.debugf("Intercepting method %s for job %s (execution %d)", 
                  methodName, jobName, executionId);
        
        try {
            // Proceed with the actual method execution
            Object result = context.proceed();
            
            log.debugf("Method %s completed successfully for job %s (execution %d)", 
                      methodName, jobName, executionId);
            
            return result;
            
        } catch (Exception e) {
            // Capture the exception
            captureException(executionId, jobName, methodName, e);
            
            // Re-throw to maintain normal flow
            throw e;
            
        } catch (Error e) {
            // Also capture Errors
            captureException(executionId, jobName, methodName, e);
            throw e;
        }
    }
    
    private void captureException(long executionId, String jobName, String methodName, Throwable e) {
        log.errorf(e, "Intercepted exception in method %s for job %s (execution %d)", 
                  methodName, jobName, executionId);
        
        // 1. Register in exception registry
        try {
            exceptionRegistry.captureException(executionId, e);
        } catch (Exception regEx) {
            log.errorf(regEx, "Failed to register exception for execution %d", executionId);
        }
        
        // 2. Update tracking service directly
        try {
            String details = String.format("Exception in %s: %s: %s",
                methodName,
                e.getClass().getSimpleName(),
                e.getMessage() != null ? e.getMessage() : "No message");
            
            trackingService.appendDetails(executionId, details);
            trackingService.setTrace(executionId, e);
        } catch (Exception trackEx) {
            log.errorf(trackEx, "Failed to update tracking for execution %d", executionId);
        }
    }
}

