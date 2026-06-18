package dk.trustworks.intranet.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PerfMetricsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PerfMetrics perf = new PerfMetrics();

    @Test
    void buildEmf_producesValidEnvelope_withDimensionsAndMetricValues() throws Exception {
        Map<String, String> dims = new LinkedHashMap<>();
        dims.put("env", "production");
        dims.put("job", "finance-load-economics");

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("executionId", 42L);
        fields.put("traceId", "abc123");

        String line = perf.buildEmf(
                "Trustworks/Perf",
                List.of(new PerfMetrics.Metric("BatchJobDurationMs", "Milliseconds", 16432.0)),
                dims, fields, 1718700000000L);

        JsonNode root = mapper.readTree(line);

        // EMF envelope
        JsonNode directive = root.path("_aws").path("CloudWatchMetrics").get(0);
        assertEquals("Trustworks/Perf", directive.path("Namespace").asText());
        assertEquals(1718700000000L, root.path("_aws").path("Timestamp").asLong());
        // dimension set lists the dimension KEYS
        JsonNode dimSet = directive.path("Dimensions").get(0);
        assertEquals("env", dimSet.get(0).asText());
        assertEquals("job", dimSet.get(1).asText());
        // metric definition
        JsonNode metricDef = directive.path("Metrics").get(0);
        assertEquals("BatchJobDurationMs", metricDef.path("Name").asText());
        assertEquals("Milliseconds", metricDef.path("Unit").asText());
        // top-level dimension VALUES present
        assertEquals("production", root.path("env").asText());
        assertEquals("finance-load-economics", root.path("job").asText());
        // top-level metric VALUE present
        assertEquals(16432.0, root.path("BatchJobDurationMs").asDouble(), 0.0001);
        // fields present for drill-down
        assertEquals("abc123", root.path("traceId").asText());
        assertEquals(42.0, root.path("executionId").asDouble(), 0.0001);
    }

    @Test
    void buildEmf_isSingleLineJson() {
        String line = perf.buildEmf("Trustworks/Perf",
                List.of(new PerfMetrics.Metric("X", "Count", 1.0)),
                Map.of("env", "staging"), Map.of(), 1L);
        assertFalse(line.contains("\n"), "EMF must be a single JSON line");
    }

    @Test
    void emit_doesNothing_whenDisabled() {
        PerfMetrics spy = Mockito.spy(new PerfMetrics());
        spy.enabled = false;
        spy.env = "production";
        spy.emitCount("X", 1, Map.of(), Map.of());
        Mockito.verify(spy, Mockito.never()).write(Mockito.anyString());
    }

    @Test
    void emit_writesOnce_whenEnabled_andPrependsEnvDimension() {
        PerfMetrics spy = Mockito.spy(new PerfMetrics());
        spy.enabled = true;
        spy.env = "staging";
        Mockito.doNothing().when(spy).write(Mockito.anyString());
        spy.emitCount("ExternalApiCount", 1, Map.of("api", "economic"), Map.of());
        Mockito.verify(spy, Mockito.times(1)).write(Mockito.contains("\"env\":\"staging\""));
    }
}
