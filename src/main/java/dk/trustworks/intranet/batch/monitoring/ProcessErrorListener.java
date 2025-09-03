package dk.trustworks.intranet.batch.monitoring;

import jakarta.batch.api.chunk.listener.ItemProcessListener;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
@Dependent
public class ProcessErrorListener implements ItemProcessListener {

    @Inject JobContext jobCtx;
    @Inject BatchJobTrackingService tracking;

    @Override
    public void beforeProcess(Object item) {}

    @Override
    public void afterProcess(Object item, Object result) {}

    @Override
    public void onProcessError(Object item, Exception e) {
        tracking.onJobFailure(jobCtx.getExecutionId(), e, "PROCESS_ERROR");
    }
}
