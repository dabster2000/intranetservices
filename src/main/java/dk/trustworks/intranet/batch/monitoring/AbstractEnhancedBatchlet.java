package dk.trustworks.intranet.batch.monitoring;

import jakarta.batch.api.AbstractBatchlet;
import jakarta.batch.api.partition.PartitionCollector;
import jakarta.batch.runtime.context.StepContext;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.io.Serializable;

/**
 * Abstract base class for enhanced batchlets that provide return-based progress tracking.
 * This eliminates the race condition where job completion is checked before async progress updates complete.
 * 
 * Subclasses should:
 * 1. Override performWork() to implement their business logic
 * 2. Return a BatchletResult from performWork() indicating success/failure
 * 3. The base class handles collecting and returning the result through the partition collector mechanism
 */
@JBossLog
@BatchExceptionTracking
public abstract class AbstractEnhancedBatchlet extends AbstractBatchlet implements PartitionCollector {

    @Inject
    protected StepContext stepContext;
    
    protected BatchletResult executionResult;
    protected long startTime;
    
    /**
     * Template method that subclasses must implement.
     * This should contain the actual business logic of the batchlet.
     * 
     * @param partitionId Unique identifier for this partition (e.g., "userUuid_date")
     * @return BatchletResult indicating success, failure, or partial completion
     * @throws Exception if processing fails
     */
    protected abstract BatchletResult performWork(String partitionId) throws Exception;
    
    /**
     * Method to generate the partition ID. Subclasses can override to customize.
     * Default implementation returns a timestamp-based ID.
     */
    protected String generatePartitionId() {
        return "partition_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
    }
    
    @Override
    public final String process() {
        startTime = System.currentTimeMillis();
        String partitionId = generatePartitionId();
        
        try {
            log.debugf("Starting processing for partition %s", partitionId);
            
            // Call the subclass implementation
            executionResult = performWork(partitionId);
            
            // Ensure we have a result
            if (executionResult == null) {
                executionResult = BatchletResult.success("Processing completed");
            }
            
            // Set partition ID and processing time
            executionResult.setPartitionId(partitionId);
            executionResult.setProcessingTimeMs(System.currentTimeMillis() - startTime);
            
            log.debugf("Completed processing for partition %s with status %s in %dms", 
                      partitionId, executionResult.getStatus(), executionResult.getProcessingTimeMs());
            
            // Return appropriate exit status based on result
            switch (executionResult.getStatus()) {
                case SUCCESS:
                    return "COMPLETED";
                case PARTIAL:
                    return "PARTIAL";
                case FAILURE:
                    return "FAILED";
                default:
                    return "UNKNOWN";
            }
            
        } catch (Exception e) {
            long processingTime = System.currentTimeMillis() - startTime;
            log.errorf(e, "Fatal error processing partition %s", partitionId);
            
            executionResult = BatchletResult.failure(
                "Fatal error processing partition " + partitionId,
                e
            );
            executionResult.setPartitionId(partitionId);
            executionResult.setProcessingTimeMs(processingTime);
            
            return "FAILED";
        }
    }
    
    @Override
    public final Serializable collectPartitionData() throws Exception {
        // This is called after process() to collect results from this partition
        if (executionResult == null) {
            BatchletResult fallback = BatchletResult.failure("No execution result was produced for this partition");
            // partitionId and processingTime are best-effort here; they should have been set in process()
            return fallback;
        }
        return executionResult;
    }
    
    /**
     * Helper method to safely execute an operation and track its success/failure.
     * This can be used by subclasses to handle partial failures gracefully.
     */
    protected boolean executeOperation(String operationName, Runnable operation, 
                                      StringBuilder errorMessages) {
        try {
            operation.run();
            log.debugf("Successfully completed operation: %s", operationName);
            return true;
        } catch (Exception e) {
            errorMessages.append(operationName).append(" failed: ").append(e.getMessage()).append("; ");
            log.errorf(e, "Failed to execute operation: %s", operationName);
            return false;
        }
    }
}