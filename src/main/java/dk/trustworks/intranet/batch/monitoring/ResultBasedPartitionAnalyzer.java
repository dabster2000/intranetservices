package dk.trustworks.intranet.batch.monitoring;

import jakarta.batch.api.partition.PartitionAnalyzer;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@JBossLog
@Dependent
@ActivateRequestContext
@Named("resultBasedPartitionAnalyzer")
public class ResultBasedPartitionAnalyzer implements PartitionAnalyzer {

    @Inject JobContext jobContext;
    @Inject BatchJobTrackingService trackingService;
    
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger partialCount = new AtomicInteger(0);
    private final ConcurrentHashMap<String, BatchletResult> partitionResults = new ConcurrentHashMap<>();
    
    @Override
    public void analyzeCollectorData(Serializable data) {
        if (data instanceof BatchletResult result) {
            String partitionId = result.getPartitionId() != null ? result.getPartitionId() : "partition-" + System.nanoTime();
            partitionResults.put(partitionId, result);
            
            switch (result.getStatus()) {
                case SUCCESS:
                    completedCount.incrementAndGet();
                    break;
                case FAILURE:
                    failedCount.incrementAndGet();
                    break;
                case PARTIAL:
                    partialCount.incrementAndGet();
                    break;
            }
            
            long executionId = jobContext.getExecutionId();
            updateProgress(executionId);
            
            if (!result.isSuccess()) {
                String msg = String.format("Partition %s: %s - %s", 
                    partitionId, 
                    result.getStatus(), 
                    result.getMessage() != null ? result.getMessage() : "");
                trackingService.appendDetails(executionId, msg);
                
                if (result.getException() != null) {
                    trackingService.setTrace(executionId, result.getException());
                }
            }
            
            log.debugf("[PARTITION-ANALYZER] Analyzed partition result: %s - Status: %s", 
                      partitionId, result.getStatus());
        }
    }
    
    @Override
    public void analyzeStatus(BatchStatus batchStatus, String exitStatus) throws Exception {
        long executionId = jobContext.getExecutionId();
        
        if (exitStatus != null && exitStatus.equals("COMPLETED")) {
            completedCount.incrementAndGet();
        } else if (batchStatus != BatchStatus.COMPLETED) {
            failedCount.incrementAndGet();
            String msg = "Partition ended with status=" + batchStatus + 
                        (exitStatus != null ? (", exitStatus=" + exitStatus) : "");
            trackingService.appendDetails(executionId, msg);
        } else {
            completedCount.incrementAndGet();
        }
        
        updateProgress(executionId);
    }
    
    private void updateProgress(long executionId) {
        int total = completedCount.get() + failedCount.get() + partialCount.get();
        trackingService.setCompletedSubtasksSynchronous(executionId, total);
        
        log.debugf("[PARTITION-ANALYZER] Progress updated - Completed: %d, Failed: %d, Partial: %d, Total: %d",
                  completedCount.get(), failedCount.get(), partialCount.get(), total);
    }
    
    public int getCompletedCount() {
        return completedCount.get();
    }
    
    public int getFailedCount() {
        return failedCount.get();
    }
    
    public int getPartialCount() {
        return partialCount.get();
    }
    
    public int getTotalProcessed() {
        return completedCount.get() + failedCount.get() + partialCount.get();
    }
    
    public ConcurrentHashMap<String, BatchletResult> getPartitionResults() {
        return partitionResults;
    }
}