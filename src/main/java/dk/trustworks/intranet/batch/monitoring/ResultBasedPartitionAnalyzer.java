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
    @Inject BatchJobTrackingQuery trackingQuery;

    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger partialCount = new AtomicInteger(0);
    private final ConcurrentHashMap<String, BatchletResult> partitionResults = new ConcurrentHashMap<>();

    // Progress logging state
    private volatile Long startTime = null;
    private volatile int lastLoggedPercent = 0;
    private static final int[] PROGRESS_MILESTONES = {5, 10, 25, 50, 75, 90, 95, 99, 100};
    
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
        // Initialize start time on first partition
        if (startTime == null) {
            synchronized (this) {
                if (startTime == null) {
                    startTime = System.currentTimeMillis();
                }
            }
        }

        int completed = completedCount.get();
        int failed = failedCount.get();
        int partial = partialCount.get();
        int totalProcessed = completed + failed + partial;

        trackingService.setCompletedSubtasksSynchronous(executionId, totalProcessed);

        // Get total subtasks to calculate percentage
        Integer totalSubtasks = trackingQuery.findByExecutionId(executionId)
                .map(BatchJobExecutionTracking::getTotalSubtasks)
                .orElse(null);

        if (totalSubtasks != null && totalSubtasks > 0) {
            int currentPercent = (int) ((totalProcessed * 100.0) / totalSubtasks);

            // Log at progress milestones
            for (int milestone : PROGRESS_MILESTONES) {
                if (currentPercent >= milestone && lastLoggedPercent < milestone) {
                    lastLoggedPercent = milestone;

                    // Calculate ETA
                    long elapsed = System.currentTimeMillis() - startTime;
                    String eta = "";
                    if (totalProcessed > 0 && currentPercent < 100) {
                        long avgTimePerPartition = elapsed / totalProcessed;
                        long remaining = totalSubtasks - totalProcessed;
                        long etaMillis = avgTimePerPartition * remaining;
                        eta = String.format(" - ETA: ~%s", formatDuration(etaMillis));
                    }

                    String jobName = jobContext.getJobName() != null ? jobContext.getJobName().toUpperCase() : "JOB";
                    log.infof("[%s] Progress: %d%% (%,d/%,d partitions) - Failed: %d, Partial: %d%s",
                             jobName, currentPercent, totalProcessed, totalSubtasks, failed, partial, eta);
                    break;
                }
            }
        }

        log.debugf("[PARTITION-ANALYZER] Progress updated - Completed: %d, Failed: %d, Partial: %d, Total: %d",
                  completed, failed, partial, totalProcessed);
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + " sec";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + " min";
        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;
        if (hours < 24) return hours + "h " + remainingMinutes + "m";
        long days = hours / 24;
        long remainingHours = hours % 24;
        return days + "d " + remainingHours + "h";
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