package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

/**
 * Native-SQL-backed service for the CXO Command Center practices tab.
 * Endpoint methods are added by per-endpoint commits.
 */
@JBossLog
@ApplicationScoped
public class CxoPracticesService {

    /** Per-query timeout for CXO Command Center endpoints (matches the legacy BFF's 15-second budget). */
    static final int CXO_QUERY_TIMEOUT_MS = 15_000;

    @Inject
    EntityManager em;

    /**
     * Null-safe Tuple value coercion to primitive double. Mirrors the helper in
     * sibling CXO services — null → 0.0, primitives unboxed,
     * Booleans/byte[]/BitSet treated as 1.0/0.0 truthy.
     */
    static double toDouble(Object v) {
        if (v == null) return 0d;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1d : 0d;
        if (v instanceof byte[] bytes) return (bytes.length > 0 && bytes[0] != 0) ? 1d : 0d;
        if (v instanceof java.util.BitSet bs) return bs.isEmpty() ? 0d : 1d;
        return Double.parseDouble(v.toString());
    }

    /**
     * Null-safe Tuple value coercion to boxed Double. NULL values are preserved as
     * {@code null} in the wire shape (e.g. ratios where the divisor is zero) so
     * Jackson serializes them as JSON {@code null} rather than zero.
     */
    static Double toDoubleBoxed(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1d : 0d;
        return Double.parseDouble(v.toString());
    }
}
