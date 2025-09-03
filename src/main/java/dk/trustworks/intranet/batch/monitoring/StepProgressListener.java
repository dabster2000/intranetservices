package dk.trustworks.intranet.batch.monitoring;

import jakarta.batch.api.listener.StepListener;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.context.JobContext;
import jakarta.batch.runtime.context.StepContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named("stepProgressListener")
@Dependent
public class StepProgressListener implements StepListener {

    @Inject JobContext jobContext;
    @Inject StepContext stepContext;
    @Inject BatchJobTrackingService trackingService;

    @Override
    public void beforeStep() {
        long executionId = jobContext.getExecutionId();
        // Count steps to provide coarse progress for non-partitioned jobs
        trackingService.incrementTotalSubtasks(executionId);
    }

    @Override
    public void afterStep() {
        long executionId = jobContext.getExecutionId();
        // Increment completed partitions/steps counter for progress
        trackingService.incrementCompletedSubtasks(executionId);

        // If this step failed, append details for diagnostics
        BatchStatus status = stepContext.getBatchStatus();
        String exitStatus = stepContext.getExitStatus();
        if (status != null && status != BatchStatus.COMPLETED) {
            String msg = "Step '" + stepContext.getStepName() + "' ended with status=" + status +
                    (exitStatus != null ? (", exitStatus=" + exitStatus) : "");
            trackingService.appendDetails(executionId, msg);
        }
    }
}
