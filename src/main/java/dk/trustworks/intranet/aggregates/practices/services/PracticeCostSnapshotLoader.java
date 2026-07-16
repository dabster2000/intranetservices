package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.ServiceUnavailableException;
import lombok.extern.jbosslog.JBossLog;

import java.sql.Timestamp;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntSupplier;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.CXO_QUERY_TIMEOUT_MS;

/** Package-internal database loader owned by {@link PracticeCostSnapshotProvider}. */
@JBossLog
@ApplicationScoped
class PracticeCostSnapshotLoader {

    private static final MathContext DIVISION_CONTEXT = MathContext.DECIMAL128;

    static final List<String> PRACTICES = List.of("PM", "BA", "CYB", "DEV", "SA");
    private static final Set<String> PRACTICE_SET = new LinkedHashSet<>(PRACTICES);
    static final List<String> PRODUCTION_COMPANIES = List.of(
            "d8894494-2fb4-4f72-9e05-e6032e6dd691",
            "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3",
            "e4b0a2a4-0963-4153-b0a2-a409637153a2");
    private static final Set<String> PRODUCTION_COMPANY_SET = new LinkedHashSet<>(PRODUCTION_COMPANIES);
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int PERIOD_MONTHS = 12;
    private static final int ANCHOR_SEARCH_MONTHS = 36;
    private static final int METADATA_QUERY_MONTHS = ANCHOR_SEARCH_MONTHS + (2 * PERIOD_MONTHS) - 1;
    static final String SALARY_COMPLETENESS_RULE_VERSION = "PRACTICE_SALARY_V1";
    static final String PUBLICATION_SNAPSHOT_SQL = """
            SELECT
                p.refresh_state,
                p.active_refresh_token,
                p.generation_at,
                p.published_at,
                p.opex_row_count,
                p.fte_row_count,
                p.completeness_row_count,
                o.total_rows,
                o.timestamped_rows,
                o.min_materialized_at,
                o.max_materialized_at,
                f.total_rows,
                f.timestamped_rows,
                f.min_materialized_at,
                f.max_materialized_at,
                c.total_rows,
                c.timestamped_rows,
                c.min_refreshed_at,
                c.max_refreshed_at
            FROM practice_operating_cost_publication p
            CROSS JOIN (
                SELECT COUNT(*) AS total_rows,
                       COUNT(materialized_at) AS timestamped_rows,
                       MIN(materialized_at) AS min_materialized_at,
                       MAX(materialized_at) AS max_materialized_at
                FROM fact_opex_mat
            ) o
            CROSS JOIN (
                SELECT COUNT(*) AS total_rows,
                       COUNT(materialized_at) AS timestamped_rows,
                       MIN(materialized_at) AS min_materialized_at,
                       MAX(materialized_at) AS max_materialized_at
                FROM fact_employee_monthly_mat
            ) f
            CROSS JOIN (
                SELECT COUNT(*) AS total_rows,
                       COUNT(refreshed_at) AS timestamped_rows,
                       MIN(refreshed_at) AS min_refreshed_at,
                       MAX(refreshed_at) AS max_refreshed_at
                FROM fact_practice_salary_completeness_mat
            ) c
            WHERE p.publication_id = 1
            """;
    static final String COST_ROWS_SQL = """
            SELECT company_id, practice_id, month_key, cost_type, SUM(opex_amount_dkk) AS amount_dkk
            FROM fact_opex_mat
            WHERE month_key >= :fromKey AND month_key <= :toKey
              AND company_id IN (:companies)
              AND practice_id IN (:practices)
              AND cost_type IN ('SALARIES', 'OPEX')
              AND posting_status IN (:postingStatuses)
            GROUP BY company_id, practice_id, month_key, cost_type
            ORDER BY company_id, practice_id, month_key, cost_type
            """;
    static final String SALARY_COMPLETENESS_ROWS_SQL = """
            SELECT company_id, month_key,
                   expected_salary_cell_count, actual_salary_cell_count,
                   covered_salary_cell_count, missing_salary_cell_count,
                   unexpected_salary_cell_count, complete, 0
            FROM fact_practice_salary_completeness_mat
            WHERE cost_source = :costSource
              AND rule_version = :ruleVersion
              AND month_key >= :fromKey AND month_key <= :toKey
            ORDER BY month_key, company_id
            """;

    @Inject
    EntityManager em;

    PracticeCostSnapshotProvider.CanonicalSnapshot readPublishedSnapshot(CostSource costSource) {
        return readPublishedSnapshot(costSource, CXO_QUERY_TIMEOUT_MS);
    }

    PracticeCostSnapshotProvider.CanonicalSnapshot readPublishedSnapshot(
            CostSource costSource, int queryTimeoutMs) {
        return readPublishedSnapshot(costSource, fixedQueryTimeout(queryTimeoutMs));
    }

    PracticeCostSnapshotProvider.CanonicalSnapshot readPublishedSnapshot(
            CostSource costSource, IntSupplier queryTimeoutMs) {
        return readSnapshot(costSource, null, null, null, null, queryTimeoutMs);
    }

