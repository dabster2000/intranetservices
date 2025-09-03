package dk.trustworks.intranet.batch.monitoring;

import jakarta.batch.api.partition.PartitionAnalyzer;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;

@Named("partitionProgressAnalyzer")
@Dependent
public class PartitionProgressAnalyzer implements PartitionAnalyzer {

    @Inject JobContext jobContext;
    @Inject BatchJobTrackingService trackingService;

    @Override
    public void analyzeCollectorData(Serializable data) throws Exception {
        // Not used now. Could parse per-partition metrics if collectors are added later.
    }

    @Override
    public void analyzeStatus(BatchStatus batchStatus, String exitStatus) throws Exception {
        long executionId = jobContext.getExecutionId();
        // One call per partition completion. Count it.
        trackingService.incrementCompletedSubtasks(executionId);
        if (batchStatus != BatchStatus.COMPLETED) {
            String msg = "Partition ended with status=" + batchStatus + (exitStatus != null ? (", exitStatus=" + exitStatus) : "");
            trackingService.appendDetails(executionId, msg);
        }
    }
}
