package dk.trustworks.intranet.perf;

import io.quarkus.arc.Arc;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Times outbound REST-client calls and emits ExternalApiDurationMs / ExternalApiCount.
 * Registered per-client via @RegisterProvider (no global @Provider client filters exist
 * in this codebase). Emits only metadata — never headers, bodies, or tokens.
 */
public class PerfRestClientFilter implements ClientRequestFilter, ClientResponseFilter {

    private static final String START_PROP = "dk.trustworks.perf.start-nanos";

    @Override
    public void filter(ClientRequestContext requestContext) {
        requestContext.setProperty(START_PROP, System.nanoTime());
    }

    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) {
        Object start = requestContext.getProperty(START_PROP);
        if (!(start instanceof Long startNanos)) return;
        double ms = (System.nanoTime() - startNanos) / 1_000_000.0;

        URI uri = requestContext.getUri();
        String api = apiLabel(uri);
        String operation = operationLabel(requestContext.getMethod(), uri);
        int status = responseContext.getStatus();

        Map<String, String> durDims = new HashMap<>();
        durDims.put("api", api);
        durDims.put("operation", operation);
        Map<String, String> countDims = new HashMap<>();
        countDims.put("api", api);
        countDims.put("httpStatusClass", statusClass(status));
        Map<String, Object> fields = new HashMap<>();
        fields.put("method", requestContext.getMethod());
        fields.put("httpStatus", status);

        PerfMetrics perf = perfMetrics();
        if (perf == null) return; // CDI not available (e.g. unit-test path)
        perf.emitTimer("ExternalApiDurationMs", ms, durDims, fields);
        perf.emitCount("ExternalApiCount", 1, countDims, fields);
    }

    private PerfMetrics perfMetrics() {
        var handle = Arc.container() != null ? Arc.container().instance(PerfMetrics.class) : null;
        return handle != null ? handle.get() : null;
    }

    static String apiLabel(URI uri) {
        String host = uri.getHost() == null ? "unknown" : uri.getHost();
        if (host.contains("e-conomic")) return "economic";
        if (host.contains("graph.microsoft")) return "graph";
        if (host.contains("openai")) return "openai";
        if (host.contains("nextsign")) return "nextsign";
        if (host.contains("virkdata")) return "cvr";
        if (host.contains("cvtool")) return "cvtool";
        return host;
    }

    /** Last up-to-3 path segments, numeric/long ids collapsed to {id}, bounded for cardinality. */
    static String operationLabel(String method, URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        Deque<String> tail = new ArrayDeque<>();
        String[] segs = path.split("/");
        for (int i = segs.length - 1; i >= 0 && tail.size() < 3; i--) {
            String s = segs[i];
            if (s.isBlank()) continue;
            String norm = (s.matches(".*\\d.*") || s.length() > 24) ? "{id}" : s;
            tail.addFirst(norm);
        }
        return method + (tail.isEmpty() ? "" : " " + String.join(" ", tail));
    }

    static String statusClass(int status) {
        return (status / 100) + "xx";
    }
}