    PracticeCostSnapshotProvider.CanonicalSnapshot readPublishedCanonicalSnapshot(
            CostSource costSource, String basisGenerationId, Instant generationAt,
            LocalDate coverageStart, PracticeCostSnapshotProvider.CanonicalWindow certifiedWindow) {
        return readPublishedCanonicalSnapshot(costSource, basisGenerationId, generationAt,
                coverageStart, certifiedWindow, CXO_QUERY_TIMEOUT_MS);
    }

    PracticeCostSnapshotProvider.CanonicalSnapshot readPublishedCanonicalSnapshot(
            CostSource costSource, String basisGenerationId, Instant generationAt,
            LocalDate coverageStart, PracticeCostSnapshotProvider.CanonicalWindow certifiedWindow,
            int queryTimeoutMs) {
        return readPublishedCanonicalSnapshot(costSource, basisGenerationId, generationAt,
                coverageStart, certifiedWindow, fixedQueryTimeout(queryTimeoutMs));
    }

    PracticeCostSnapshotProvider.CanonicalSnapshot readPublishedCanonicalSnapshot(
            CostSource costSource, String basisGenerationId, Instant generationAt,
            LocalDate coverageStart, PracticeCostSnapshotProvider.CanonicalWindow certifiedWindow,
            IntSupplier queryTimeoutMs) {
        if (basisGenerationId == null || generationAt == null) throw unavailable();
        return readSnapshot(costSource, basisGenerationId, generationAt, coverageStart,
                certifiedWindow, queryTimeoutMs);
    }

    PracticeCostSnapshotProvider.CanonicalSnapshot readCandidateCanonicalSnapshot(
            CostSource costSource, String basisGenerationId, Instant generationAt,
            LocalDate coverageStart) {
        if (basisGenerationId == null || generationAt == null) throw unavailable();
        return readSnapshot(costSource, basisGenerationId, generationAt, coverageStart,
                null, fixedQueryTimeout(CXO_QUERY_TIMEOUT_MS));
    }

