package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.BudgetActualGapDTO;
import dk.trustworks.intranet.aggregates.finance.dto.BudgetActualGapMonthlyDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ConsultantUtilizationRankingDTO;
import dk.trustworks.intranet.aggregates.finance.dto.ConsultantWithoutContractDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TimeToFirstContractDTO;
import dk.trustworks.intranet.aggregates.finance.dto.UnprofitableConsultantDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Service for Consultant Insights tab on the CxO Executive Dashboard.
 *
 * Provides per-consultant analytics: utilization rankings, time-to-first-contract,
 * bench consultants, unprofitable consultants, and budget-vs-actual gap.
 *
 * All queries filter to consultants in the five core practices (PM, BA, SA, CYB, DEV)
 * by default, with optional company filtering.
 */
@JBossLog
@ApplicationScoped
public class ConsultantInsightsService {

    private static final Set<String> DEFAULT_PRACTICES = Set.of("PM", "BA", "SA", "CYB", "DEV");

    @Inject
    EntityManager em;

    /**
     * Returns consultants ranked by net utilization over the trailing 12 months.
     * Only includes consultants with > 100 net available hours to avoid noise.
     *
     * @param practices  practice filter (defaults to all 5 core practices)
     * @param companyIds optional company filter
     * @param ascending  true for lowest-first, false for highest-first
     * @param limit      max results to return
     * @return ranked list of consultant utilization DTOs
     */
    public List<ConsultantUtilizationRankingDTO> getUtilizationRankings(
            Set<String> practices,
            Set<String> companyIds,
            boolean ascending,
            int limit) {

        Set<String> effectivePractices = effectivePractices(practices);
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();

        // TTM boundaries: 12 months back from start of current month
        LocalDate now = LocalDate.now();
        String toKey = String.format("%04d%02d", now.getYear(), now.getMonthValue());
        LocalDate from = now.minusMonths(12).withDayOfMonth(1);
        String fromKey = String.format("%04d%02d", from.getYear(), from.getMonthValue());

        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT fum.user_id, u.firstname, u.lastname, u.practice,
                   SUM(fum.billable_hours) AS billable,
                   SUM(fum.net_available_hours) AS net_available
            FROM fact_user_utilization_mat fum
            JOIN user u ON u.uuid = fum.user_id
            JOIN userstatus us ON us.useruuid = u.uuid
                 AND us.statusdate = (
                     SELECT MAX(us2.statusdate) FROM userstatus us2 WHERE us2.useruuid = u.uuid
                 )
                 AND us.status NOT IN ('TERMINATED', 'PREBOARDING') AND us.type = 'CONSULTANT'
            WHERE u.practice IN (:practices)
              AND fum.month_key >= :fromKey
              AND fum.month_key < :toKey
            """);

        if (hasCompanies) {
            sql.append("  AND fum.companyuuid IN (:companyIds) ");
        }

        sql.append("""
            GROUP BY fum.user_id, u.firstname, u.lastname, u.practice
            HAVING SUM(fum.net_available_hours) > 100
            """);

        sql.append("ORDER BY (SUM(fum.billable_hours) / SUM(fum.net_available_hours)) ");
        sql.append(ascending ? "ASC" : "DESC");
        sql.append(" LIMIT :resultLimit");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("practices", effectivePractices);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setParameter("resultLimit", limit);
        if (hasCompanies) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        log.debugf("getUtilizationRankings: ascending=%s, limit=%s, returned %s rows",
                ascending, limit, rows.size());

        List<ConsultantUtilizationRankingDTO> results = new ArrayList<>();
        for (Tuple row : rows) {
            double billable = ((Number) row.get("billable")).doubleValue();
            double netAvailable = ((Number) row.get("net_available")).doubleValue();
            double utilPct = netAvailable > 0 ? (billable / netAvailable) * 100.0 : 0.0;

            results.add(new ConsultantUtilizationRankingDTO(
                    (String) row.get("user_id"),
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    (String) row.get("practice"),
                    utilPct,
                    billable,
                    netAvailable
            ));
        }

        return results;
    }

    /**
     * Returns time-to-first-contract data for consultants hired within the last N months.
     * Shows the gap between hire date (first ACTIVE/CONSULTANT status) and first contract assignment.
     *
     * @param practices  practice filter
     * @param companyIds optional company filter
     * @param months     how far back to look for hires (default 24)
     * @return list of hire-to-contract DTOs, ordered by hire date ascending
     */
    public List<TimeToFirstContractDTO> getTimeToFirstContract(
            Set<String> practices,
            Set<String> companyIds,
            int months) {

        Set<String> effectivePractices = effectivePractices(practices);
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        LocalDate cutoffDate = LocalDate.now().minusMonths(months);

        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT sub.user_id, sub.firstname, sub.lastname, sub.practice,
                   sub.hire_date,
                   MIN(cc.activefrom) AS first_contract_date
            FROM (
                SELECT u.uuid AS user_id, u.firstname, u.lastname, u.practice,
                       MIN(us.statusdate) AS hire_date
                FROM user u
                JOIN userstatus us ON us.useruuid = u.uuid
                     AND us.status NOT IN ('TERMINATED', 'PREBOARDING') AND us.type = 'CONSULTANT'
                WHERE u.practice IN (:practices)
            """);

