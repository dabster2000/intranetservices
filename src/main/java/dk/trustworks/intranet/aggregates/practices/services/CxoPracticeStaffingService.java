package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeStaffingConsultantDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeStaffingResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeStaffingSummaryDTO;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.CXO_QUERY_TIMEOUT_MS;

/** Group staffing signals with separate planned-allocation and completed-day actual definitions. */
@JBossLog
@ApplicationScoped
public class CxoPracticeStaffingService {

    static final List<String> PRACTICES = List.of("PM", "BA", "CYB", "DEV", "SA");
    private static final Set<String> PRACTICE_SET = new LinkedHashSet<>(PRACTICES);
    static final double PLANNED_UNALLOCATED_THRESHOLD_PCT = 10.0;
    static final double ACTUAL_UNDERUTILIZED_THRESHOLD_PCT = 50.0;
    static final double STANDARD_DAILY_FTE_HOURS = 7.4;
    static final String PLANNED_ROWS_SQL = """
            WITH monthly_capacity AS (
                SELECT fud.useruuid,
                       u.firstname,
                       u.lastname,
                       u.practice,
                       DATE_FORMAT(fud.document_date, '%Y%m') AS month_key,
                       SUM(fud.net_available_hours) AS net_available_hours
                FROM fact_user_day fud
                JOIN `user` u ON u.uuid = fud.useruuid
                WHERE fud.document_date >= :fromDate
                  AND fud.document_date < :toDate
                  AND fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                  AND u.practice IN (:practices)
                GROUP BY fud.useruuid, u.firstname, u.lastname, u.practice,
                         DATE_FORMAT(fud.document_date, '%Y%m')
                HAVING SUM(fud.net_available_hours) > 0
            ), monthly_budget AS (
                SELECT useruuid,
                       DATE_FORMAT(document_date, '%Y%m') AS month_key,
                       SUM(budgetHours) AS budget_hours
                FROM fact_budget_day
                WHERE document_date >= :fromDate AND document_date < :toDate
                GROUP BY useruuid, DATE_FORMAT(document_date, '%Y%m')
            )
            SELECT c.useruuid, c.firstname, c.lastname, c.practice, c.month_key,
                   c.net_available_hours, COALESCE(b.budget_hours, 0) AS budget_hours
            FROM monthly_capacity c
            LEFT JOIN monthly_budget b
              ON b.useruuid = c.useruuid AND b.month_key = c.month_key
            ORDER BY c.practice, c.month_key, c.firstname, c.lastname, c.useruuid
            """;
    static final String ACTUAL_ROWS_SQL = """
            SELECT fud.useruuid, u.firstname, u.lastname,
                   COALESCE(uph.practice, u.practice) AS practice,
                   SUM(fud.net_available_hours) AS net_available_hours,
                   SUM(fud.registered_billable_hours) AS billable_hours
            FROM fact_user_day fud
            JOIN `user` u ON u.uuid = fud.useruuid
            LEFT JOIN user_practice_history uph ON uph.useruuid = fud.useruuid
              AND fud.document_date >= uph.effective_from
              AND (uph.effective_to IS NULL OR fud.document_date < uph.effective_to)
            WHERE fud.document_date >= :fromDate
              AND fud.document_date <= :toDate
              AND fud.consultant_type = 'CONSULTANT'
              AND fud.status_type = 'ACTIVE'
              AND COALESCE(uph.practice, u.practice) IN (:practices)
            GROUP BY fud.useruuid, u.firstname, u.lastname, COALESCE(uph.practice, u.practice)
            HAVING SUM(fud.net_available_hours) > 0
            ORDER BY COALESCE(uph.practice, u.practice), u.firstname, u.lastname, fud.useruuid
            """;

    @Inject
    EntityManager em;

    @Inject
    PracticeAttributionService practiceAttributionService;

    public PracticeStaffingResponseDTO getStaffing(String requestedPractice) {
        return getStaffing(normalizePractice(requestedPractice),
                LocalDate.now(UtilizationCalculationHelper.REPORTING_ZONE));
    }

