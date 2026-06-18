package dk.trustworks.intranet.perf;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InfraMetricsEmitterTest {

    private final InfraMetricsEmitter emitter = new InfraMetricsEmitter();

    @Test
    void toMetrics_includesHeapAndPresentPoolGauges() {
        Map<String, Double> pool = new LinkedHashMap<>();
        pool.put("DbPoolActive", 3.0);
        pool.put("DbPoolAwaiting", 0.0);

        List<PerfMetrics.Metric> metrics = emitter.toMetrics(536_870_912L, pool);

        assertTrue(metrics.stream().anyMatch(m -> m.name().equals("JvmHeapUsedBytes") && m.value() == 536_870_912L));
        assertTrue(metrics.stream().anyMatch(m -> m.name().equals("DbPoolActive") && m.value() == 3.0));
        assertTrue(metrics.stream().anyMatch(m -> m.name().equals("DbPoolAwaiting")));
    }

    @Test
    void toMetrics_skipsNaNGauges() {
        Map<String, Double> pool = new LinkedHashMap<>();
        pool.put("DbPoolActive", Double.NaN);
        List<PerfMetrics.Metric> metrics = emitter.toMetrics(1L, pool);
        assertTrue(metrics.stream().noneMatch(m -> m.name().equals("DbPoolActive")));
    }
}
