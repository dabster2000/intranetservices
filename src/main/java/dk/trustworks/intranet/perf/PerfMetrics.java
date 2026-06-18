package dk.trustworks.intranet.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits CloudWatch Embedded Metric Format (EMF) lines on a dedicated raw-format
 * logger. One emission feeds both CloudWatch Metrics (auto-extracted) and Logs
 * Insights. Carries only metadata — never tokens, payloads, or PII as dimensions.
 */
@ApplicationScoped
public class PerfMetrics {

    public static final String NAMESPACE = "Trustworks/Perf";

    /** EMF lines go here; application.yml binds this category to a raw %m%n handler. */
    static final Logger PERF_LOG = Logger.getLogger("dk.trustworks.perf");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "dk.trustworks.perf.metrics.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "dk.trustworks.perf.env", defaultValue = "production")
    String env;

    public record Metric(String name, String unit, double value) {}

    public void emitTimer(String metricName, double valueMs,
                          Map<String, String> dimensions, Map<String, Object> fields) {
        emit(List.of(new Metric(metricName, "Milliseconds", valueMs)), dimensions, fields);
    }

    public void emitCount(String metricName, double count,
                          Map<String, String> dimensions, Map<String, Object> fields) {
        emit(List.of(new Metric(metricName, "Count", count)), dimensions, fields);
    }

    /** Emit several metrics sharing one dimension set as a single EMF line. */
    public void emit(List<Metric> metrics, Map<String, String> dimensions, Map<String, Object> fields) {
        if (!enabled || metrics == null || metrics.isEmpty()) return;
        Map<String, String> dims = new LinkedHashMap<>();
        dims.put("env", env);
        if (dimensions != null) dims.putAll(dimensions);
        write(buildEmf(NAMESPACE, metrics, dims, fields, System.currentTimeMillis()));
    }

    /** Package-private seam so unit tests can verify emission without parsing logs. */
    void write(String emfLine) {
        PERF_LOG.info(emfLine);
    }

    /** Pure, deterministic EMF serializer — the unit-tested core. */
    String buildEmf(String namespace, List<Metric> metrics,
                    Map<String, String> dimensions, Map<String, Object> fields, long timestampMs) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode aws = root.putObject("_aws");
        aws.put("Timestamp", timestampMs);
        ArrayNode cwMetrics = aws.putArray("CloudWatchMetrics");
        ObjectNode directive = cwMetrics.addObject();
        directive.put("Namespace", namespace);
        ArrayNode dimsArray = directive.putArray("Dimensions");
        ArrayNode dimSet = dimsArray.addArray();
        for (String key : dimensions.keySet()) dimSet.add(key);
        ArrayNode metricDefs = directive.putArray("Metrics");
        for (Metric m : metrics) {
            ObjectNode md = metricDefs.addObject();
            md.put("Name", m.name());
            md.put("Unit", m.unit());
        }
        dimensions.forEach(root::put);
        for (Metric m : metrics) root.put(m.name(), m.value());
        if (fields != null) fields.forEach((k, v) -> putField(root, k, v));
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"_perfError\":\"emf-serialize-failed\"}";
        }
    }

    private static void putField(ObjectNode node, String key, Object value) {
        if (value == null) { node.putNull(key); return; }
        if (value instanceof Number n) node.put(key, n.doubleValue());
        else if (value instanceof Boolean b) node.put(key, b);
        else node.put(key, String.valueOf(value));
    }
}