    PracticeStaffingResponseDTO getStaffing(String requestedPractice, LocalDate copenhagenToday) {
        YearMonth plannedMonth = YearMonth.from(copenhagenToday);
        YearMonth priorPlannedMonth = plannedMonth.minusMonths(1);
        StaffingWindow staffingWindow = staffingWindow(copenhagenToday);
        LocalDate actualTo = staffingWindow.actualToDate();
        LocalDate actualFrom = staffingWindow.actualFromDate();
        int standardWorkingDays = countWeekdays(actualFrom, actualTo);
        double standardPeriodHours = standardWorkingDays * STANDARD_DAILY_FTE_HOURS;

        List<Object[]> plannedRows = loadPlannedRows(
                priorPlannedMonth.atDay(1), plannedMonth.plusMonths(1).atDay(1));
        List<Object[]> actualRows = loadActualRows(actualFrom, actualTo);

        String plannedKey = monthKey(plannedMonth);
        String priorKey = monthKey(priorPlannedMonth);
        Map<String, Integer> currentPlannedCount = zeroIntMap();
        Map<String, Integer> priorPlannedCount = zeroIntMap();
        Map<String, Integer> actualCount = zeroIntMap();
        Map<String, Double> actualUnusedFte = zeroDoubleMap();

        Map<String, ConsultantMetrics> currentPlannedByUserPractice = new HashMap<>();
        Set<String> detailUserPracticeKeys = new HashSet<>();
        for (Object[] row : plannedRows) {
            ConsultantMetrics metrics = plannedMetrics(row);
            if (!PRACTICE_SET.contains(metrics.practiceId) || metrics.netAvailableHours <= 0.0) continue;
            boolean unallocated = isPlannedUnallocated(metrics.utilizationPct);
            if (plannedKey.equals(metrics.monthKey)) {
                metrics.plannedUnallocated = unallocated;
                String userPracticeKey = userPracticeKey(metrics.userUuid, metrics.practiceId);
                currentPlannedByUserPractice.put(userPracticeKey, metrics);
                if (unallocated) {
                    currentPlannedCount.merge(metrics.practiceId, 1, Integer::sum);
                    detailUserPracticeKeys.add(userPracticeKey);
                }
            } else if (priorKey.equals(metrics.monthKey) && unallocated) {
                priorPlannedCount.merge(metrics.practiceId, 1, Integer::sum);
            }
        }

        Map<String, ConsultantMetrics> actualByUserPractice = new HashMap<>();
        for (Object[] row : actualRows) {
            ConsultantMetrics metrics = actualMetrics(row, standardPeriodHours);
            if (!PRACTICE_SET.contains(metrics.practiceId) || metrics.actualNetAvailableHours <= 0.0) continue;
            if (isActualUnderutilized(metrics.actualUtilizationPct)) {
                String userPracticeKey = userPracticeKey(metrics.userUuid, metrics.practiceId);
                actualByUserPractice.put(userPracticeKey, metrics);
                actualCount.merge(metrics.practiceId, 1, Integer::sum);
                actualUnusedFte.merge(metrics.practiceId, metrics.actualUnusedFte, Double::sum);
                detailUserPracticeKeys.add(userPracticeKey);
            }
        }

        List<PracticeStaffingSummaryDTO> summaries = new ArrayList<>(PRACTICES.size());
        for (String practice : PRACTICES) {
            int current = currentPlannedCount.get(practice);
            int prior = priorPlannedCount.get(practice);
            summaries.add(new PracticeStaffingSummaryDTO(
                    practice,
                    current,
                    prior,
                    current - prior,
                    actualCount.get(practice),
                    actualUnusedFte.get(practice)
            ));
        }

        List<PracticeStaffingConsultantDTO> consultants = new ArrayList<>();
        if (requestedPractice != null) {
            for (String key : detailUserPracticeKeys) {
                ConsultantMetrics planned = currentPlannedByUserPractice.get(key);
                ConsultantMetrics actual = actualByUserPractice.get(key);
                ConsultantMetrics identity = planned != null ? planned : actual;
                if (!requestedPractice.equals(identity.practiceId)) continue;
                consultants.add(new PracticeStaffingConsultantDTO(
                        identity.userUuid,
                        identity.fullName,
                        identity.practiceId,
                        planned != null,
                        planned == null ? null : planned.budgetHours,
                        planned == null ? null : planned.netAvailableHours,
                        planned == null ? null : planned.utilizationPct,
                        planned != null && planned.plannedUnallocated,
                        actual != null,
                        actual == null ? null : actual.actualBillableHours,
                        actual == null ? null : actual.actualNetAvailableHours,
                        actual == null ? null : actual.actualUtilizationPct,
                        actual == null ? null : actual.actualUnusedFte
                ));
            }
        }
        consultants.sort(Comparator.comparing(PracticeStaffingConsultantDTO::practiceId)
                .thenComparing(PracticeStaffingConsultantDTO::fullName)
                .thenComparing(PracticeStaffingConsultantDTO::userUuid));

        log.debugf("practice staffing: planned=%s actual=%s..%s consultants=%d",
                plannedKey, actualFrom, actualTo, consultants.size());
        PracticeAttributionService.AttributionMetadata attribution = practiceAttributionService.metadata();
        return new PracticeStaffingResponseDTO(
                plannedKey,
                priorKey,
                actualFrom,
                actualTo,
                actualTo,
                attribution.method(),
                attribution.coverageStartDate(),
                attribution.note(),
                summaries,
                consultants
        );
    }