        if (hasCompanies) {
            sql.append("      AND us.companyuuid IN (:companyIds) ");
        }

        sql.append("""
                GROUP BY u.uuid, u.firstname, u.lastname, u.practice
                HAVING MIN(us.statusdate) >= :cutoffDate
            ) sub
            LEFT JOIN contract_consultants cc ON cc.useruuid = sub.user_id
            GROUP BY sub.user_id, sub.firstname, sub.lastname, sub.practice, sub.hire_date
            ORDER BY sub.hire_date ASC
            """);

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("practices", effectivePractices);
        query.setParameter("cutoffDate", cutoffDate);
        if (hasCompanies) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        log.debugf("getTimeToFirstContract: months=%s, cutoff=%s, returned %s rows",
                (Object) months, cutoffDate, rows.size());

        List<TimeToFirstContractDTO> results = new ArrayList<>();
        for (Tuple row : rows) {
            LocalDate hireDate = toLocalDate(row.get("hire_date"));
            LocalDate firstContractDate = toLocalDate(row.get("first_contract_date"));

            Integer daysToContract = null;
            if (hireDate != null && firstContractDate != null) {
                daysToContract = (int) ChronoUnit.DAYS.between(hireDate, firstContractDate);
            }

            results.add(new TimeToFirstContractDTO(
                    (String) row.get("user_id"),
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    (String) row.get("practice"),
                    hireDate,
                    firstContractDate,
                    daysToContract
            ));
        }

