package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecAgeBucketDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native-SQL-backed service for the Executive Dashboard people-domain endpoints.
 * Endpoint methods are added by per-endpoint commits.
 */
@JBossLog
@ApplicationScoped
public class ExecutivePeopleService {

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
    // Executive Dashboard: Age Distribution (Current Snapshot)
    // ============================================================================

    /**
     * Returns the current-snapshot age distribution of active employees in
     * 5-year buckets, stacked by gender. Mirrors the BFF route at
     * {@code /api/executive/age-distribution}.
     *
     * <p>Source: {@code userstatus} joined to {@code user} on {@code u.uuid =
     * us.useruuid}. Filters: {@code us.type IN ('CONSULTANT', 'STUDENT', 'STAFF')},
     * {@code us.status='ACTIVE'}, {@code u.birthday IS NOT NULL AND u.birthday
     * != '0000-00-00'}, plus a NOT EXISTS correlated subquery enforcing
     * "most-recent userstatus row" semantics. Buckets are computed via
     * {@code FLOOR(TIMESTAMPDIFF(YEAR, u.birthday, CURDATE()) / 5) * 5}.</p>
     *
     * <p>Pivot is performed server-side: SQL returns one row per
     * (bucket_start, gender) and the service folds those into one
     * {@link ExecAgeBucketDTO} per bucket_start. {@code total} is computed
     * as {@code maleCount + femaleCount + unknownCount}. {@code MALE}/{@code FEMALE}
     * counts overwrite (a single row is expected per gender per bucket); other
     * gender values (including NULL) accumulate into {@code unknownCount}.
     * Buckets are returned in ascending {@code bucketStart} order via
     * {@link LinkedHashMap} insertion order (SQL ORDER BY bucket_start, gender).</p>
     *
     * <p><b>Privacy note:</b> age is HR-sensitive — class-level
     * {@code dashboard:read} on the resource governs access (no method-level
     * override).</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return list of buckets ordered by ascending {@code bucketStart} (may be empty)
     */
    public List<ExecAgeBucketDTO> ageDistribution(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String companyFilter = hasCompanyFilter ? " AND us.companyuuid IN (:companyIds) " : "";

        String sql = "SELECT " +
                "  FLOOR(TIMESTAMPDIFF(YEAR, u.birthday, CURDATE()) / 5) * 5 AS bucket_start, " +
                "  u.gender                                                  AS gender, " +
                "  COUNT(DISTINCT us.useruuid)                               AS headcount " +
                "FROM userstatus us " +
                "JOIN `user` u ON u.uuid = us.useruuid " +
                "WHERE us.`type` IN ('CONSULTANT', 'STUDENT', 'STAFF') " +
                "  AND us.status = 'ACTIVE' " +
                "  AND u.birthday IS NOT NULL " +
                "  AND u.birthday != '0000-00-00' " +
                companyFilter +
                "  AND NOT EXISTS ( " +
                "    SELECT 1 FROM userstatus us2 " +
                "    WHERE us2.useruuid = us.useruuid " +
                "      AND us2.statusdate > us.statusdate " +
                "  ) " +
                "GROUP BY bucket_start, u.gender " +
                "ORDER BY bucket_start, u.gender";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }
        query.setHint("javax.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        // Mutable accumulator distinct from the immutable DTO record.
        final class Acc {
            final int bucketStart;
            long male;
            long female;
            long unknown;
            Acc(int bucketStart) { this.bucketStart = bucketStart; }
        }

        // LinkedHashMap preserves SQL ORDER BY bucket_start insertion order so
        // the response is deterministically ascending.
        Map<Integer, Acc> bucketMap = new LinkedHashMap<>();

        for (Tuple row : rows) {
            int bucketStart = ((Number) row.get("bucket_start")).intValue();
            String gender = row.get("gender", String.class);
            long headcount = ((Number) row.get("headcount")).longValue();

            Acc acc = bucketMap.computeIfAbsent(bucketStart, Acc::new);
            if ("MALE".equals(gender)) {
                acc.male = headcount;
            } else if ("FEMALE".equals(gender)) {
                acc.female = headcount;
            } else {
                // NULL or any other value → unknown (matches BFF default branch)
                acc.unknown += headcount;
            }
        }

        List<ExecAgeBucketDTO> result = new ArrayList<>(bucketMap.size());
        for (Acc acc : bucketMap.values()) {
            String label = acc.bucketStart + "–" + (acc.bucketStart + 4); // en-dash
            long total = acc.male + acc.female + acc.unknown;
            result.add(new ExecAgeBucketDTO(label, acc.bucketStart, acc.male, acc.female, acc.unknown, total));
        }

        log.debugf("ageDistribution: %d buckets (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }
}