    private PracticeCostSnapshotProvider.CanonicalSnapshot readSnapshot(
            CostSource costSource, String basisGenerationId, Instant canonicalGenerationAt,
            LocalDate coverageStart, PracticeCostSnapshotProvider.CanonicalWindow certifiedWindow,
            IntSupplier queryTimeoutMs) {
        PublicationSnapshot before = basisGenerationId == null
                ? loadPublishedSnapshot(queryTimeoutMs) : null;
        Set<String> postingStatuses = new LinkedHashSet<>(costSource.postingStatusNames());

        if (certifiedWindow != null && !certifiedWindow.available()) {
            return PracticeCostSnapshotProvider.unavailableCanonical(costSource, certifiedWindow.reason());
        }

        LocalDate copenhagenToday = LocalDate.now(UtilizationCalculationHelper.REPORTING_ZONE);
        YearMonth latestCompletedMonth = YearMonth.from(copenhagenToday).minusMonths(1);
        YearMonth metadataEndMonth = certifiedWindow == null
                ? latestCompletedMonth : YearMonth.from(certifiedWindow.currentEnd());
        YearMonth earliestMetadataMonth = certifiedWindow == null
                ? metadataStartMonth(latestCompletedMonth) : YearMonth.from(certifiedWindow.priorStart());
        List<SalaryCompletenessCell> salaryCompletenessCells;
        try {
            salaryCompletenessCells = basisGenerationId == null
                    ? loadSalaryCompletenessRows(
                            costSource, monthKey(earliestMetadataMonth), monthKey(metadataEndMonth),
                            queryTimeoutMs)
                    : loadCanonicalSalaryCompletenessRows(basisGenerationId, costSource,
                            monthKey(earliestMetadataMonth), monthKey(metadataEndMonth), queryTimeoutMs);
        } catch (RuntimeException e) {
            if (e instanceof ContributionUnavailableException contributionUnavailable) {
                throw contributionUnavailable;
            }
            log.errorf(e, "practice operating-cost completeness metadata query failed: source=%s", costSource);
            throw new ServiceUnavailableException(
                    "Operating-cost completeness metadata is unavailable; values are withheld.",
                    (Long) null, e);
        }
        Optional<YearMonth> reportingThroughResult = certifiedWindow == null
                ? latestCompleteAnchor(salaryCompletenessCells, latestCompletedMonth)
                : Optional.of(YearMonth.from(certifiedWindow.anchor()));
        if (reportingThroughResult.isEmpty()) {
            return PracticeCostSnapshotProvider.unavailableCanonical(
                    costSource, "SELECTED_COST_SOURCE_NO_COMPLETE_WINDOW");
        }
        YearMonth reportingThrough = reportingThroughResult.get();

        OperatingWindow window = reportingWindow(reportingThrough);
        if (certifiedWindow != null && !windowMatches(window, certifiedWindow)) throw unavailable();
        String anchorKey = window.reportingThroughMonthKey();
        String currentStartKey = window.currentStartMonthKey();
        String currentEndKey = window.currentEndMonthKey();
        String priorStartKey = window.priorStartMonthKey();
        String priorEndKey = window.priorEndMonthKey();

        List<Object[]> costRows = basisGenerationId == null
                ? loadCostRows(priorStartKey, currentEndKey, postingStatuses, queryTimeoutMs)
                : loadCanonicalCostRows(basisGenerationId, priorStartKey, currentEndKey,
                        postingStatuses, queryTimeoutMs);
        List<Object[]> fteRows = basisGenerationId == null
                ? loadFteRows(priorStartKey, currentEndKey, queryTimeoutMs)
                : loadCanonicalFteRows(basisGenerationId, priorStartKey, currentEndKey,
                        queryTimeoutMs);

        Map<String, PracticeAccumulator> byPractice = new LinkedHashMap<>();
        PRACTICES.forEach(p -> byPractice.put(p, new PracticeAccumulator()));

        Set<String> currentSalaryMonths = new HashSet<>();
        Set<String> currentOpexMonths = new HashSet<>();
        Set<String> priorSalaryMonths = new HashSet<>();
        Set<String> priorOpexMonths = new HashSet<>();

        for (Object[] row : costRows) {
            String practice = String.valueOf(row[1]);
            String key = String.valueOf(row[2]);
            String costType = String.valueOf(row[3]);
            BigDecimal amount = decimal(row[4]);
            PracticeAccumulator acc = byPractice.get(practice);
            if (acc == null) continue;

            boolean current = key.compareTo(currentStartKey) >= 0;
            if ("SALARIES".equals(costType)) {
                if (current) {
                    acc.currentSalary = acc.currentSalary.add(amount);
                    currentSalaryMonths.add(key);
                } else {
                    acc.priorSalary = acc.priorSalary.add(amount);
                    priorSalaryMonths.add(key);
                }
            } else if ("OPEX".equals(costType)) {
                if (current) {
                    acc.currentOpex = acc.currentOpex.add(amount);
                    currentOpexMonths.add(key);
                } else {
                    acc.priorOpex = acc.priorOpex.add(amount);
                    priorOpexMonths.add(key);
                }
            }
        }

        Set<String> currentFteMonths = new HashSet<>();
        Set<String> priorFteMonths = new HashSet<>();
        Set<String> currentFteCells = new HashSet<>();
        Set<String> priorFteCells = new HashSet<>();
        for (Object[] row : fteRows) {
            String practice = String.valueOf(row[1]);
            String key = String.valueOf(row[2]);
            BigDecimal monthlyFte = decimal(row[3]);
            PracticeAccumulator acc = byPractice.get(practice);
            if (acc == null) continue;
            String cell = practiceMonthCell(practice, key);
            if (key.compareTo(currentStartKey) >= 0) {
                acc.currentFteSum = acc.currentFteSum.add(monthlyFte);
                currentFteMonths.add(key);
                currentFteCells.add(cell);
            } else {
                acc.priorFteSum = acc.priorFteSum.add(monthlyFte);
                priorFteMonths.add(key);
                priorFteCells.add(cell);
            }
        }

        // OPEX can legitimately be zero for a practice-month and therefore have no row.
        // Salary readiness is evaluated from versioned company/month/source metadata.
        // FTE remains an independent five-practice x twelve-month population check.
        PeriodCostCompleteness currentCostCompleteness = summarizeCostCompleteness(
                salaryCompletenessCells, currentStartKey, currentEndKey);
        PeriodCostCompleteness priorCostCompleteness = summarizeCostCompleteness(
                salaryCompletenessCells, priorStartKey, priorEndKey);
        CoverageResult currentSalaryCoverage = currentCostCompleteness.salaryCoverage();
        CoverageResult priorSalaryCoverage = priorCostCompleteness.salaryCoverage();

        CoverageResult currentFteCoverage = coverage(
                expectedPracticeMonthCells(currentStartKey, currentEndKey), currentFteCells);
        CoverageResult priorFteCoverage = coverage(
                expectedPracticeMonthCells(priorStartKey, priorEndKey), priorFteCells);
        List<PracticeCostSnapshotProvider.CanonicalPractice> practices = new ArrayList<>(PRACTICES.size());
        for (String practice : PRACTICES) {
            CoverageResult currentPracticeFte = coverage(
                    expectedPracticeMonthCells(practice, currentStartKey, currentEndKey), currentFteCells);
            CoverageResult priorPracticeFte = coverage(
                    expectedPracticeMonthCells(practice, priorStartKey, priorEndKey), priorFteCells);
            practices.add(toCanonical(practice, byPractice.get(practice), currentPracticeFte, priorPracticeFte));
        }
        boolean currentCostComplete = currentCostCompleteness.complete();
        boolean currentFteComplete = currentFteCoverage.complete();
        boolean priorCostComplete = priorCostCompleteness.complete();
        boolean priorFteComplete = priorFteCoverage.complete();
        String currentCompletenessStatus = completenessStatus(currentCostComplete, currentFteComplete);
        String priorCompletenessStatus = completenessStatus(priorCostComplete, priorFteComplete);
        String completenessStatus = completenessStatus(
                currentCostComplete && priorCostComplete,
                currentFteComplete && priorFteComplete);
        boolean complete = currentCostComplete && currentFteComplete
                && priorCostComplete && priorFteComplete;

        log.debugf("practice operating cost: source=%s through=%s status=%s",
                costSource, anchorKey, completenessStatus);
        PracticeCostSnapshotProvider.CanonicalSnapshot response = new PracticeCostSnapshotProvider.CanonicalSnapshot(
                costSource.name(),
                true,
                null,
                anchorKey,
                currentStartKey,
                currentEndKey,
                priorStartKey,
                priorEndKey,
                basisGenerationId == null ? before.generationAt() : canonicalGenerationAt,
                currentSalaryMonths.size(),
                currentOpexMonths.size(),
                currentFteMonths.size(),
                priorSalaryMonths.size(),
                priorOpexMonths.size(),
                priorFteMonths.size(),
                currentSalaryCoverage.expectedCount(),
                currentSalaryCoverage.actualCount(),
                currentSalaryCoverage.coveredCount(),
                currentSalaryCoverage.missingCount(),
                currentSalaryCoverage.unexpectedCount(),
                priorSalaryCoverage.expectedCount(),
                priorSalaryCoverage.actualCount(),
                priorSalaryCoverage.coveredCount(),
                priorSalaryCoverage.missingCount(),
                priorSalaryCoverage.unexpectedCount(),
                currentFteCoverage.expectedCount(),
                currentFteCoverage.coveredCount(),
                currentFteCoverage.missingCount(),
                priorFteCoverage.expectedCount(),
                priorFteCoverage.coveredCount(),
                priorFteCoverage.missingCount(),
                currentCostComplete,
                currentFteComplete,
                currentCompletenessStatus,
                priorCostComplete,
                priorFteComplete,
                priorCompletenessStatus,
                completenessStatus,
                complete,
                basisGenerationId == null ? "CURRENT_PRACTICE_AT_MATERIALIZATION" : "EFFECTIVE_DATED_PRACTICE",
                coverageStart,
                basisGenerationId == null
                        ? "fact_opex_mat is distributed using current practice at materialization; "
                            + "effective-dated practice snapshots are not applied to this historical fact."
                            : "Salary, OPEX and FTE are allocated from the publication's immutable effective-practice basis.",
                currentCostCompleteness.costMonthEndFallbackEmployeeMonthCount(),
                priorCostCompleteness.costMonthEndFallbackEmployeeMonthCount(),
                practices
        );

        if (basisGenerationId == null) {
            PublicationSnapshot after = loadPublishedSnapshot(queryTimeoutMs);
            if (!samePublication(before, after)) {
                log.warnf("practice operating-cost publication changed during read: source=%s", costSource);
                throw unavailable();
            }
        }
        return response;
    }