    private List<Object[]> loadPlannedRows(LocalDate fromInclusive, LocalDate toExclusive) {
        Query query = em.createNativeQuery(PLANNED_ROWS_SQL);
        query.setParameter("fromDate", fromInclusive);
        query.setParameter("toDate", toExclusive);
        query.setParameter("practices", PRACTICE_SET);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    private List<Object[]> loadActualRows(LocalDate fromInclusive, LocalDate toInclusive) {
        Query query = em.createNativeQuery(ACTUAL_ROWS_SQL);
        query.setParameter("fromDate", fromInclusive);
        query.setParameter("toDate", toInclusive);
        query.setParameter("practices", PRACTICE_SET);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    private static ConsultantMetrics plannedMetrics(Object[] row) {
        ConsultantMetrics metrics = identity(row[0], row[1], row[2], row[3]);
        metrics.monthKey = String.valueOf(row[4]);
        metrics.netAvailableHours = CxoSqlSupport.toDouble(row[5]);
        metrics.budgetHours = CxoSqlSupport.toDouble(row[6]);
        metrics.utilizationPct = utilizationPct(
                metrics.budgetHours, metrics.netAvailableHours);
        return metrics;
    }

    private static ConsultantMetrics actualMetrics(Object[] row, double standardPeriodHours) {
        ConsultantMetrics metrics = identity(row[0], row[1], row[2], row[3]);
        metrics.actualNetAvailableHours = CxoSqlSupport.toDouble(row[4]);
        metrics.actualBillableHours = CxoSqlSupport.toDouble(row[5]);
        metrics.actualUtilizationPct = utilizationPct(
                metrics.actualBillableHours, metrics.actualNetAvailableHours);
        metrics.actualUnusedFte = calculateUnusedFte(
                metrics.actualBillableHours, metrics.actualNetAvailableHours, standardPeriodHours);
        return metrics;
    }

    private static ConsultantMetrics identity(Object userUuid, Object firstname, Object lastname, Object practice) {
        ConsultantMetrics metrics = new ConsultantMetrics();
        metrics.userUuid = String.valueOf(userUuid);
        String first = firstname == null ? "" : String.valueOf(firstname).trim();
        String last = lastname == null ? "" : String.valueOf(lastname).trim();
        metrics.fullName = (first + " " + last).trim();
        metrics.practiceId = String.valueOf(practice);
        return metrics;
    }

    static double calculateUnusedFte(double billableHours, double netAvailableHours, double standardPeriodHours) {
        if (standardPeriodHours <= 0.0) return 0.0;
        return Math.max(netAvailableHours - billableHours, 0.0) / standardPeriodHours;
    }

    static double utilizationPct(double numeratorHours, double denominatorHours) {
        return UtilizationCalculationHelper.calcPercent(numeratorHours, denominatorHours);
    }

    static boolean isPlannedUnallocated(double utilizationPct) {
        return utilizationPct < PLANNED_UNALLOCATED_THRESHOLD_PCT;
    }

    static boolean isActualUnderutilized(double utilizationPct) {
        return utilizationPct < ACTUAL_UNDERUTILIZED_THRESHOLD_PCT;
    }

    static StaffingWindow staffingWindow(LocalDate copenhagenToday) {
        LocalDate actualTo = copenhagenToday.minusDays(1);
        return new StaffingWindow(actualTo.minusDays(27), actualTo);
    }

    static int countWeekdays(LocalDate fromInclusive, LocalDate toInclusive) {
        if (fromInclusive.isAfter(toInclusive)) return 0;
        int count = 0;
        for (LocalDate date = fromInclusive; !date.isAfter(toInclusive); date = date.plusDays(1)) {
            DayOfWeek day = date.getDayOfWeek();
            if (day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY) count++;
        }
        return count;
    }

    static String normalizePractice(String requestedPractice) {
        if (requestedPractice == null || requestedPractice.isBlank()) return null;
        String normalized = requestedPractice.trim().toUpperCase(java.util.Locale.ROOT);
        if (!PRACTICE_SET.contains(normalized)) {
            throw new IllegalArgumentException("practice must be one of PM, BA, CYB, DEV, SA");
        }
        return normalized;
    }

    static String userPracticeKey(String userUuid, String practiceId) {
        return userUuid + '\u0000' + practiceId;
    }

    private static String monthKey(YearMonth month) {
        return "%04d%02d".formatted(month.getYear(), month.getMonthValue());
    }

    private static Map<String, Integer> zeroIntMap() {
        Map<String, Integer> result = new LinkedHashMap<>();
        PRACTICES.forEach(p -> result.put(p, 0));
        return result;
    }

    private static Map<String, Double> zeroDoubleMap() {
        Map<String, Double> result = new LinkedHashMap<>();
        PRACTICES.forEach(p -> result.put(p, 0.0));
        return result;
    }

    private static final class ConsultantMetrics {
        String userUuid;
        String fullName;
        String practiceId;
        String monthKey;
        double budgetHours;
        double netAvailableHours;
        double utilizationPct;
        boolean plannedUnallocated;
        double actualBillableHours;
        double actualNetAvailableHours;
        double actualUtilizationPct;
        double actualUnusedFte;
    }

    record StaffingWindow(LocalDate actualFromDate, LocalDate actualToDate) {
    }
}
