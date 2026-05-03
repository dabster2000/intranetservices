package dk.trustworks.intranet.aggregates.people.services;

import dk.trustworks.intranet.aggregates.people.dto.cxo.ConsultantPyramidDTO;
import dk.trustworks.intranet.aggregates.people.dto.cxo.HeadcountGrowthMonthDTO;
import dk.trustworks.intranet.aggregates.people.dto.cxo.PyramidLevelDTO;
import dk.trustworks.intranet.aggregates.people.dto.cxo.TurnoverTtmMonthDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Pyramid bucket descriptor: {@code label} (display + dedup key), the set of
     * {@code career_level} codes that map to this bucket, and the hardcoded target %.
     * Mirrors the {@code PYRAMID_BUCKETS} array in the legacy BFF
     * {@code /api/cxo/people/consultant-pyramid/route.ts} verbatim — same labels,
     * same career-level membership, same target percents.
     */
    private record PyramidBucket(String label, List<String> levels, double targetPercent) {}

    /** Hardcoded pyramid buckets in display order. Matches the BFF source 1:1. */
    private static final List<PyramidBucket> PYRAMID_BUCKETS = List.of(
            new PyramidBucket("Junior",
                    List.of("JUNIOR_CONSULTANT", "PROFESSIONAL_CONSULTANT"), 30.0),
            new PyramidBucket("Mid",
                    List.of("CONSULTANT"), 25.0),
            new PyramidBucket("Senior",
                    List.of("SENIOR_MANAGER", "ENGAGEMENT_MANAGER",
                            "SENIOR_ENGAGEMENT_MANAGER", "MANAGER"), 25.0),
            new PyramidBucket("Leadership",
                    List.of("ASSOCIATE_PARTNER", "ENGAGEMENT_DIRECTOR", "PRACTICE_LEADER",
                            "THOUGHT_LEADER_PARTNER", "MANAGING_DIRECTOR"), 15.0),
            new PyramidBucket("Partner",
                    List.of("PARTNER", "MANAGING_PARTNER"), 5.0)
    );

    /**
     * Reverse index: career_level → bucket label. Built once at class load.
     * Career levels not in this map are silently excluded from any bucket
     * count, but they still contribute to {@code totalConsultants} (matches
     * BFF semantics — the BFF accumulates {@code totalConsultants}
     * unconditionally for every active-consultant row).
     */
    private static final Map<String, String> CAREER_LEVEL_TO_BUCKET;
    static {
        Map<String, String> m = new HashMap<>();
        for (PyramidBucket bucket : PYRAMID_BUCKETS) {
            for (String level : bucket.levels()) {
                m.put(level, bucket.label());
            }
        }
        CAREER_LEVEL_TO_BUCKET = Map.copyOf(m);
    }

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
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

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

    // ============================================================================
    // CXO Command Center: Consultant Pyramid
    // ============================================================================

    /**
     * Returns the current pyramid distribution snapshot of active consultants
     * across 5 hardcoded buckets, mirroring the BFF route at
     * {@code /api/cxo/people/consultant-pyramid}.
     *
     * <p>Source: {@code user_career_level} joined to {@code userstatus} where
     * {@code userstatus.type='CONSULTANT', status='ACTIVE'}, plus a correlated
     * subquery to filter to each user's most-recent {@code user_career_level}
     * row ({@code MAX(active_from)}). Career-level codes are then bucketed
     * server-side via the static {@link #PYRAMID_BUCKETS} table.</p>
     *
     * <p>Unmapped career levels contribute to {@code totalConsultants}
     * (matching BFF — the BFF accumulator is unconditional) but not to any
     * bucket count.</p>
     *
     * <p>The 5 buckets are always returned in fixed order regardless of which
     * buckets have rows, so the chart renderer can rely on the shape.</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return snapshot DTO with 5 buckets in order Junior → Partner
     */
    public ConsultantPyramidDTO consultantPyramid(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        // The BFF joins userstatus a SECOND time (us2) on companyuuid when filter present.
        // Match that here: the primary userstatus join (us) is unfiltered for company,
        // and the company filter is applied via a separate join.
        String companyJoin = hasCompanyFilter
                ? "JOIN userstatus us2 ON us2.useruuid = ucl.useruuid AND us2.companyuuid IN (:companyIds) "
                : "";

        String sql = "SELECT " +
                "  ucl.career_level                       AS career_level, " +
                "  COUNT(DISTINCT ucl.useruuid)           AS consultant_count " +
                "FROM user_career_level ucl " +
                "JOIN userstatus us ON us.useruuid = ucl.useruuid " +
                "  AND us.type = 'CONSULTANT' " +
                "  AND us.status = 'ACTIVE' " +
                companyJoin +
                "WHERE ucl.active_from = ( " +
                "  SELECT MAX(ucl2.active_from) " +
                "  FROM user_career_level ucl2 " +
                "  WHERE ucl2.useruuid = ucl.useruuid " +
                ") " +
                "GROUP BY ucl.career_level " +
                "ORDER BY consultant_count DESC";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        // Aggregate raw rows into bucket counts. LinkedHashMap preserves the
        // PYRAMID_BUCKETS insertion order so the response shape is deterministic.
        Map<String, Long> bucketCounts = new LinkedHashMap<>();
        for (PyramidBucket bucket : PYRAMID_BUCKETS) {
            bucketCounts.put(bucket.label(), 0L);
        }
        long totalConsultants = 0L;
        for (Tuple row : rows) {
            String careerLevel = row.get("career_level", String.class);
            long count = ((Number) row.get("consultant_count")).longValue();
            String bucketLabel = CAREER_LEVEL_TO_BUCKET.get(careerLevel);
            // totalConsultants is incremented unconditionally to match the BFF's
            // unconditional accumulator. Unmapped career levels (e.g.
            // SENIOR_CONSULTANT, LEAD_CONSULTANT, MANAGING_CONSULTANT,
            // PRINCIPAL_CONSULTANT) still contribute to the total but are not
            // counted in any bucket.
            totalConsultants += count;
            if (bucketLabel != null) {
                bucketCounts.merge(bucketLabel, count, Long::sum);
            }
        }

        List<PyramidLevelDTO> levels = new ArrayList<>(PYRAMID_BUCKETS.size());
        for (PyramidBucket bucket : PYRAMID_BUCKETS) {
            long actualCount = bucketCounts.get(bucket.label());
            double actualPercent = totalConsultants > 0
                    ? Math.round((double) actualCount / totalConsultants * 10000.0) / 100.0
                    : 0.0;
            levels.add(new PyramidLevelDTO(
                    bucket.label(), bucket.levels(), actualCount, actualPercent, bucket.targetPercent()));
        }

        String snapshotDate = LocalDate.now().toString(); // ISO YYYY-MM-DD
        log.debugf("consultantPyramid: total=%d (companyFilter=%s)",
                Long.valueOf(totalConsultants), Boolean.toString(hasCompanyFilter));
        return new ConsultantPyramidDTO(levels, totalConsultants, snapshotDate);
    }

    // ============================================================================
    // CXO Command Center: Headcount Growth (Trailing 24 Months)
    // ============================================================================

    /**
     * Mutable accumulator for {@link #headcountGrowth(Set)}'s server-side type
     * pivot. Distinct from the immutable {@link HeadcountGrowthMonthDTO}
     * record returned to clients — used only as scratch state inside the
     * service.
     */
    private static final class HeadcountAccumulator {
        final String monthKey;
        final int year;
        final int monthNumber;
        long consultant;
        long student;
        long staff;
        HeadcountAccumulator(String monthKey, int year, int monthNumber) {
            this.monthKey = monthKey;
            this.year = year;
            this.monthNumber = monthNumber;
        }
    }

    /**
     * Returns the trailing-24-months headcount-by-type curve from
     * {@code userstatus}, mirroring the BFF route at
     * {@code /api/cxo/people/headcount-growth}.
     *
     * <p>For each of the 24 months, counts users whose most-recent
     * {@code userstatus} row on or before month-end has {@code status='ACTIVE'}
     * and {@code type IN ('CONSULTANT', 'STUDENT', 'STAFF')}. The "most-recent"
     * predicate is implemented via a NOT EXISTS correlated subquery (matches
     * BFF SQL verbatim). The 24-month series is generated via a {@code UNION}
     * derived table (rather than a recursive CTE) — same as BFF.</p>
     *
     * <p>Rows are pivoted server-side via a {@link LinkedHashMap} keyed by
     * month_key so the response shape is deterministic and ordered chronologically.
     * {@code total} is computed as {@code consultant + student + staff}.
     * {@code monthLabel} is derived from {@code (year, monthNumber)} via
     * {@code Month.getDisplayName(SHORT, ENGLISH)}.</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return chronologically-ordered list of monthly headcount rows (may be empty)
     */
    public List<HeadcountGrowthMonthDTO> headcountGrowth(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String companyFilter = hasCompanyFilter ? " AND us.companyuuid IN (:companyIds)" : "";

        String sql = "SELECT " +
                "  DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL n MONTH), '%Y%m') AS month_key, " +
                "  YEAR(DATE_SUB(CURDATE(), INTERVAL n MONTH))                AS year, " +
                "  MONTH(DATE_SUB(CURDATE(), INTERVAL n MONTH))               AS month_number, " +
                "  us.type                                                    AS type, " +
                "  COUNT(DISTINCT us.useruuid)                                AS headcount " +
                "FROM ( " +
                "  SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 " +
                "  UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 " +
                "  UNION SELECT 8 UNION SELECT 9 UNION SELECT 10 UNION SELECT 11 " +
                "  UNION SELECT 12 UNION SELECT 13 UNION SELECT 14 UNION SELECT 15 " +
                "  UNION SELECT 16 UNION SELECT 17 UNION SELECT 18 UNION SELECT 19 " +
                "  UNION SELECT 20 UNION SELECT 21 UNION SELECT 22 UNION SELECT 23 " +
                ") nums " +
                "JOIN userstatus us " +
                "  ON  us.statusdate <= LAST_DAY(DATE_SUB(CURDATE(), INTERVAL n MONTH)) " +
                "  AND us.type IN ('CONSULTANT', 'STUDENT', 'STAFF') " +
                "  AND us.status = 'ACTIVE' " +
                companyFilter + " " +
                "WHERE NOT EXISTS ( " +
                "  SELECT 1 FROM userstatus us2 " +
                "  WHERE us2.useruuid = us.useruuid " +
                "    AND us2.statusdate >  us.statusdate " +
                "    AND us2.statusdate <= LAST_DAY(DATE_SUB(CURDATE(), INTERVAL n MONTH)) " +
                ") " +
                "GROUP BY month_key, year, month_number, us.type " +
                "ORDER BY month_key, us.type";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        // Pivot type rows into one row per month. LinkedHashMap preserves SQL
        // ORDER BY month_key insertion order so the response is chronologically
        // ordered and deterministic.
        Map<String, HeadcountAccumulator> monthMap = new LinkedHashMap<>();
        for (Tuple row : rows) {
            String monthKey = row.get("month_key", String.class);
            int year = ((Number) row.get("year")).intValue();
            int monthNumber = ((Number) row.get("month_number")).intValue();
            String type = row.get("type", String.class);
            long headcount = ((Number) row.get("headcount")).longValue();

            HeadcountAccumulator acc = monthMap.computeIfAbsent(monthKey,
                    k -> new HeadcountAccumulator(monthKey, year, monthNumber));
            switch (type) {
                case "CONSULTANT" -> acc.consultant = headcount;
                case "STUDENT"    -> acc.student    = headcount;
                case "STAFF"      -> acc.staff      = headcount;
                // Other types ignored (defensive — SQL filter restricts to these three).
            }
        }

        List<HeadcountGrowthMonthDTO> result = new ArrayList<>(monthMap.size());
        for (HeadcountAccumulator acc : monthMap.values()) {
            String monthLabel = Month.of(acc.monthNumber)
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + acc.year;
            long total = acc.consultant + acc.student + acc.staff;
            result.add(new HeadcountGrowthMonthDTO(
                    acc.monthKey, monthLabel, acc.year, acc.monthNumber,
                    acc.consultant, acc.student, acc.staff, total));
        }

        log.debugf("headcountGrowth: %d months (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }
}