    static OperatingWindow reportingWindow(YearMonth currentEnd) {
        YearMonth currentStart = currentEnd.minusMonths(PERIOD_MONTHS - 1L);
        YearMonth priorEnd = currentStart.minusMonths(1);
        YearMonth priorStart = priorEnd.minusMonths(PERIOD_MONTHS - 1L);
        return new OperatingWindow(
                monthKey(currentEnd),
                monthKey(currentStart),
                monthKey(currentEnd),
                monthKey(priorStart),
                monthKey(priorEnd));
    }

    private static boolean windowMatches(
            OperatingWindow window, PracticeCostSnapshotProvider.CanonicalWindow certified) {
        return window.reportingThroughMonthKey().equals(monthKey(YearMonth.from(certified.anchor())))
                && window.currentStartMonthKey().equals(monthKey(YearMonth.from(certified.currentStart())))
                && window.currentEndMonthKey().equals(monthKey(YearMonth.from(certified.currentEnd())))
                && window.priorStartMonthKey().equals(monthKey(YearMonth.from(certified.priorStart())))
                && window.priorEndMonthKey().equals(monthKey(YearMonth.from(certified.priorEnd())));
    }

    static YearMonth metadataStartMonth(YearMonth latestCompletedMonth) {
        return latestCompletedMonth.minusMonths(METADATA_QUERY_MONTHS - 1L);
    }

    private List<Object[]> loadCostRows(
            String fromKey, String toKey, Set<String> postingStatuses, IntSupplier queryTimeoutMs) {
        Query query = em.createNativeQuery(COST_ROWS_SQL);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setParameter("companies", PRODUCTION_COMPANY_SET);
        query.setParameter("practices", PRACTICE_SET);
        query.setParameter("postingStatuses", postingStatuses);
        query.setHint("jakarta.persistence.query.timeout", remainingQueryTimeout(queryTimeoutMs));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        restoreLegacyNullAggregateAsZero(rows, 4);
        return rows;
    }

