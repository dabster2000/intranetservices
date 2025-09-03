package dk.trustworks.intranet.batch.monitoring;

import jakarta.batch.api.chunk.listener.ChunkListener;
import jakarta.batch.runtime.context.JobContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
@Dependent
public class ChunkErrorListener implements ChunkListener {

    @Inject JobContext jobCtx;
    @Inject BatchJobTrackingService tracking;

    @Override public void beforeChunk() {}
    @Override public void afterChunk() {}

    @Override
    public void onError(Exception e) {
        tracking.onJobFailure(jobCtx.getExecutionId(), e, "CHUNK_ERROR");
    }
}
