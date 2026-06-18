package dk.trustworks.intranet.perf;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits a single EMF snapshot of DB-pool + JVM-heap gauges every 60s. Avoids
 * per-request logging while still giving live infrastructure health.
 */
@JBossLog
@ApplicationScoped
public class InfraMetricsEmitter {

    /** Agroal Micrometer gauge name -> our metric name. Verify names via /q/metrics in dev. */
    private static final Map<String, String> POOL_GAUGES = Map.of(
            "agroal.active.count", "DbPoolActive",
            "agroal.available.count", "DbPoolAvailable",
            "agroal.awaiting.count", "DbPoolAwaiting",
            "agroal.max.used.count", "DbPoolMaxUsed");

    @Inject
    MeterRegistry registry;

    @Inject
    PerfMetrics perfMetrics;

    @Scheduled(every = "60s", identity = "perf-infra-metrics",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void emit() {
        try {
            long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            List<PerfMetrics.Metric> metrics = toMetrics(heapUsed, readPoolGauges());
            perfMetrics.emit(metrics, Map.of(), Map.of());
        } catch (Exception e) {
            log.warnf("perf-infra-metrics emit failed: %s", e.getMessage());
        }
    }

    /** Reads the live Agroal gauges from the registry (integration path). */
    private Map<String, Double> readPoolGauges() {
        Map<String, Double> out = new LinkedHashMap<>();
        POOL_GAUGES.forEach((meterName, metricName) -> {
            Gauge g = registry.find(meterName).gauge();
            out.put(metricName, g != null ? g.value() : Double.NaN);
        });
        return out;
    }

    /** Pure builder — unit tested. */
    List<PerfMetrics.Metric> toMetrics(long heapUsedBytes, Map<String, Double> poolGauges) {
        List<PerfMetrics.Metric> out = new ArrayList<>();
        out.add(new PerfMetrics.Metric("JvmHeapUsedBytes", "Bytes", heapUsedBytes));
        poolGauges.forEach((name, value) -> {
            if (value != null && !Double.isNaN(value)) {
                out.add(new PerfMetrics.Metric(name, "Count", value));
            }
        });
        return out;
    }
}
