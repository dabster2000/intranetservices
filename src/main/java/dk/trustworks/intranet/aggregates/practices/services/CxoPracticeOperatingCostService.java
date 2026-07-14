package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.CXO_QUERY_TIMEOUT_MS;

/** Reads signed group operating cost without legal-entity slicing. */
@JBossLog
@ApplicationScoped
public class CxoPracticeOperatingCostService {

    static final List<String> PRACTICES = List.of("PM", "BA", "CYB", "DEV", "SA");
    private static final Set<String> PRACTICE_SET = new LinkedHashSet<>(PRACTICES);
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyyMM");
    private static final int PERIOD_MONTHS = 12;
    static final String COST_ROWS_SQL = """
            SELECT company_id, practice_id, month_key, cost_type, SUM(opex_amount_dkk) AS amount_dkk
            FROM fact_opex_mat
            WHERE month_key >= :fromKey AND month_key <= :toKey
              AND practice_id IN (:practices)
              AND cost_type IN ('SALARIES', 'OPEX')
              AND posting_status IN (:postingStatuses)
            GROUP BY company_id, practice_id, month_key, cost_type
            ORDER BY company_id, practice_id, month_key, cost_type
            """;

    @Inject
    EntityManager em;

    public PracticeOperatingCostResponseDTO getOperatingCost(CostSource requestedCostSource) {
        CostSource costSource = requestedCostSource == null ? CostSource.BOOKED : requestedCostSource;
        Set<String> postingStatuses = new LinkedHashSet<>(costSource.postingStatusNames());

        LocalDate copenhagenToday = LocalDate.now(UtilizationCalculationHelper.REPORTING_ZONE);
        OperatingWindow window = reportingWindow(copenhagenToday);
        String anchorKey = window.reportingThroughMonthKey();
        String currentStartKey = window.currentStartMonthKey();
        String currentEndKey = window.currentEndMonthKey();
        String priorStartKey = window.priorStartMonthKey();
        String priorEndKey = window.priorEndMonthKey();

        List<Object[]> costRows = loadCostRows(priorStartKey, currentEndKey, postingStatuses);
        List<Object[]> fteRows = loadFteRows(priorStartKey, currentEndKey);

        Map<String, PracticeAccumulator> byPractice = new LinkedHashMap<>();
        PRACTICES.forEach(p -> byPractice.put(p, new PracticeAccumulator()));

        Set<String> currentSalaryMonths = new HashSet<>();
        Set<String> currentOpexMonths = new HashSet<>();
        Set<String> priorSalaryMonths = new HashSet<>();
        Set<String> priorOpexMonths = new HashSet<>();
        Set<String> currentSalaryCells = new HashSet<>();
        Set<String> priorSalaryCells = new HashSet<>();

        for (Object[] row : costRows) {
            String company = String.valueOf(row[0]);
            String practice = String.valueOf(row[1]);
            String key = String.valueOf(row[2]);
            String costType = String.valueOf(row[3]);
            double amount = CxoSqlSupport.toDouble(row[4]);
            PracticeAccumulator acc = byPractice.get(practice);
            if (acc == null) continue;

            boolean current = key.compareTo(currentStartKey) >= 0;
            String salaryCoverageCell = coverageCell(company, practice, key);
            if ("SALARIES".equals(costType)) {
                if (current) {
                    acc.currentSalary += amount;
                    currentSalaryMonths.add(key);
                    currentSalaryCells.add(salaryCoverageCell);
                } else {
                    acc.priorSalary += amount;
                    priorSalaryMonths.add(key);
                    priorSalaryCells.add(salaryCoverageCell);
                }
            } else if ("OPEX".equals(costType)) {
                if (current) {
                    acc.currentOpex += amount;
                    currentOpexMonths.add(key);
                } else {
                    acc.priorOpex += amount;
                    priorOpexMonths.add(key);
                }
            }
        }

        Set<String> currentFteMonths = new HashSet<>();
        Set<String> priorFteMonths = new HashSet<>();
        Set<String> currentFteCells = new HashSet<>();
        Set<String> priorFteCells = new HashSet<>();
        Set<String> currentExpectedSalaryCells = new HashSet<>();
        Set<String> priorExpectedSalaryCells = new HashSet<>();
        for (Object[] row : fteRows) {
            String company = String.valueOf(row[0]);
            String practice = String.valueOf(row[1]);
            String key = String.valueOf(row[2]);
            double monthlyFte = CxoSqlSupport.toDouble(row[3]);
            PracticeAccumulator acc = byPractice.get(practice);
            if (acc == null) continue;
            String cell = practice + ':' + key;
            if (key.compareTo(currentStartKey) >= 0) {
                acc.currentFteSum += monthlyFte;
                currentFteMonths.add(key);
                currentFteCells.add(cell);
                currentExpectedSalaryCells.add(coverageCell(company, practice, key));
            } else {
                acc.priorFteSum += monthlyFte;
                priorFteMonths.add(key);
                priorFteCells.add(cell);
                priorExpectedSalaryCells.add(coverageCell(company, practice, key));
            }
        }

        List<PracticeOperatingCostDTO> practices = new ArrayList<>(PRACTICES.size());
        for (String practice : PRACTICES) {
            practices.add(toDto(practice, byPractice.get(practice)));
        }

        int expectedFteCells = PRACTICES.size() * PERIOD_MONTHS;
        // OPEX can legitimately be zero for a practice-month and therefore have no row.
        // Salary uses the stronger company/practice/month coverage grain; practice-only
        // counts can conceal a missing legal entity. FTE remains practice/month based.
        CoverageResult currentSalaryCoverage = coverage(currentExpectedSalaryCells, currentSalaryCells);
        CoverageResult priorSalaryCoverage = coverage(priorExpectedSalaryCells, priorSalaryCells);
        boolean salaryComplete = currentSalaryCoverage.complete() && priorSalaryCoverage.complete();
        boolean fteComplete = currentFteCells.size() == expectedFteCells
                && priorFteCells.size() == expectedFteCells;
        boolean complete = salaryComplete && fteComplete;
        String completenessStatus = completenessStatus(salaryComplete, fteComplete);

        LocalDateTime sourceRefreshedAt = loadBestEffortSourceFreshness();
        log.debugf("practice operating cost: source=%s through=%s status=%s",
                costSource, anchorKey, completenessStatus);
        return new PracticeOperatingCostResponseDTO(
                costSource.name(),
                anchorKey,
                currentStartKey,
                currentEndKey,
                priorStartKey,
                priorEndKey,
                sourceRefreshedAt,
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
                completenessStatus,
                complete,
                "CURRENT_PRACTICE_AT_MATERIALIZATION",
                null,
                "fact_opex_mat is distributed using current practice at materialization; "
                        + "effective-dated practice snapshots are not applied to this historical fact.",
                practices
        );
    }

