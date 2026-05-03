package dk.trustworks.intranet.aggregates.forecast.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

/**
 * Native-SQL-backed service for the CXO Command Center forecast tab.
 * Endpoint methods are added by per-endpoint commits.
 */
@JBossLog
@ApplicationScoped
public class CxoForecastService {

    static final int CXO_QUERY_TIMEOUT_MS = 15_000;

    @Inject
    EntityManager em;

    static double toDouble(Object v) {
        if (v == null) return 0d;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Boolean b) return b ? 1d : 0d;
        if (v instanceof byte[] bytes) return (bytes.length > 0 && bytes[0] != 0) ? 1d : 0d;
        if (v instanceof java.util.BitSet bs) return bs.isEmpty() ? 0d : 1d;
        return Double.parseDouble(v.toString());
    }
}
