package dk.trustworks.intranet.batch.monitoring;

import dk.trustworks.intranet.perf.PerfMetrics;
import jakarta.batch.runtime.BatchStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class JobMonitoringPerfTest {

    @Test
    void emitJobPerf_mapsCompletedToSuccess_andEmitsDurationAndCount() {
        PerfMetrics perf = mock(PerfMetrics.class);
        JobMonitoringListener listener = new JobMonitoringListener();
        listener.perfMetrics = perf;

        listener.emitJobPerf("finance-load-economics", BatchStatus.COMPLETED, 42L, 16432.0);

        verify(perf).emitTimer(eq("BatchJobDurationMs"), eq(16432.0),
                eq(Map.of("job", "finance-load-economics")), anyMap());

        ArgumentCaptor<Map<String, String>> dims = ArgumentCaptor.forClass(Map.class);
        verify(perf).emitCount(eq("BatchJobRuns"), eq(1.0), dims.capture(), anyMap());
        assertEquals("success", dims.getValue().get("outcome"));
        assertEquals("finance-load-economics", dims.getValue().get("job"));
    }

    @Test
    void emitJobPerf_mapsFailedToFailed() {
        PerfMetrics perf = mock(PerfMetrics.class);
        JobMonitoringListener listener = new JobMonitoringListener();
        listener.perfMetrics = perf;

        listener.emitJobPerf("expense-sync", BatchStatus.FAILED, 7L, 100.0);

        ArgumentCaptor<Map<String, String>> dims = ArgumentCaptor.forClass(Map.class);
        verify(perf).emitCount(eq("BatchJobRuns"), eq(1.0), dims.capture(), anyMap());
        assertEquals("failed", dims.getValue().get("outcome"));
    }
}