        return results;
    }

    /**
     * Returns consultants who have had no active contract for at least the specified number of months.
     * Only includes currently active consultants (latest userstatus = ACTIVE/CONSULTANT).
     *
     * @param practices  practice filter
     * @param companyIds optional company filter
     * @param minMonths  minimum months without a contract (default 3)
     * @return list of bench consultant DTOs, ordered by days since contract descending
     */
    public List<ConsultantWithoutContractDTO> getConsultantsWithoutContract(
            Set<String> practices,
            Set<String> companyIds,
            int minMonths) {

        Set<String> effectivePractices = effectivePractices(practices);
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();
        LocalDate cutoffDate = LocalDate.now().minusMonths(minMonths);

        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT u.uuid AS user_id, u.firstname, u.lastname, u.practice,
                   MAX(cc.activeto) AS last_contract_end,
                   DATEDIFF(CURDATE(), MAX(cc.activeto)) AS days_since
            FROM user u
            JOIN userstatus us ON us.useruuid = u.uuid
                 AND us.statusdate = (
                     SELECT MAX(us2.statusdate) FROM userstatus us2 WHERE us2.useruuid = u.uuid
                 )
                 AND us.status NOT IN ('TERMINATED', 'PREBOARDING') AND us.type = 'CONSULTANT'
            LEFT JOIN contract_consultants cc ON cc.useruuid = u.uuid
            WHERE u.practice IN (:practices)
            """);

        if (hasCompanies) {
            sql.append("  AND us.companyuuid IN (:companyIds) ");
        }

        sql.append("""
            GROUP BY u.uuid, u.firstname, u.lastname, u.practice
            HAVING MAX(cc.activeto) IS NULL OR MAX(cc.activeto) < :cutoffDate
            ORDER BY days_since DESC
            """);

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("practices", effectivePractices);
        query.setParameter("cutoffDate", cutoffDate);
        if (hasCompanies) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        log.debugf("getConsultantsWithoutContract: minMonths=%s, cutoff=%s, returned %s rows",
                (Object) minMonths, cutoffDate, rows.size());

        List<ConsultantWithoutContractDTO> results = new ArrayList<>();
        for (Tuple row : rows) {
            LocalDate lastContractEnd = toLocalDate(row.get("last_contract_end"));

            int daysSince = lastContractEnd != null
                    ? ((Number) row.get("days_since")).intValue() : -1;

            results.add(new ConsultantWithoutContractDTO(
                    (String) row.get("user_id"),
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    (String) row.get("practice"),
                    lastContractEnd,
                    daysSince
            ));
        }

        return results;
    }

    /**
     * Returns consultants whose TTM net profit is negative.
     * Net Profit = Revenue - Salary - (Total OPEX / headcount).
     *
     * Revenue: SUM(registered_amount) from fact_user_day per user TTM.
     * Salary: SUM of monthly MAX(salary) from fact_user_day per user TTM.
     * Shared overhead: total non-salary OPEX from fact_opex_mat TTM / active consultant headcount.
     *
     * @param practices  practice filter
     * @param companyIds optional company filter
     * @return list of unprofitable consultant DTOs, ordered by net profit ascending (worst first)
     */
    public List<UnprofitableConsultantDTO> getUnprofitableConsultants(
            Set<String> practices,
            Set<String> companyIds) {

        Set<String> effectivePractices = effectivePractices(practices);
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();

        // TTM boundaries
        LocalDate now = LocalDate.now();
        LocalDate ttmFrom = now.minusMonths(12).withDayOfMonth(1);
        String fromKey = String.format("%04d%02d", ttmFrom.getYear(), ttmFrom.getMonthValue());
        String toKey = String.format("%04d%02d", now.getYear(), now.getMonthValue());

        // Step 1: Get total non-salary OPEX for TTM period
        StringBuilder opexSql = new StringBuilder();
        opexSql.append("""
            SELECT COALESCE(SUM(opex_amount_dkk), 0) AS total_opex
            FROM fact_opex_mat
            WHERE cost_type = 'OPEX'
              AND month_key >= :fromKey AND month_key < :toKey
            """);
        if (hasCompanies) {
            opexSql.append("  AND company_id IN (:companyIds) ");
        }

        var opexQuery = em.createNativeQuery(opexSql.toString(), Tuple.class);
        opexQuery.setParameter("fromKey", fromKey);
        opexQuery.setParameter("toKey", toKey);
        if (hasCompanies) {
            opexQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> opexRows = opexQuery.getResultList();
        double totalOpex = opexRows.isEmpty() ? 0.0 : ((Number) opexRows.get(0).get("total_opex")).doubleValue();

        // Step 2: Get headcount of active consultants in the practices (distinct users with data in TTM)
        StringBuilder headcountSql = new StringBuilder();
        headcountSql.append("""
            SELECT COUNT(DISTINCT fud.useruuid) AS headcount
            FROM fact_user_day fud
            JOIN user u ON u.uuid = fud.useruuid
            WHERE u.practice IN (:practices)
              AND fud.consultant_type = 'CONSULTANT'
              AND fud.status_type = 'ACTIVE'
              AND fud.document_date >= :ttmFrom AND fud.document_date < :ttmTo
            """);
        if (hasCompanies) {
            headcountSql.append("  AND fud.companyuuid IN (:companyIds) ");
        }

        var headcountQuery = em.createNativeQuery(headcountSql.toString(), Tuple.class);
        headcountQuery.setParameter("practices", effectivePractices);
        headcountQuery.setParameter("ttmFrom", ttmFrom);
        headcountQuery.setParameter("ttmTo", now.withDayOfMonth(1));
        if (hasCompanies) {
            headcountQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> hcRows = headcountQuery.getResultList();
        long headcount = hcRows.isEmpty() ? 1L : ((Number) hcRows.get(0).get("headcount")).longValue();
        if (headcount == 0) headcount = 1; // avoid division by zero

        double sharedOverheadPerConsultant = totalOpex / headcount;

        log.debugf("getUnprofitableConsultants: totalOpex=%.2f, headcount=%s, overheadPerConsultant=%.2f",
                totalOpex, headcount, sharedOverheadPerConsultant);

        // Step 3: Per-user revenue and salary
        // Revenue: SUM(registered_amount) from daily rows
        // Salary: SUM of MAX(salary) per month — computed in a subquery to avoid
        //         re-joining daily rows which would multiply salary by ~20 working days
        StringBuilder userSql = new StringBuilder();
        userSql.append("""
            SELECT rev.useruuid AS user_id, u.firstname, u.lastname, u.practice,
                   rev.ttm_revenue,
                   COALESCE(sal.ttm_salary, 0) AS ttm_salary
            FROM (
                SELECT fud.useruuid, SUM(fud.registered_amount) AS ttm_revenue
                FROM fact_user_day fud
                WHERE fud.consultant_type = 'CONSULTANT'
                  AND fud.status_type = 'ACTIVE'
                  AND fud.document_date >= :ttmFrom AND fud.document_date < :ttmTo
                GROUP BY fud.useruuid
            ) rev
            JOIN user u ON u.uuid = rev.useruuid
            LEFT JOIN (
                SELECT useruuid, SUM(max_salary) AS ttm_salary FROM (
                    SELECT useruuid, MAX(salary) AS max_salary
                    FROM fact_user_day
                    WHERE consultant_type = 'CONSULTANT'
                      AND status_type = 'ACTIVE'
                      AND salary > 0
                      AND document_date >= :ttmFrom AND document_date < :ttmTo
                    GROUP BY useruuid, year, month
                ) ms GROUP BY useruuid
            ) sal ON sal.useruuid = rev.useruuid
            WHERE u.practice IN (:practices)
              AND EXISTS (
                  SELECT 1 FROM userstatus us_active
                  WHERE us_active.useruuid = u.uuid
                    AND us_active.statusdate = (SELECT MAX(us3.statusdate) FROM userstatus us3 WHERE us3.useruuid = u.uuid)
                    AND us_active.status NOT IN ('TERMINATED', 'PREBOARDING') AND us_active.type = 'CONSULTANT'
              )
            """);

        if (hasCompanies) {
            userSql.append("  AND rev.useruuid IN (SELECT DISTINCT us.useruuid FROM userstatus us WHERE us.companyuuid IN (:companyIds)) ");
        }

        userSql.append("""
            ORDER BY ttm_salary DESC
            """);

        var userQuery = em.createNativeQuery(userSql.toString(), Tuple.class);
        userQuery.setParameter("practices", effectivePractices);
        userQuery.setParameter("ttmFrom", ttmFrom);
        userQuery.setParameter("ttmTo", now.withDayOfMonth(1));
        if (hasCompanies) {
            userQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> userRows = userQuery.getResultList();

        log.debugf("getUnprofitableConsultants: found %s consultant rows", userRows.size());

        List<UnprofitableConsultantDTO> results = new ArrayList<>();
        for (Tuple row : userRows) {
            double revenue = row.get("ttm_revenue") != null
                    ? ((Number) row.get("ttm_revenue")).doubleValue() : 0.0;
            double salary = row.get("ttm_salary") != null
                    ? ((Number) row.get("ttm_salary")).doubleValue() : 0.0;
            double netProfit = revenue - salary - sharedOverheadPerConsultant;

            if (netProfit < 0) {
                results.add(new UnprofitableConsultantDTO(
                        (String) row.get("user_id"),
                        (String) row.get("firstname"),
                        (String) row.get("lastname"),
                        (String) row.get("practice"),
                        revenue,
                        salary,
                        sharedOverheadPerConsultant,
                        netProfit
                ));
            }
        }

        // Sort by net profit ascending (most unprofitable first)
        results.sort((a, b) -> Double.compare(a.getNetProfit(), b.getNetProfit()));

        return results;
    }

    /**
     * Returns the per-consultant gap between budgeted hours and actual billable hours over TTM.
     * Identifies consultants driving the largest budget-actual discrepancy.
     *
     * @param practices  practice filter
     * @param companyIds optional company filter
     * @param limit      max results to return (default 15)
     * @return list of budget-actual gap DTOs, ordered by gap descending (largest gap first)
     */
    public List<BudgetActualGapDTO> getBudgetActualGap(
            Set<String> practices,
            Set<String> companyIds,
            int limit) {

        Set<String> effectivePractices = effectivePractices(practices);
        boolean hasCompanies = companyIds != null && !companyIds.isEmpty();

        // TTM boundaries
        LocalDate now = LocalDate.now();
        LocalDate ttmFrom = now.minusMonths(12).withDayOfMonth(1);
        String fromKey = String.format("%04d%02d", ttmFrom.getYear(), ttmFrom.getMonthValue());
        String toKey = String.format("%04d%02d", now.getYear(), now.getMonthValue());

        // Budget subquery from fact_budget_day
        StringBuilder budgetSub = new StringBuilder();
        budgetSub.append("""
            SELECT bd.useruuid, SUM(bd.budgetHours) AS total_budget
            FROM fact_budget_day bd
            JOIN user ub ON ub.uuid = bd.useruuid
            WHERE ub.practice IN (:practices)
              AND bd.document_date >= :ttmFrom AND bd.document_date < :ttmTo
            """);
        if (hasCompanies) {
            budgetSub.append("  AND bd.companyuuid IN (:companyIds) ");
        }
        budgetSub.append("GROUP BY bd.useruuid HAVING SUM(bd.budgetHours) > 100");

        // Actual subquery from fact_user_utilization_mat
        StringBuilder actualSub = new StringBuilder();
        actualSub.append("""
            SELECT fum.user_id AS useruuid, SUM(fum.billable_hours) AS total_billable
            FROM fact_user_utilization_mat fum
            JOIN user ua ON ua.uuid = fum.user_id
            WHERE ua.practice IN (:practices)
              AND fum.month_key >= :fromKey AND fum.month_key < :toKey
            """);
        if (hasCompanies) {
            actualSub.append("  AND fum.companyuuid IN (:companyIds) ");
        }
        actualSub.append("GROUP BY fum.user_id");

        // Main query joining budget and actual
        String sql = """
            SELECT u.uuid AS user_id, u.firstname, u.lastname, u.practice,
                   budget_sub.total_budget AS budget_hours,
                   COALESCE(actual_sub.total_billable, 0) AS actual_hours,
                   budget_sub.total_budget - COALESCE(actual_sub.total_billable, 0) AS gap_hours,
                   COALESCE(actual_sub.total_billable, 0) / NULLIF(budget_sub.total_budget, 0) * 100 AS fulfillment_pct
            FROM (%s) budget_sub
            JOIN user u ON u.uuid = budget_sub.useruuid
            LEFT JOIN (%s) actual_sub ON actual_sub.useruuid = budget_sub.useruuid
            ORDER BY gap_hours DESC
            LIMIT :resultLimit
            """.formatted(budgetSub, actualSub);

        var query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("practices", effectivePractices);
        query.setParameter("ttmFrom", ttmFrom);
        query.setParameter("ttmTo", now.withDayOfMonth(1));
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setParameter("resultLimit", limit);
        if (hasCompanies) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        log.debugf("getBudgetActualGap: limit=%s, returned %s rows", limit, rows.size());

        List<BudgetActualGapDTO> results = new ArrayList<>();
        for (Tuple row : rows) {
            Double fulfillmentPct = row.get("fulfillment_pct") != null
                    ? ((Number) row.get("fulfillment_pct")).doubleValue() : null;

            results.add(new BudgetActualGapDTO(
                    (String) row.get("user_id"),
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    (String) row.get("practice"),
                    ((Number) row.get("budget_hours")).doubleValue(),
                    ((Number) row.get("actual_hours")).doubleValue(),
                    ((Number) row.get("gap_hours")).doubleValue(),
                    fulfillmentPct
            ));
        }

        return results;
    }

    /**
     * Returns 12 monthly rows (TTM, excluding current month) with budget hours and actual hours
     * for a specific consultant. Each row includes utilization percentages.
     *
     * @param userId the consultant's user UUID
     * @return list of monthly budget-vs-actual DTOs, ordered by month ascending
     */
    public List<BudgetActualGapMonthlyDTO> getBudgetActualGapMonthly(String userId) {

        // TTM boundaries: 12 months back from start of current month
        LocalDate now = LocalDate.now();
        LocalDate ttmFrom = now.minusMonths(12).withDayOfMonth(1);
        LocalDate ttmTo = now.withDayOfMonth(1);
        String fromKey = String.format("%04d%02d", ttmFrom.getYear(), ttmFrom.getMonthValue());
        String toKey = String.format("%04d%02d", ttmTo.getYear(), ttmTo.getMonthValue());

        // Build skeleton map for all 12 months so we return rows even when data is missing
        Map<String, BudgetActualGapMonthlyDTO> monthMap = new LinkedHashMap<>();
        for (int i = 0; i < 12; i++) {
            LocalDate month = ttmFrom.plusMonths(i);
            String key = String.format("%04d%02d", month.getYear(), month.getMonthValue());
            String label = Month.of(month.getMonthValue())
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + month.getYear();
            monthMap.put(key, new BudgetActualGapMonthlyDTO(
                    key, month.getYear(), month.getMonthValue(), label,
                    0.0, 0.0, 0.0, 0.0, 0.0));
        }

        // Budget hours per month from fact_budget_day
        String budgetSql = """
            SELECT CONCAT(LPAD(bd.year, 4, '0'), LPAD(bd.month, 2, '0')) AS month_key,
                   bd.year, bd.month AS month_number,
                   SUM(bd.budgetHours) AS budget_hours
            FROM fact_budget_day bd
            WHERE bd.useruuid = :userId
              AND bd.document_date >= :ttmFrom AND bd.document_date < :ttmTo
            GROUP BY bd.year, bd.month
            ORDER BY month_key
            """;

        @SuppressWarnings("unchecked")
        List<Tuple> budgetRows = em.createNativeQuery(budgetSql, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("ttmFrom", ttmFrom)
                .setParameter("ttmTo", ttmTo)
                .getResultList();

        for (Tuple row : budgetRows) {
            String key = (String) row.get("month_key");
            var dto = monthMap.get(key);
            if (dto != null) {
                dto.setBudgetHours(((Number) row.get("budget_hours")).doubleValue());
            }
        }

        // Actual hours and net available from fact_user_utilization_mat
        String actualSql = """
            SELECT fum.month_key, fum.year, fum.month_number,
                   fum.billable_hours AS actual_hours,
                   fum.net_available_hours
            FROM fact_user_utilization_mat fum
            WHERE fum.user_id = :userId
              AND fum.month_key >= :fromKey AND fum.month_key < :toKey
            ORDER BY fum.month_key
            """;

        @SuppressWarnings("unchecked")
        List<Tuple> actualRows = em.createNativeQuery(actualSql, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .getResultList();

        for (Tuple row : actualRows) {
            String key = (String) row.get("month_key");
            var dto = monthMap.get(key);
            if (dto != null) {
                dto.setActualHours(((Number) row.get("actual_hours")).doubleValue());
                dto.setNetAvailableHours(((Number) row.get("net_available_hours")).doubleValue());
            }
        }

        // Compute utilization percentages
        List<BudgetActualGapMonthlyDTO> results = new ArrayList<>(monthMap.values());
        for (var dto : results) {
            double net = dto.getNetAvailableHours();
            if (net > 0) {
                dto.setBudgetUtilizationPct(dto.getBudgetHours() / net * 100.0);
                dto.setActualUtilizationPct(dto.getActualHours() / net * 100.0);
            }
        }

        log.debugf("getBudgetActualGapMonthly: userId=%s, returned %s rows", userId, results.size());

        return results;
    }

    /**
     * Returns effective practices set, defaulting to all 5 core practices if null/empty.
     */
    private Set<String> effectivePractices(Set<String> practices) {
        return (practices != null && !practices.isEmpty()) ? practices : DEFAULT_PRACTICES;
    }

    /**
     * Safely converts a Tuple date value to LocalDate.
     * Hibernate 6 / MariaDB may return java.sql.Date, java.time.LocalDate, or java.sql.Timestamp.
     */
    private LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate ld) return ld;
        if (value instanceof java.sql.Date sd) return sd.toLocalDate();
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        if (value instanceof java.util.Date d) return d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return null;
    }
}
