package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecAgeBucketDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecGenderTrendMonthDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    // ============================================================================
    // Executive Dashboard: Gender Diversity Trend (Trailing 24 Months)
    // ============================================================================

    /** Mutable accumulator for {@link #genderTrend(Set)}'s server-side gender pivot. */
    private static final class GenderAcc {
        final String monthKey;
        final int year;
        final int monthNumber;
        long male;
        long female;
        long unknown;
        GenderAcc(String monthKey, int year, int monthNumber) {
            this.monthKey = monthKey;
            this.year = year;
            this.monthNumber = monthNumber;
        }
    }

    /**
     * Returns the trailing-24-months gender diversity trend, mirroring the
     * BFF route at {@code /api/executive/gender-trend}.
     *
     * <p>For each of the 24 months, counts users whose most-recent
     * {@code userstatus} row on or before month-end has {@code status='ACTIVE'}
     * and {@code type IN ('CONSULTANT', 'STUDENT', 'STAFF')}, joined to
     * {@code user.gender}. The "most-recent" predicate is implemented via a
     * NOT EXISTS correlated subquery (matches BFF SQL verbatim). The 24-month
     * series is generated via a {@code UNION} derived table.</p>
     *
     * <p>Pivot is server-side: SQL returns one row per (month, gender), folded
     * into one {@link ExecGenderTrendMonthDTO} per month via {@link LinkedHashMap}
     * (SQL ORDER BY month_key preserves chronological order). MALE/FEMALE counts
     * overwrite (single row per gender per month expected); other values
     * including NULL accumulate into {@code unknownCount}. {@code femalePct} is
     * computed as {@code round((femaleCount / (maleCount + femaleCount)) *
     * 10000) / 100} excluding unknown from the denominator; {@code null} when
     * the denominator is zero.</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return chronologically-ordered list of monthly gender rows (may be empty)
     */
    public List<ExecGenderTrendMonthDTO> genderTrend(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String companyFilter = hasCompanyFilter ? " AND us.companyuuid IN (:companyIds) " : "";

        String sql = "SELECT " +
                "  DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL n MONTH), '%Y%m') AS month_key, " +
                "  YEAR(DATE_SUB(CURDATE(), INTERVAL n MONTH))                AS `year`, " +
                "  MONTH(DATE_SUB(CURDATE(), INTERVAL n MONTH))               AS month_number, " +
                "  u.gender                                                   AS gender, " +
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
                "  AND us.`type` IN ('CONSULTANT', 'STUDENT', 'STAFF') " +
                "  AND us.status = 'ACTIVE' " +
                companyFilter +
                "JOIN `user` u ON u.uuid = us.useruuid " +
                "WHERE NOT EXISTS ( " +
                "  SELECT 1 FROM userstatus us2 " +
                "  WHERE us2.useruuid = us.useruuid " +
                "    AND us2.statusdate >  us.statusdate " +
                "    AND us2.statusdate <= LAST_DAY(DATE_SUB(CURDATE(), INTERVAL n MONTH)) " +
                ") " +
                "GROUP BY month_key, `year`, month_number, u.gender " +
                "ORDER BY month_key, u.gender";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }
        query.setHint("javax.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        Map<String, GenderAcc> monthMap = new LinkedHashMap<>();
        for (Tuple row : rows) {
            String monthKey = row.get("month_key", String.class);
            int year = ((Number) row.get("year")).intValue();
            int monthNumber = ((Number) row.get("month_number")).intValue();
            String gender = row.get("gender", String.class);
            long headcount = ((Number) row.get("headcount")).longValue();

            GenderAcc acc = monthMap.computeIfAbsent(monthKey,
                    k -> new GenderAcc(monthKey, year, monthNumber));
            if ("MALE".equals(gender)) {
                acc.male = headcount;
            } else if ("FEMALE".equals(gender)) {
                acc.female = headcount;
            } else {
                acc.unknown += headcount;
            }
        }

        List<ExecGenderTrendMonthDTO> result = new ArrayList<>(monthMap.size());
        for (GenderAcc acc : monthMap.values()) {
            String monthLabel = Month.of(acc.monthNumber)
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + acc.year;
            long denominator = acc.male + acc.female;
            Double femalePct = denominator > 0
                    ? Math.round((double) acc.female / denominator * 10000.0) / 100.0
                    : null;
            result.add(new ExecGenderTrendMonthDTO(
                    acc.monthKey, monthLabel, acc.year, acc.monthNumber,
                    acc.male, acc.female, acc.unknown, femalePct));
        }

        log.debugf("genderTrend: %d months (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }
}