    private List<Object[]> loadCanonicalCostRows(
            String generationId, String fromKey, String toKey, Set<String> postingStatuses,
            IntSupplier queryTimeoutMs) {
        Query query = em.createNativeQuery("""
                SELECT company_id, practice_code, month_key, cost_type, SUM(allocated_amount_dkk)
                FROM fact_practice_cost_generation_mat
                WHERE generation_id=:generation AND month_key BETWEEN :fromKey AND :toKey
                  AND company_id IN (:companies) AND practice_code IN (:practices)
                  AND cost_type IN ('SALARIES','OPEX') AND posting_status IN (:postingStatuses)
                GROUP BY company_id, practice_code, month_key, cost_type
                ORDER BY company_id, practice_code, month_key, cost_type
                """);
        query.setParameter("generation", generationId);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setParameter("companies", PRODUCTION_COMPANY_SET);
        query.setParameter("practices", PRACTICE_SET);
        query.setParameter("postingStatuses", postingStatuses);
        query.setHint("jakarta.persistence.query.timeout", remainingQueryTimeout(queryTimeoutMs));
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        return rows;
    }

    private List<SalaryCompletenessCell> loadSalaryCompletenessRows(
            CostSource costSource, String fromKey, String toKey, IntSupplier queryTimeoutMs) {
        Query query = em.createNativeQuery(SALARY_COMPLETENESS_ROWS_SQL);
        query.setParameter("costSource", costSource.name());
        query.setParameter("ruleVersion", SALARY_COMPLETENESS_RULE_VERSION);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setHint("jakarta.persistence.query.timeout", remainingQueryTimeout(queryTimeoutMs));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<SalaryCompletenessCell> cells = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            cells.add(new SalaryCompletenessCell(
                    String.valueOf(row[0]),
                    String.valueOf(row[1]),
                    toInt(row[2]),
                    toInt(row[3]),
                    toInt(row[4]),
                    toInt(row[5]),
                            toInt(row[6]),
                            toBoolean(row[7]), toInt(row[8])));
        }
        return cells;
    }

    private List<SalaryCompletenessCell> loadCanonicalSalaryCompletenessRows(
            String generationId, CostSource costSource, String fromKey, String toKey,
            IntSupplier queryTimeoutMs) {
        Query query = em.createNativeQuery("""
                SELECT company_id, month_key, expected_salary_cell_count, actual_salary_cell_count,
                       covered_salary_cell_count, missing_salary_cell_count,
                       unexpected_salary_cell_count, complete,
                       cost_month_end_practice_fallback_employee_month_count
                FROM fact_practice_cost_completeness_generation_mat
                WHERE generation_id=:generation AND cost_source=:costSource
                  AND month_key BETWEEN :fromKey AND :toKey
                ORDER BY month_key, company_id
                """);
        query.setParameter("generation", generationId);
        query.setParameter("costSource", costSource.name());
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setHint("jakarta.persistence.query.timeout", remainingQueryTimeout(queryTimeoutMs));
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        List<SalaryCompletenessCell> cells = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            cells.add(new SalaryCompletenessCell(String.valueOf(row[0]), String.valueOf(row[1]),
                    toInt(row[2]), toInt(row[3]), toInt(row[4]), toInt(row[5]), toInt(row[6]),
                    toBoolean(row[7]), toInt(row[8])));
        }
        return cells;
    }

    private List<Object[]> loadFteRows(String fromKey, String toKey, IntSupplier queryTimeoutMs) {
        Query query = em.createNativeQuery("""
                SELECT company_id, practice_id, month_key, SUM(fte_billable) AS monthly_fte
                FROM fact_employee_monthly_mat
                WHERE month_key >= :fromKey AND month_key <= :toKey
                  AND company_id IN (:companies)
                  AND practice_id IN (:practices)
                  AND role_type = 'BILLABLE'
                  AND fte_billable > 0
                GROUP BY company_id, practice_id, month_key
                ORDER BY company_id, practice_id, month_key
                """);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setParameter("companies", PRODUCTION_COMPANY_SET);
        query.setParameter("practices", PRACTICE_SET);
        query.setHint("jakarta.persistence.query.timeout", remainingQueryTimeout(queryTimeoutMs));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        restoreLegacyNullAggregateAsZero(rows, 3);
        return rows;
    }

    private List<Object[]> loadCanonicalFteRows(
            String generationId, String fromKey, String toKey, IntSupplier queryTimeoutMs) {
        Query query = em.createNativeQuery("""
                SELECT company_id, practice_code, month_key, SUM(billable_fte)
                FROM fact_practice_fte_generation_mat
                WHERE generation_id=:generation AND month_key BETWEEN :fromKey AND :toKey
                  AND company_id IN (:companies) AND practice_code IN (:practices)
                  AND billable_fte > 0
                GROUP BY company_id, practice_code, month_key
                ORDER BY company_id, practice_code, month_key
                """);
        query.setParameter("generation", generationId);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setParameter("companies", PRODUCTION_COMPANY_SET);
        query.setParameter("practices", PRACTICE_SET);
        query.setHint("jakarta.persistence.query.timeout", remainingQueryTimeout(queryTimeoutMs));
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        return rows;
    }

    private PublicationSnapshot loadPublishedSnapshot(IntSupplier queryTimeoutMs) {
        Query query = em.createNativeQuery(PUBLICATION_SNAPSHOT_SQL);
        query.setHint("jakarta.persistence.query.timeout", remainingQueryTimeout(queryTimeoutMs));
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        if (rows.size() != 1) {
            throw unavailable();
        }

        Object[] row = rows.getFirst();
        PublicationSnapshot snapshot = new PublicationSnapshot(
                nullableString(row[0]),
                nullableString(row[1]),
                toInstant(row[2]),
                toInstant(row[3]),
                toLong(row[4]),
                toLong(row[5]),
                toLong(row[6]),
                new SourcePublication(toLong(row[7]), toLong(row[8]),
                        toInstant(row[9]), toInstant(row[10])),
                new SourcePublication(toLong(row[11]), toLong(row[12]),
                        toInstant(row[13]), toInstant(row[14])),
                new SourcePublication(toLong(row[15]), toLong(row[16]),
                        toInstant(row[17]), toInstant(row[18])));
        String failure = publicationValidationFailure(snapshot);
        if (failure != null) {
            log.warnf("practice operating-cost publication unavailable: %s", failure);
            throw unavailable();
        }
        return snapshot;
    }

    static PracticeCostSnapshotProvider.CanonicalPractice toCanonical(
            String practice, PracticeAccumulator acc,
            CoverageResult currentFteCoverage, CoverageResult priorFteCoverage) {
        BigDecimal currentTotal = acc.currentSalary.add(acc.currentOpex);
        BigDecimal priorTotal = acc.priorSalary.add(acc.priorOpex);
        BigDecimal totalDelta = currentTotal.subtract(priorTotal);
        BigDecimal totalDeltaPct = priorTotal.signum() != 0
                ? totalDelta.divide(priorTotal.abs(), DIVISION_CONTEXT).multiply(BigDecimal.valueOf(100)) : null;
        BigDecimal currentAverageFte = acc.currentFteSum.divide(
                BigDecimal.valueOf(PERIOD_MONTHS), DIVISION_CONTEXT);
        BigDecimal priorAverageFte = acc.priorFteSum.divide(
                BigDecimal.valueOf(PERIOD_MONTHS), DIVISION_CONTEXT);
        BigDecimal currentCostPerFte = currentAverageFte.signum() > 0
                ? currentTotal.divide(currentAverageFte, DIVISION_CONTEXT) : null;
        BigDecimal priorCostPerFte = priorAverageFte.signum() > 0
                ? priorTotal.divide(priorAverageFte, DIVISION_CONTEXT) : null;
        BigDecimal costPerFteDelta = currentCostPerFte != null && priorCostPerFte != null
                ? currentCostPerFte.subtract(priorCostPerFte) : null;
        BigDecimal costPerFteDeltaPct = costPerFteDelta != null && priorCostPerFte.signum() != 0
                ? costPerFteDelta.divide(priorCostPerFte.abs(), DIVISION_CONTEXT)
                        .multiply(BigDecimal.valueOf(100)) : null;

        return new PracticeCostSnapshotProvider.CanonicalPractice(
                practice,
                acc.currentSalary,
                acc.currentOpex,
                currentTotal,
                acc.priorSalary,
                acc.priorOpex,
                priorTotal,
                totalDelta,
                totalDeltaPct,
                currentAverageFte,
                priorAverageFte,
                currentCostPerFte,
                priorCostPerFte,
                costPerFteDelta,
                costPerFteDeltaPct,
                currentFteCoverage.expectedCount(), currentFteCoverage.coveredCount(),
                currentFteCoverage.missingCount(), priorFteCoverage.expectedCount(),
                priorFteCoverage.coveredCount(), priorFteCoverage.missingCount()
        );
    }

    static PracticeOperatingCostDTO toDto(String practice, PracticeAccumulator accumulator) {
        CoverageResult complete = new CoverageResult(12, 12, 12, 0, 0, true);
        return toCanonical(practice, accumulator, complete, complete).toLegacy();
    }

    static CoverageResult coverage(Set<String> expected, Set<String> actual) {
        Set<String> covered = new HashSet<>(expected);
        covered.retainAll(actual);
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new HashSet<>(actual);
        unexpected.removeAll(expected);
        return new CoverageResult(
                expected.size(), actual.size(), covered.size(), missing.size(), unexpected.size(),
                expected.equals(actual));
    }

    static Optional<YearMonth> latestCompleteAnchor(
            List<SalaryCompletenessCell> cells, YearMonth latestCompletedMonth) {
        Map<String, Map<String, SalaryCompletenessCell>> byMonthAndCompany = new HashMap<>();
        Set<String> duplicateMonths = new HashSet<>();
        for (SalaryCompletenessCell cell : cells) {
            if (!PRODUCTION_COMPANY_SET.contains(cell.companyId())) continue;
            Map<String, SalaryCompletenessCell> byCompany = byMonthAndCompany.computeIfAbsent(
                    cell.monthKey(), ignored -> new HashMap<>());
            if (byCompany.putIfAbsent(cell.companyId(), cell) != null) {
                duplicateMonths.add(cell.monthKey());
            }
        }

        for (int anchorOffset = 0; anchorOffset < ANCHOR_SEARCH_MONTHS; anchorOffset++) {
            YearMonth candidateEnd = latestCompletedMonth.minusMonths(anchorOffset);
            boolean completeWindow = true;
            for (int periodOffset = 0; periodOffset < PERIOD_MONTHS; periodOffset++) {
                String key = monthKey(candidateEnd.minusMonths(periodOffset));
                Map<String, SalaryCompletenessCell> byCompany = byMonthAndCompany.get(key);
                if (duplicateMonths.contains(key)
                        || byCompany == null
                        || !byCompany.keySet().equals(PRODUCTION_COMPANY_SET)
                        || byCompany.values().stream().anyMatch(cell -> !cell.complete())) {
                    completeWindow = false;
                    break;
                }
            }
            if (completeWindow) return Optional.of(candidateEnd);
        }
        return Optional.empty();
    }

    static PeriodCostCompleteness summarizeCostCompleteness(
            List<SalaryCompletenessCell> cells, String fromKey, String toKey) {
        Map<String, SalaryCompletenessCell> uniqueCells = new HashMap<>();
        boolean duplicate = false;
        for (SalaryCompletenessCell cell : cells) {
            if (!PRODUCTION_COMPANY_SET.contains(cell.companyId())
                    || cell.monthKey().compareTo(fromKey) < 0
                    || cell.monthKey().compareTo(toKey) > 0) {
                continue;
            }
            if (uniqueCells.putIfAbsent(
                    metadataCell(cell.companyId(), cell.monthKey()), cell) != null) {
                duplicate = true;
            }
        }

        int expectedCount = 0;
        int actualCount = 0;
        int coveredCount = 0;
        int missingCount = 0;
        int unexpectedCount = 0;
        boolean everyCellComplete = true;
        int costMonthEndFallbackEmployeeMonthCount = 0;
        for (SalaryCompletenessCell cell : uniqueCells.values()) {
            expectedCount += cell.expectedSalaryCellCount();
            actualCount += cell.actualSalaryCellCount();
            coveredCount += cell.coveredSalaryCellCount();
            missingCount += cell.missingSalaryCellCount();
            unexpectedCount += cell.unexpectedSalaryCellCount();
            everyCellComplete &= cell.complete();
            costMonthEndFallbackEmployeeMonthCount += cell.costMonthEndFallbackEmployeeMonthCount();
        }

        int expectedMetadataCells = PRODUCTION_COMPANIES.size() * inclusiveMonthCount(fromKey, toKey);
        boolean exactSalaryCells = missingCount == 0
                && unexpectedCount == 0
                && expectedCount == actualCount
                && expectedCount == coveredCount;
        CoverageResult salaryCoverage = new CoverageResult(
                expectedCount, actualCount, coveredCount, missingCount, unexpectedCount, exactSalaryCells);
        boolean complete = !duplicate
                && uniqueCells.size() == expectedMetadataCells
                && everyCellComplete
                && exactSalaryCells;
        return new PeriodCostCompleteness(
                salaryCoverage, complete, costMonthEndFallbackEmployeeMonthCount);
    }

    static Set<String> expectedPracticeMonthCells(String fromKey, String toKey) {
        YearMonth month = parseMonthKey(fromKey);
        YearMonth end = parseMonthKey(toKey);
        Set<String> cells = new HashSet<>();
        while (!month.isAfter(end)) {
            String key = monthKey(month);
            for (String practice : PRACTICES) {
                cells.add(practiceMonthCell(practice, key));
            }
            month = month.plusMonths(1);
        }
        return cells;
    }

    static Set<String> expectedPracticeMonthCells(String practice, String fromKey, String toKey) {
        YearMonth month = parseMonthKey(fromKey);
        YearMonth end = parseMonthKey(toKey);
        Set<String> cells = new HashSet<>();
        while (!month.isAfter(end)) {
            cells.add(practiceMonthCell(practice, monthKey(month)));
            month = month.plusMonths(1);
        }
        return cells;
    }

    static String completenessStatus(boolean salaryComplete, boolean fteComplete) {
        if (salaryComplete && fteComplete) return "COMPLETE";
        if (!salaryComplete && !fteComplete) return "INCOMPLETE_SALARY_AND_FTE_COVERAGE";
        if (!salaryComplete) return "INCOMPLETE_SALARY_COVERAGE";
        return "INCOMPLETE_FTE_COVERAGE";
    }

    private static String practiceMonthCell(String practice, String monthKey) {
        return practice + ':' + monthKey;
    }

    private static String metadataCell(String company, String monthKey) {
        return company + ':' + monthKey;
    }

    private static int inclusiveMonthCount(String fromKey, String toKey) {
        YearMonth from = parseMonthKey(fromKey);
        YearMonth to = parseMonthKey(toKey);
        if (to.isBefore(from)) return 0;
        return (to.getYear() - from.getYear()) * 12 + to.getMonthValue() - from.getMonthValue() + 1;
    }

    private static YearMonth parseMonthKey(String value) {
        return YearMonth.parse(value, MONTH_KEY);
    }

    private static int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(String.valueOf(value));
    }

    /**
     * Restores the pre-extraction legacy contract (SQL {@code NULL -> 0}) for an all-NULL aggregate
     * column. Applied only to the uncertified legacy OPEX/FTE loaders; the shared {@link #decimal}
     * helper and the certified generation loaders are left untouched, so a missing canonical
     * aggregate remains null and its suppressible cost/FTE metric stays unavailable.
     */
    private static void restoreLegacyNullAggregateAsZero(List<Object[]> rows, int amountIndex) {
        for (Object[] row : rows) {
            if (row[amountIndex] == null) {
                row[amountIndex] = BigDecimal.ZERO;
            }
        }
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof LocalDateTime localDateTime) return localDateTime.toInstant(ZoneOffset.UTC);
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
        throw new IllegalStateException("Unexpected publication timestamp type: " + value.getClass());
    }

    static String publicationValidationFailure(PublicationSnapshot snapshot) {
        if (snapshot == null) return "publication row missing";
        if (!"READY".equals(snapshot.publicationState())) return "publication is not READY";
        if (snapshot.activeRefreshToken() != null) return "publication token is still active";
        if (snapshot.generationAt() == null || snapshot.publishedAt() == null) {
            return "publication timestamps are missing";
        }
        if (snapshot.publishedAt().isBefore(snapshot.generationAt())) {
            return "publication precedes its source generation";
        }
        String opexFailure = sourceValidationFailure(
                "operating-cost", snapshot.opexRowCount(), snapshot.opex(), snapshot.generationAt());
        if (opexFailure != null) return opexFailure;
        String fteFailure = sourceValidationFailure(
                "FTE", snapshot.fteRowCount(), snapshot.fte(), snapshot.generationAt());
        if (fteFailure != null) return fteFailure;
        return sourceValidationFailure(
                "completeness", snapshot.completenessRowCount(),
                snapshot.completeness(), snapshot.generationAt());
    }

    private static String sourceValidationFailure(
            String source,
            long publishedRowCount,
            SourcePublication evidence,
            Instant generationAt) {
        if (publishedRowCount <= 0 || evidence.totalRowCount() <= 0) {
            return source + " source is empty";
        }
        if (publishedRowCount != evidence.totalRowCount()) {
            return source + " row count does not match publication";
        }
        if (evidence.timestampedRowCount() != evidence.totalRowCount()) {
            return source + " contains unpublished rows";
        }
        if (!generationAt.equals(evidence.minGenerationAt())
                || !generationAt.equals(evidence.maxGenerationAt())) {
            return source + " generation does not match publication";
        }
        return null;
    }

    static boolean samePublication(PublicationSnapshot before, PublicationSnapshot after) {
        return before != null && before.equals(after);
    }

    private static IntSupplier fixedQueryTimeout(int queryTimeoutMs) {
        int bounded = boundedQueryTimeout(queryTimeoutMs);
        return () -> bounded;
    }

    private static int remainingQueryTimeout(IntSupplier queryTimeoutMs) {
        if (queryTimeoutMs == null) throw new NullPointerException("queryTimeoutMs");
        return boundedQueryTimeout(queryTimeoutMs.getAsInt());
    }

    private static int boundedQueryTimeout(int queryTimeoutMs) {
        if (queryTimeoutMs < 1 || queryTimeoutMs > CXO_QUERY_TIMEOUT_MS) {
            throw new IllegalArgumentException("query timeout is outside the canonical CXO bound");
        }
        return queryTimeoutMs;
    }

    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException(
                "Operating-cost evidence is refreshing or unavailable; values are withheld.");
    }

    private static String monthKey(YearMonth month) {
        return month.format(MONTH_KEY);
    }

    static final class PracticeAccumulator {
        BigDecimal currentSalary = BigDecimal.ZERO;
        BigDecimal currentOpex = BigDecimal.ZERO;
        BigDecimal priorSalary = BigDecimal.ZERO;
        BigDecimal priorOpex = BigDecimal.ZERO;
        BigDecimal currentFteSum = BigDecimal.ZERO;
        BigDecimal priorFteSum = BigDecimal.ZERO;
    }

    record OperatingWindow(
            String reportingThroughMonthKey,
            String currentStartMonthKey,
            String currentEndMonthKey,
            String priorStartMonthKey,
            String priorEndMonthKey) {
    }

    record CoverageResult(
            int expectedCount,
            int actualCount,
            int coveredCount,
            int missingCount,
            int unexpectedCount,
            boolean complete) {
    }

    record SalaryCompletenessCell(
            String companyId,
            String monthKey,
            int expectedSalaryCellCount,
            int actualSalaryCellCount,
            int coveredSalaryCellCount,
            int missingSalaryCellCount,
            int unexpectedSalaryCellCount,
            boolean complete,
            int costMonthEndFallbackEmployeeMonthCount) {
    }

    record PeriodCostCompleteness(CoverageResult salaryCoverage, boolean complete,
                                  int costMonthEndFallbackEmployeeMonthCount) {
    }

    record SourcePublication(
            long totalRowCount,
            long timestampedRowCount,
            Instant minGenerationAt,
            Instant maxGenerationAt) {
    }

    record PublicationSnapshot(
            String publicationState,
            String activeRefreshToken,
            Instant generationAt,
            Instant publishedAt,
            long opexRowCount,
            long fteRowCount,
            long completenessRowCount,
            SourcePublication opex,
            SourcePublication fte,
            SourcePublication completeness) {
    }
}