    /** One complete-month lag beyond the immediately preceding month protects payroll posting. */
    static String reportingThroughMonthKey(LocalDate copenhagenToday) {
        return monthKey(YearMonth.from(copenhagenToday).minusMonths(2));
    }

    static OperatingWindow reportingWindow(LocalDate copenhagenToday) {
        YearMonth currentEnd = YearMonth.from(copenhagenToday).minusMonths(2);
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

    private List<Object[]> loadCostRows(String fromKey, String toKey, Set<String> postingStatuses) {
        Query query = em.createNativeQuery(COST_ROWS_SQL);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setParameter("practices", PRACTICE_SET);
        query.setParameter("postingStatuses", postingStatuses);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    private List<Object[]> loadFteRows(String fromKey, String toKey) {
        Query query = em.createNativeQuery("""
                SELECT company_id, practice_id, month_key, SUM(fte_billable) AS monthly_fte
                FROM fact_employee_monthly_mat
                WHERE month_key >= :fromKey AND month_key <= :toKey
                  AND practice_id IN (:practices)
                  AND role_type = 'BILLABLE'
                  AND fte_billable > 0
                GROUP BY company_id, practice_id, month_key
                ORDER BY company_id, practice_id, month_key
                """);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setParameter("practices", PRACTICE_SET);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    private LocalDateTime loadBestEffortSourceFreshness() {
        Query query = em.createNativeQuery("""
                SELECT table_name, update_time
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('fact_opex_mat', 'fact_employee_monthly_mat')
                """);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        if (rows.size() != 2) return null;

        Map<String, LocalDateTime> byTable = new HashMap<>();
        for (Object[] row : rows) {
            byTable.put(String.valueOf(row[0]), toLocalDateTime(row[1]));
        }
        LocalDateTime opex = byTable.get("fact_opex_mat");
        LocalDateTime fte = byTable.get("fact_employee_monthly_mat");
        if (opex == null || fte == null) return null;
        return opex.isBefore(fte) ? opex : fte;
    }

    static PracticeOperatingCostDTO toDto(String practice, PracticeAccumulator acc) {
        double currentTotal = acc.currentSalary + acc.currentOpex;
        double priorTotal = acc.priorSalary + acc.priorOpex;
        double totalDelta = currentTotal - priorTotal;
        Double totalDeltaPct = priorTotal != 0.0 ? totalDelta / Math.abs(priorTotal) * 100.0 : null;
        double currentAverageFte = acc.currentFteSum / PERIOD_MONTHS;
        double priorAverageFte = acc.priorFteSum / PERIOD_MONTHS;
        Double currentCostPerFte = currentAverageFte > 0.0 ? currentTotal / currentAverageFte : null;
        Double priorCostPerFte = priorAverageFte > 0.0 ? priorTotal / priorAverageFte : null;
        Double costPerFteDelta = currentCostPerFte != null && priorCostPerFte != null
                ? currentCostPerFte - priorCostPerFte : null;
        Double costPerFteDeltaPct = costPerFteDelta != null && priorCostPerFte != 0.0
                ? costPerFteDelta / Math.abs(priorCostPerFte) * 100.0 : null;

        return new PracticeOperatingCostDTO(
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
                costPerFteDeltaPct
        );
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

    static String completenessStatus(boolean salaryComplete, boolean fteComplete) {
        if (salaryComplete && fteComplete) return "COMPLETE";
        if (!salaryComplete && !fteComplete) return "INCOMPLETE_SALARY_AND_FTE_COVERAGE";
        if (!salaryComplete) return "INCOMPLETE_SALARY_COVERAGE";
        return "INCOMPLETE_FTE_COVERAGE";
    }

    private static String coverageCell(String company, String practice, String monthKey) {
        return company + ':' + practice + ':' + monthKey;
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime localDateTime) return localDateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        throw new IllegalStateException("Unexpected information_schema update_time type: " + value.getClass());
    }

    private static String monthKey(YearMonth month) {
        return month.format(MONTH_KEY);
    }

    static final class PracticeAccumulator {
        double currentSalary;
        double currentOpex;
        double priorSalary;
        double priorOpex;
        double currentFteSum;
        double priorFteSum;
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
}
