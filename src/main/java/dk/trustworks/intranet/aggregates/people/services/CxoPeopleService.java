package dk.trustworks.intranet.aggregates.people.services;

import dk.trustworks.intranet.aggregates.people.dto.cxo.TurnoverTtmMonthDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Native-SQL-backed service for the CXO Command Center people tab.
 * Endpoint methods are added by per-endpoint commits.
 */
@JBossLog
@ApplicationScoped
public class CxoPeopleService {

    /** Per-query timeout for CXO Command Center endpoints (matches the legacy BFF's 15-second budget). */
    static final int CXO_QUERY_TIMEOUT_MS = 15_000;

    @Inject
    EntityManager em;

    /**
     * Null-safe Tuple value coercion to primitive double. Mirrors the helper in
     * {@code CxoFinanceService}, {@code CxoClientService}, {@code CxoSalesService}, and
     * {@code CxoForecastService} — null → 0.0, primitives unboxed,
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

    // ============================================================================
    // CXO Command Center: Employee Turnover (Trailing 24 Months)
    // ============================================================================

    /**
     * Returns the trailing-24-months hires-vs-terminations curve from
     * {@code userstatus}, mirroring the BFF route at
     * {@code /api/cxo/people/turnover-ttm}. Rows correspond to
     * {@code statusdate} entries within the trailing 24 months for
     * {@code type IN ('CONSULTANT', 'STUDENT', 'STAFF')}. {@code monthLabel}
     * is built server-side via {@code Month.getDisplayName(SHORT, ENGLISH)};
     * {@code net} is computed as {@code hires - terminations}.
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return chronologically-ordered list of monthly turnover rows (may be empty)
     */
    public List<TurnoverTtmMonthDTO> turnoverTtm(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String companyFilter = hasCompanyFilter ? " AND companyuuid IN (:companyIds)" : "";

        String sql = "SELECT " +
                "  DATE_FORMAT(statusdate, '%Y%m')                          AS month_key, " +
                "  YEAR(statusdate)                                         AS year, " +
                "  MONTH(statusdate)                                        AS month_number, " +
                "  SUM(CASE WHEN status = 'ACTIVE'     THEN 1 ELSE 0 END)   AS hires, " +
                "  SUM(CASE WHEN status = 'TERMINATED' THEN 1 ELSE 0 END)   AS terminations " +
                "FROM userstatus " +
                "WHERE statusdate >= DATE_SUB(CURDATE(), INTERVAL 24 MONTH) " +
                "  AND `type` IN ('CONSULTANT', 'STUDENT', 'STAFF')" +
                companyFilter + " " +
                "GROUP BY month_key, year, month_number " +
                "ORDER BY month_key";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }
        query.setHint("javax.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        List<TurnoverTtmMonthDTO> result = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            String monthKey = row.get("month_key", String.class);
            int year = ((Number) row.get("year")).intValue();
            int monthNumber = ((Number) row.get("month_number")).intValue();
            long hires = ((Number) row.get("hires")).longValue();
            long terminations = ((Number) row.get("terminations")).longValue();
            String monthLabel = Month.of(monthNumber).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    + " " + year;
            result.add(new TurnoverTtmMonthDTO(
                    monthKey, monthLabel, year, monthNumber, hires, terminations, hires - terminations));
        }

        log.debugf("turnoverTtm: %d months (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }
}
