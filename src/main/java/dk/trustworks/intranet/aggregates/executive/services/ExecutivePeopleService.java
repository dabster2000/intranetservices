package dk.trustworks.intranet.aggregates.executive.services;

import dk.trustworks.intranet.aggregates.executive.dto.people.ExecAgeBucketDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecCareerLevelDistDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecGenderTrendMonthDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecHeadcountByTypeMonthDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecRetentionCohortDTO;
import dk.trustworks.intranet.aggregates.executive.dto.people.ExecRetentionCohortPointDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.CXO_QUERY_TIMEOUT_MS;
import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toInt;
import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toLong;

import java.sql.Date;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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

    @Inject
    EntityManager em;

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
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows;
        try {
            rows = query.getResultList();
        } catch (PersistenceException pe) {
            log.errorf(pe, "ageDistribution failed (companyFilter=%s)",
                    hasCompanyFilter ? "yes" : "none");
            throw pe;
        }

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
            int bucketStart = toInt(row.get("bucket_start"));
            String gender = row.get("gender", String.class);
            long headcount = toLong(row.get("headcount"));

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
        int dropped = 0;
        for (Acc acc : bucketMap.values()) {
            try {
                String label = acc.bucketStart + "–" + (acc.bucketStart + 4); // en-dash
                long total = acc.male + acc.female + acc.unknown;
                result.add(new ExecAgeBucketDTO(label, acc.bucketStart, acc.male, acc.female, acc.unknown, total));
            } catch (IllegalArgumentException e) {
                dropped++;
                log.warnf("Skipping malformed row in ageDistribution (dropped=%d, bucketStart=%d): %s",
                        dropped, acc.bucketStart, e.getMessage());
            }
        }
        if (dropped > 0) {
            log.warnf("ageDistribution dropped %d malformed buckets out of %d",
                    dropped, bucketMap.size());
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
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows;
        try {
            rows = query.getResultList();
        } catch (PersistenceException pe) {
            log.errorf(pe, "genderTrend failed (companyFilter=%s)",
                    hasCompanyFilter ? "yes" : "none");
            throw pe;
        }

        Map<String, GenderAcc> monthMap = new LinkedHashMap<>();
        for (Tuple row : rows) {
            String monthKey = row.get("month_key", String.class);
            int year = toInt(row.get("year"));
            int monthNumber = toInt(row.get("month_number"));
            String gender = row.get("gender", String.class);
            long headcount = toLong(row.get("headcount"));

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
        int dropped = 0;
        for (GenderAcc acc : monthMap.values()) {
            try {
                String monthLabel = Month.of(acc.monthNumber)
                        .getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + acc.year;
                long denominator = acc.male + acc.female;
                Double femalePct = denominator > 0
                        ? Math.round((double) acc.female / denominator * 10000.0) / 100.0
                        : null;
                result.add(new ExecGenderTrendMonthDTO(
                        acc.monthKey, monthLabel, acc.year, acc.monthNumber,
                        acc.male, acc.female, acc.unknown, femalePct));
            } catch (IllegalArgumentException e) {
                dropped++;
                log.warnf("Skipping malformed row in genderTrend (dropped=%d, monthKey=%s): %s",
                        dropped, acc.monthKey, e.getMessage());
            }
        }
        if (dropped > 0) {
            log.warnf("genderTrend dropped %d malformed rows out of %d",
                    dropped, monthMap.size());
        }

        log.debugf("genderTrend: %d months (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }

    // ============================================================================
    // Executive Dashboard: Headcount by Type (Trailing 24 Months, incl. EXTERNAL)
    // ============================================================================

    /** Mutable accumulator for {@link #headcountByType(Set)}'s server-side type pivot. */
    private static final class HeadcountByTypeAcc {
        final String monthKey;
        final int year;
        final int monthNumber;
        long consultant;
        long student;
        long staff;
        long external;
        HeadcountByTypeAcc(String monthKey, int year, int monthNumber) {
            this.monthKey = monthKey;
            this.year = year;
            this.monthNumber = monthNumber;
        }
    }

    /**
     * Returns the trailing-24-months headcount-by-type curve including
     * {@code EXTERNAL}, mirroring the BFF route at
     * {@code /api/executive/headcount-by-type}.
     *
     * <p>Identical structure to the CXO {@code headcount-growth} curve except
     * the type IN-list adds {@code 'EXTERNAL'}. For each of the 24 months,
     * counts users whose most-recent {@code userstatus} row on or before
     * month-end has {@code status='ACTIVE'} and {@code type IN ('CONSULTANT',
     * 'STUDENT', 'STAFF', 'EXTERNAL')}. The "most-recent" predicate is a
     * NOT EXISTS correlated subquery (matches BFF SQL verbatim). The 24-month
     * series is generated via a {@code UNION} derived table.</p>
     *
     * <p>Pivot is server-side: SQL returns one row per (month, type) and the
     * service folds those into one {@link ExecHeadcountByTypeMonthDTO} per
     * month via {@link LinkedHashMap} (chronological order from SQL ORDER BY).
     * {@code total = consultant + student + staff + external}.
     * {@code monthLabel} is derived from {@code (year, monthNumber)} via
     * {@code Month.getDisplayName(SHORT, ENGLISH)}.</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return chronologically-ordered list of monthly headcount rows (may be empty)
     */
    public List<ExecHeadcountByTypeMonthDTO> headcountByType(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String companyFilter = hasCompanyFilter ? " AND us.companyuuid IN (:companyIds) " : "";

        String sql = "SELECT " +
                "  DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL n MONTH), '%Y%m') AS month_key, " +
                "  YEAR(DATE_SUB(CURDATE(), INTERVAL n MONTH))                AS `year`, " +
                "  MONTH(DATE_SUB(CURDATE(), INTERVAL n MONTH))               AS month_number, " +
                "  us.`type`                                                  AS type, " +
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
                "  AND us.`type` IN ('CONSULTANT', 'STUDENT', 'STAFF', 'EXTERNAL') " +
                "  AND us.status = 'ACTIVE' " +
                companyFilter +
                "WHERE NOT EXISTS ( " +
                "  SELECT 1 FROM userstatus us2 " +
                "  WHERE us2.useruuid = us.useruuid " +
                "    AND us2.statusdate >  us.statusdate " +
                "    AND us2.statusdate <= LAST_DAY(DATE_SUB(CURDATE(), INTERVAL n MONTH)) " +
                ") " +
                "GROUP BY month_key, `year`, month_number, us.`type` " +
                "ORDER BY month_key, us.`type`";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows;
        try {
            rows = query.getResultList();
        } catch (PersistenceException pe) {
            log.errorf(pe, "headcountByType failed (companyFilter=%s)",
                    hasCompanyFilter ? "yes" : "none");
            throw pe;
        }

        Map<String, HeadcountByTypeAcc> monthMap = new LinkedHashMap<>();
        for (Tuple row : rows) {
            String monthKey = row.get("month_key", String.class);
            int year = toInt(row.get("year"));
            int monthNumber = toInt(row.get("month_number"));
            String type = row.get("type", String.class);
            long headcount = toLong(row.get("headcount"));

            HeadcountByTypeAcc acc = monthMap.computeIfAbsent(monthKey,
                    k -> new HeadcountByTypeAcc(monthKey, year, monthNumber));
            switch (type) {
                case "CONSULTANT" -> acc.consultant = headcount;
                case "STUDENT"    -> acc.student    = headcount;
                case "STAFF"      -> acc.staff      = headcount;
                case "EXTERNAL"   -> acc.external   = headcount;
                // Other types ignored (defensive — SQL filter restricts to these four).
            }
        }

        List<ExecHeadcountByTypeMonthDTO> result = new ArrayList<>(monthMap.size());
        int dropped = 0;
        for (HeadcountByTypeAcc acc : monthMap.values()) {
            try {
                String monthLabel = Month.of(acc.monthNumber)
                        .getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + acc.year;
                long total = acc.consultant + acc.student + acc.staff + acc.external;
                result.add(new ExecHeadcountByTypeMonthDTO(
                        acc.monthKey, monthLabel, acc.year, acc.monthNumber,
                        acc.consultant, acc.student, acc.staff, acc.external, total));
            } catch (IllegalArgumentException e) {
                dropped++;
                log.warnf("Skipping malformed row in headcountByType (dropped=%d, monthKey=%s): %s",
                        dropped, acc.monthKey, e.getMessage());
            }
        }
        if (dropped > 0) {
            log.warnf("headcountByType dropped %d malformed rows out of %d",
                    dropped, monthMap.size());
        }

        log.debugf("headcountByType: %d months (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }

    // ============================================================================
    // Executive Dashboard: Retention Cohort Survival Curves
    // ============================================================================

    /** First cohort year (inclusive). Mirrors {@code COHORT_START_YEAR} in BFF. */
    private static final int RETENTION_COHORT_START_YEAR = 2019;
    /** Last cohort year (inclusive). Mirrors {@code COHORT_END_YEAR} in BFF. */
    private static final int RETENTION_COHORT_END_YEAR = 2025;
    /** Fixed survival-curve sample points (months since hire). Mirrors {@code TIME_POINTS} in BFF. */
    private static final List<Integer> RETENTION_TIME_POINTS =
            List.of(0, 6, 12, 18, 24, 36, 48, 60, 72);

    /** Per-employee lifecycle row used by {@link #retentionCohorts(Set)}. */
    private static final class EmployeeLifecycle {
        final LocalDate hireDate;
        final LocalDate terminationDate; // null if still active
        EmployeeLifecycle(LocalDate hireDate, LocalDate terminationDate) {
            this.hireDate = hireDate;
            this.terminationDate = terminationDate;
        }
    }

    /**
     * Returns hire-year cohort survival curves for cohorts {@value #RETENTION_COHORT_START_YEAR}
     * through {@value #RETENTION_COHORT_END_YEAR}, mirroring the BFF route at
     * {@code /api/executive/retention-cohorts}.
     *
     * <p><b>Per-employee SQL.</b> The native query returns one row per employee
     * with {@code hire_date = MIN(statusdate WHERE status='ACTIVE' AND type IN
     * (CONSULTANT, STUDENT, STAFF))} and a correlated subquery for
     * {@code termination_date = MAX(statusdate WHERE status='TERMINATED' AND
     * statusdate > hire_date)} (NULL if the employee never terminated).
     * The HAVING clause restricts to {@code YEAR(hire_date) BETWEEN ? AND ?}.</p>
     *
     * <p><b>Java-side aggregation (nested DTO fold).</b> Per-employee rows are
     * grouped by {@code YEAR(hireDate)} into a {@link LinkedHashMap} keyed by
     * cohort year, preserving insertion order. The result list is built in
     * ascending cohort year, with all 9 fixed time points present per cohort
     * regardless of data — matches BFF semantics.</p>
     *
     * <p><b>Survival computation.</b> For each cohort year:
     * <ul>
     *   <li>Cohort reference point = Jan 1 of cohort year.</li>
     *   <li>{@code monthsSinceCohortStart} = months from Jan 1 to today (UTC).</li>
     *   <li>If a time point exceeds {@code monthsSinceCohortStart}, the point
     *       is right-censored ({@code survivalPct = null}).</li>
     *   <li>Otherwise count employees who survived at least N months: still-active
     *       employees count as surviving every observed time point; terminated
     *       employees count if {@code (terminationDate - hireDate) in months ≥ N}.</li>
     *   <li>{@code survivalPct = round((survived / cohortSize) * 10000) / 100}.</li>
     * </ul>
     * Empty cohorts ({@code cohortSize = 0}) emit all 9 points with
     * {@code survivalPct = null}.</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return list of 7 cohort DTOs in ascending year order (always returned, even when empty)
     */
    public List<ExecRetentionCohortDTO> retentionCohorts(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        // BFF aliases the filter on `us_hire.companyuuid` — match that here.
        String companyFilter = hasCompanyFilter ? " AND us_hire.companyuuid IN (:companyIds) " : "";

        String sql = "SELECT " +
                "  us_hire.useruuid                AS useruuid, " +
                "  MIN(us_hire.statusdate)         AS hire_date, " +
                "  ( " +
                "    SELECT MAX(us_term.statusdate) " +
                "    FROM userstatus us_term " +
                "    WHERE us_term.useruuid = us_hire.useruuid " +
                "      AND us_term.status = 'TERMINATED' " +
                "      AND us_term.statusdate > ( " +
                "        SELECT MIN(us_h2.statusdate) " +
                "        FROM userstatus us_h2 " +
                "        WHERE us_h2.useruuid = us_hire.useruuid " +
                "          AND us_h2.status = 'ACTIVE' " +
                "          AND us_h2.`type` IN ('CONSULTANT', 'STUDENT', 'STAFF') " +
                "      ) " +
                "  )                               AS termination_date " +
                "FROM userstatus us_hire " +
                "WHERE us_hire.status = 'ACTIVE' " +
                "  AND us_hire.`type` IN ('CONSULTANT', 'STUDENT', 'STAFF') " +
                companyFilter +
                "GROUP BY us_hire.useruuid " +
                "HAVING YEAR(MIN(us_hire.statusdate)) BETWEEN :cohortStart AND :cohortEnd";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }
        query.setParameter("cohortStart", RETENTION_COHORT_START_YEAR);
        query.setParameter("cohortEnd", RETENTION_COHORT_END_YEAR);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows;
        try {
            rows = query.getResultList();
        } catch (PersistenceException pe) {
            log.errorf(pe, "retentionCohorts failed (companyFilter=%s)",
                    hasCompanyFilter ? "yes" : "none");
            throw pe;
        }

        // Cohort fold: group by YEAR(hireDate). LinkedHashMap so we can iterate
        // in deterministic order (though we always emit all years 2019..2025
        // explicitly below, present or not).
        Map<Integer, List<EmployeeLifecycle>> byCohort = new LinkedHashMap<>();
        for (Tuple row : rows) {
            LocalDate hire = toLocalDate(row.get("hire_date"));
            if (hire == null) continue;
            int cohortYear = hire.getYear();
            if (cohortYear < RETENTION_COHORT_START_YEAR || cohortYear > RETENTION_COHORT_END_YEAR) {
                continue;
            }
            LocalDate term = toLocalDate(row.get("termination_date"));
            byCohort.computeIfAbsent(cohortYear, k -> new ArrayList<>())
                    .add(new EmployeeLifecycle(hire, term));
        }

        LocalDate now = LocalDate.now();
        List<ExecRetentionCohortDTO> result = new ArrayList<>(
                RETENTION_COHORT_END_YEAR - RETENTION_COHORT_START_YEAR + 1);
        int totalDroppedPoints = 0;
        for (int year = RETENTION_COHORT_START_YEAR; year <= RETENTION_COHORT_END_YEAR; year++) {
            List<EmployeeLifecycle> employees = byCohort.getOrDefault(year, List.of());
            long cohortSize = employees.size();

            if (cohortSize == 0) {
                // Inner-list ExecRetentionCohortPointDTO construction is per-row
                // in spirit; wrap each point so a single corrupt point can't
                // take down the whole cohort. Outer ExecRetentionCohortDTO is
                // fail-fast (the request is unrecoverable if it throws).
                List<ExecRetentionCohortPointDTO> emptyPoints = new ArrayList<>(RETENTION_TIME_POINTS.size());
                for (int tp : RETENTION_TIME_POINTS) {
                    try {
                        emptyPoints.add(new ExecRetentionCohortPointDTO(tp, null));
                    } catch (IllegalArgumentException e) {
                        totalDroppedPoints++;
                        log.warnf("Skipping malformed point in retentionCohorts (year=%d, tp=%d): %s",
                                year, tp, e.getMessage());
                    }
                }
                result.add(new ExecRetentionCohortDTO(year, 0L, emptyPoints));
                continue;
            }

            LocalDate cohortStart = LocalDate.of(year, 1, 1);
            long monthsSinceCohortStart = ChronoUnit.MONTHS.between(cohortStart, now);

            List<ExecRetentionCohortPointDTO> points = new ArrayList<>(RETENTION_TIME_POINTS.size());
            for (int tp : RETENTION_TIME_POINTS) {
                if (tp > monthsSinceCohortStart) {
                    // Right-censored: time point beyond observation window
                    try {
                        points.add(new ExecRetentionCohortPointDTO(tp, null));
                    } catch (IllegalArgumentException e) {
                        totalDroppedPoints++;
                        log.warnf("Skipping malformed point in retentionCohorts (year=%d, tp=%d): %s",
                                year, tp, e.getMessage());
                    }
                    continue;
                }
                long survived = 0;
                for (EmployeeLifecycle emp : employees) {
                    if (emp.terminationDate == null) {
                        // Still active — survived all observed time points
                        survived++;
                    } else {
                        long tenureMonths = ChronoUnit.MONTHS.between(emp.hireDate, emp.terminationDate);
                        if (tenureMonths >= tp) {
                            survived++;
                        }
                    }
                }
                double survivalPct = Math.round((double) survived / cohortSize * 10000.0) / 100.0;
                try {
                    points.add(new ExecRetentionCohortPointDTO(tp, survivalPct));
                } catch (IllegalArgumentException e) {
                    totalDroppedPoints++;
                    log.warnf("Skipping malformed point in retentionCohorts (year=%d, tp=%d): %s",
                            year, tp, e.getMessage());
                }
            }

            result.add(new ExecRetentionCohortDTO(year, cohortSize, points));
        }
        if (totalDroppedPoints > 0) {
            log.warnf("retentionCohorts dropped %d malformed points across all cohorts",
                    totalDroppedPoints);
        }

        log.debugf("retentionCohorts: %d cohorts (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }

    /**
     * Coerces a {@code java.sql.Date} or {@code java.time.LocalDate} value
     * (returned by JDBC for DATE columns) to a {@link LocalDate}. Returns
     * {@code null} for null input.
     */
    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof Date sqlDate) return sqlDate.toLocalDate();
        // Fall back to ISO parse for unexpected types (e.g. driver returning String)
        return LocalDate.parse(v.toString());
    }

    // ============================================================================
    // Executive Dashboard: Career Level Distribution (Current Snapshot)
    // ============================================================================

    /**
     * Career level → track entry. Mirrors {@code CAREER_LEVEL_TRACK_MAP} in the
     * BFF route 1:1 (same levels, same track strings, same canonical order).
     */
    private record CareerLevelTrack(String careerLevel, String careerTrack) {}

    /**
     * Hardcoded canonical career-level → track mapping in display order.
     * Mirrors the BFF route's {@code CAREER_LEVEL_TRACK_MAP} verbatim — same
     * 18 levels, same six track names, same ordering. The endpoint always
     * emits all 18 levels regardless of data.
     */
    private static final List<CareerLevelTrack> CAREER_LEVEL_TRACK_MAP = List.of(
            new CareerLevelTrack("JUNIOR_CONSULTANT",         "Entry"),
            new CareerLevelTrack("CONSULTANT",                "Delivery"),
            new CareerLevelTrack("PROFESSIONAL_CONSULTANT",   "Delivery"),
            new CareerLevelTrack("SENIOR_CONSULTANT",         "Delivery"),
            new CareerLevelTrack("LEAD_CONSULTANT",           "Advisory"),
            new CareerLevelTrack("MANAGING_CONSULTANT",       "Advisory"),
            new CareerLevelTrack("PRINCIPAL_CONSULTANT",      "Advisory"),
            new CareerLevelTrack("MANAGER",                   "Leadership"),
            new CareerLevelTrack("SENIOR_MANAGER",            "Leadership"),
            new CareerLevelTrack("ASSOCIATE_PARTNER",         "Leadership"),
            new CareerLevelTrack("ENGAGEMENT_MANAGER",        "Client Engagement"),
            new CareerLevelTrack("SENIOR_ENGAGEMENT_MANAGER", "Client Engagement"),
            new CareerLevelTrack("ENGAGEMENT_DIRECTOR",       "Client Engagement"),
            new CareerLevelTrack("PARTNER",                   "Partner / Director"),
            new CareerLevelTrack("THOUGHT_LEADER_PARTNER",    "Partner / Director"),
            new CareerLevelTrack("PRACTICE_LEADER",           "Partner / Director"),
            new CareerLevelTrack("MANAGING_DIRECTOR",         "Partner / Director"),
            new CareerLevelTrack("MANAGING_PARTNER",          "Partner / Director")
    );

    /**
     * Returns the current-snapshot career-level distribution for active
     * consultants, mirroring the BFF route at
     * {@code /api/executive/career-level-distribution}.
     *
     * <p>Source: {@code user_career_level} joined to {@code userstatus} where
     * {@code us.type='CONSULTANT', us.status='ACTIVE'}, with two correlated
     * subqueries: one selects each user's most-recent {@code user_career_level}
     * row ({@code MAX(active_from)}); the other ensures {@code userstatus} is
     * also the most recent for that user via NOT EXISTS.</p>
     *
     * <p>The endpoint always emits all 18 canonical career levels with
     * {@code count = 0} for levels with no active consultants — matches BFF.
     * Career levels that exist in {@code user_career_level} but are not in
     * the canonical {@link #CAREER_LEVEL_TRACK_MAP} are silently dropped from
     * the response (defensive — the BFF behaves identically).</p>
     *
     * @param companyIds optional set of company UUIDs; {@code null}/empty means no filter
     * @return list of all 18 canonical levels in display order
     */
    public List<ExecCareerLevelDistDTO> careerLevelDistribution(Set<String> companyIds) {
        boolean hasCompanyFilter = companyIds != null && !companyIds.isEmpty();
        String companyFilter = hasCompanyFilter ? " AND us.companyuuid IN (:companyIds) " : "";

        String sql = "SELECT " +
                "  ucl.career_level             AS career_level, " +
                "  COUNT(DISTINCT ucl.useruuid) AS consultant_count " +
                "FROM user_career_level ucl " +
                "JOIN userstatus us ON us.useruuid = ucl.useruuid " +
                "  AND us.`type` = 'CONSULTANT' " +
                "  AND us.status = 'ACTIVE' " +
                companyFilter +
                "WHERE ucl.active_from = ( " +
                "  SELECT MAX(ucl2.active_from) " +
                "  FROM user_career_level ucl2 " +
                "  WHERE ucl2.useruuid = ucl.useruuid " +
                ") " +
                "AND NOT EXISTS ( " +
                "  SELECT 1 FROM userstatus us2 " +
                "  WHERE us2.useruuid = us.useruuid " +
                "    AND us2.statusdate > us.statusdate " +
                ") " +
                "GROUP BY ucl.career_level " +
                "ORDER BY ucl.career_level";

        Query query = em.createNativeQuery(sql, Tuple.class);
        if (hasCompanyFilter) {
            query.setParameter("companyIds", companyIds);
        }
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);

        @SuppressWarnings("unchecked")
        List<Tuple> rows;
        try {
            rows = query.getResultList();
        } catch (PersistenceException pe) {
            log.errorf(pe, "careerLevelDistribution failed (companyFilter=%s)",
                    hasCompanyFilter ? "yes" : "none");
            throw pe;
        }

        // Build a lookup of actual counts. HashMap is fine here since we then
        // iterate the canonical CAREER_LEVEL_TRACK_MAP (which dictates the
        // response order); no need for LinkedHashMap.
        Map<String, Long> countByLevel = new HashMap<>(rows.size());
        for (Tuple row : rows) {
            String careerLevel = row.get("career_level", String.class);
            long count = toLong(row.get("consultant_count"));
            countByLevel.put(careerLevel, count);
        }

        // Always emit all 18 canonical levels in display order, even with zero count.
        List<ExecCareerLevelDistDTO> result = new ArrayList<>(CAREER_LEVEL_TRACK_MAP.size());
        int dropped = 0;
        for (CareerLevelTrack entry : CAREER_LEVEL_TRACK_MAP) {
            long count = countByLevel.getOrDefault(entry.careerLevel(), 0L);
            try {
                result.add(new ExecCareerLevelDistDTO(
                        entry.careerLevel(), entry.careerTrack(), count));
            } catch (IllegalArgumentException e) {
                dropped++;
                log.warnf("Skipping malformed row in careerLevelDistribution (dropped=%d, level=%s): %s",
                        dropped, entry.careerLevel(), e.getMessage());
            }
        }
        if (dropped > 0) {
            log.warnf("careerLevelDistribution dropped %d malformed levels out of %d",
                    dropped, CAREER_LEVEL_TRACK_MAP.size());
        }

        log.debugf("careerLevelDistribution: %d levels (companyFilter=%s)",
                Integer.valueOf(result.size()), Boolean.toString(hasCompanyFilter));
        return result;
    }
}
