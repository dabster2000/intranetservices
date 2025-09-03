package dk.trustworks.intranet.batch.examples;

import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

/**
 * Examples demonstrating the three approaches for adding exception tracking to batch jobs.
 * 
 * These examples show how to retrofit existing batchlets and create new ones with
 * automatic exception capture and persistence.
 */
@JBossLog
public class ExceptionTrackingExamples {
    
    /**
     * APPROACH 1: New batchlets should extend MonitoredBatchlet
     * 
     * This is the recommended approach for new batch jobs. Simply extend MonitoredBatchlet
     * and implement the doProcess() method. Exceptions are automatically captured.
     */
    @Named("exampleMonitoredBatchlet")
    @Dependent
    public static class ExampleMonitoredBatchlet extends MonitoredBatchlet {
        
        @Override
        protected String doProcess() throws Exception {
            log.info("Starting example monitored batchlet");
            
            // Simulate some work
            for (int i = 0; i < 10; i++) {
                if (i == 5) {
                    // This exception will be automatically captured and persisted
                    throw new RuntimeException("Simulated failure at step " + i);
                }
                
                // Update progress (optional)
                updateProgress(i, 10);
                
                Thread.sleep(100);
            }
            
            return "COMPLETED";
        }
        
        @Override
        protected void onFinally(long executionId, String jobName) {
            // Optional cleanup
            log.info("Cleaning up after job execution");
        }
    }
    
    /**
     * APPROACH 2: Retrofit existing batchlets with @BatchExceptionTracking
     * 
     * For existing batchlets that can't be easily refactored, add the @BatchExceptionTracking
     * annotation to the class or specific methods. The interceptor will capture exceptions.
     */
    @Named("exampleInterceptedBatchlet")
    @Dependent
    @BatchExceptionTracking  // Add this annotation to enable exception tracking
    public static class ExampleInterceptedBatchlet extends AbstractBatchlet {
        
        @Override
        public String process() throws Exception {
            log.info("Starting example intercepted batchlet");
            
            // Existing code doesn't need to change
            performWork();
            
            return "COMPLETED";
        }
        
        private void performWork() throws Exception {
            // Simulate work that might fail
            if (Math.random() > 0.5) {
                throw new IllegalStateException("Random failure in intercepted batchlet");
            }
        }
    }
    
    /**
     * APPROACH 3: Manual exception handling (for special cases)
     * 
     * If you need fine-grained control over exception handling, you can manually
     * inject and use the exception registry. This is rarely needed.
     */
    @Named("exampleManualBatchlet")
    @Dependent
    public static class ExampleManualBatchlet extends AbstractBatchlet {
        
        @jakarta.inject.Inject
        private jakarta.batch.runtime.context.JobContext jobContext;
        
        @jakarta.inject.Inject
        private dk.trustworks.intranet.batch.monitoring.BatchExceptionRegistry exceptionRegistry;
        
        @Override
        public String process() throws Exception {
            long executionId = jobContext.getExecutionId();
            
            try {
                log.info("Starting example manual batchlet");
                
                // Your batch logic here
                riskyOperation();
                
                return "COMPLETED";
                
            } catch (Exception e) {
                // Manually capture the exception
                exceptionRegistry.captureException(executionId, e);
                
                // Re-throw to maintain batch semantics
                throw e;
            }
        }
        
        private void riskyOperation() throws Exception {
            throw new UnsupportedOperationException("Operation not implemented");
        }
    }
    
    /**
     * MIGRATION GUIDE for existing batchlets like BirthdayNotificationBatchlet:
     * 
     * Option 1 (Minimal change - Add annotation):
     * 
     * @Named("birthdayNotificationBatchlet")
     * @Dependent
     * @BatchExceptionTracking  // <-- Just add this line
     * public class BirthdayNotificationBatchlet extends AbstractBatchlet {
     *     // No other changes needed
     * }
     * 
     * Option 2 (Better - Extend MonitoredBatchlet):
     * 
     * @Named("birthdayNotificationBatchlet")
     * @Dependent
     * public class BirthdayNotificationBatchlet extends MonitoredBatchlet {  // <-- Change parent
     *     
     *     @Override
     *     protected String doProcess() throws Exception {  // <-- Rename method
     *         // Move existing process() logic here
     *     }
     * }
     * 
     * Option 3 (Quick fix - Wrap in try-catch):
     * 
     * public String process() throws Exception {
     *     long executionId = jobContext.getExecutionId();
     *     try {
     *         // Existing logic
     *     } catch (Exception e) {
     *         exceptionRegistry.captureException(executionId, e);
     *         throw e;
     *     }
     * }
     */
}