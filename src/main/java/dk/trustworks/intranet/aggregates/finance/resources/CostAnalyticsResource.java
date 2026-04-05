package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.analytics.*;
import dk.trustworks.intranet.aggregates.finance.services.analytics.CareerBandMapper;
import dk.trustworks.intranet.aggregates.finance.services.analytics.ProfitabilityProvider;
import dk.trustworks.intranet.aggregates.finance.services.analytics.SalaryAnalyticsProvider;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.toMonthKey;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST resource for unified cost analytics endpoints.
 *
 * Replaces raw SQL in Next.js BFF routes with proper Quarkus service layer.
 * All calculation logic lives in composable provider beans.
 */
@JBossLog
@Tag(name = "finance")
@Path("/finance/analytics")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"dashboard:read"})
public class CostAnalyticsResource {

    @Inject
    SalaryAnalyticsProvider salaryAnalyticsProvider;

    @Inject
    ProfitabilityProvider profitabilityProvider;

    @Inject
    EntityManager em;

    /**
     * Average salary per career band per month (18-month trailing window).
     * Replaces BFF route: /api/cxo/cost/salary-development
     */
    @GET
    @Path("/salary-by-band")
    public List<SalaryByBandDTO> getAvgSalaryByBand(
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

        return salaryAnalyticsProvider.getAvgSalaryByBand(fromDate, toDate, companyIds.isEmpty() ? null : companyIds);
    }

    /**
     * Total salary per career band per month (18-month trailing window).
     * Replaces BFF route: /api/executive/total-salary-development
     */
    @GET
    @Path("/total-salary-by-band")
    public List<SalaryByBandDTO> getTotalSalaryByBand(
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

        return salaryAnalyticsProvider.getTotalSalaryByBand(fromDate, toDate, companyIds.isEmpty() ? null : companyIds);
    }

    /**
     * Monthly salary-to-revenue ratio (18-month trailing).
     * Replaces BFF route: /api/cxo/cost/salary-cost-ratio
     */
    @GET
    @Path("/salary-cost-ratio")
    public List<SalaryCostRatioDTO> getSalaryCostRatio(
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

        return salaryAnalyticsProvider.getSalaryCostRatio(fromDate, toDate, companyIds.isEmpty() ? null : companyIds);
    }

    /**
     * Top 10 consultants by expense amount (trailing 12 months).
     * Replaces BFF route: /api/executive/top-expense-consultants
     *
     * Excludes training/conference expenses (accountname LIKE 'Kursus/udd/konferencer%').
     * Only verified expenses (status IN VERIFIED_BOOKED, VERIFIED_UNBOOKED).
     */
    @GET
    @Path("/top-expense-consultants")
    public List<TopExpenseConsultantDTO> getTopExpenseConsultants(
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusMonths(11).withDayOfMonth(1);

        // Query 1: Top 10 by total expense amount
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT e.useruuid, u.firstname, u.lastname, ");
        sql.append("  SUM(CAST(e.amount AS DECIMAL(12,2))) AS total_expenses, ");
        sql.append("  COUNT(*) AS expense_count ");
        sql.append("FROM expenses e ");
        sql.append("JOIN user u ON u.uuid = e.useruuid ");
        sql.append("WHERE e.status IN ('VERIFIED_BOOKED', 'VERIFIED_UNBOOKED') ");
        sql.append("  AND TRIM(e.accountname) NOT LIKE 'Kursus/udd/konferencer%' ");
        sql.append("  AND e.expensedate >= :fromDate AND e.expensedate <= :toDate ");
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND EXISTS (SELECT 1 FROM userstatus us2 WHERE us2.useruuid = e.useruuid ");
            sql.append("    AND us2.statusdate = (SELECT MAX(us3.statusdate) FROM userstatus us3 WHERE us3.useruuid = e.useruuid AND us3.statusdate <= CURDATE()) ");
            sql.append("    AND us2.company IN (:companyIds)) ");
        }
        sql.append("GROUP BY e.useruuid, u.firstname, u.lastname ");
        sql.append("ORDER BY total_expenses DESC LIMIT 10");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", today);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> topRows = query.getResultList();
        if (topRows.isEmpty()) return List.of();

        // Collect top 10 user UUIDs
        List<String> topUserIds = topRows.stream().map(r -> (String) r.get("useruuid")).toList();

        // Query 2: Driving category per consultant
        String catSql = "WITH ranked AS ( " +
                "  SELECT e.useruuid, e.accountname, " +
                "    SUM(CAST(e.amount AS DECIMAL(12,2))) AS cat_total, " +
                "    ROW_NUMBER() OVER (PARTITION BY e.useruuid ORDER BY SUM(CAST(e.amount AS DECIMAL(12,2))) DESC) AS rn " +
                "  FROM expenses e " +
                "  WHERE e.status IN ('VERIFIED_BOOKED', 'VERIFIED_UNBOOKED') " +
                "    AND e.useruuid IN (:topUserIds) " +
                "    AND e.expensedate >= :fromDate AND e.expensedate <= :toDate " +
                "  GROUP BY e.useruuid, e.accountname " +
                ") SELECT useruuid, accountname, cat_total FROM ranked WHERE rn = 1";

        var catQuery = em.createNativeQuery(catSql, Tuple.class);
        catQuery.setParameter("topUserIds", topUserIds);
        catQuery.setParameter("fromDate", fromDate);
        catQuery.setParameter("toDate", today);

        @SuppressWarnings("unchecked")
        List<Tuple> catRows = catQuery.getResultList();
        Map<String, String> topCategoryByUser = new HashMap<>();
        Map<String, Double> topCategoryAmountByUser = new HashMap<>();
        for (Tuple row : catRows) {
            String uuid = (String) row.get("useruuid");
            topCategoryByUser.put(uuid, (String) row.get("accountname"));
            topCategoryAmountByUser.put(uuid, ((Number) row.get("cat_total")).doubleValue());
        }

        // Build result
        List<TopExpenseConsultantDTO> result = new ArrayList<>();
        for (Tuple row : topRows) {
            String uuid = (String) row.get("useruuid");
            result.add(new TopExpenseConsultantDTO(
                    uuid,
                    (String) row.get("firstname"),
                    (String) row.get("lastname"),
                    ((Number) row.get("total_expenses")).doubleValue(),
                    ((Number) row.get("expense_count")).intValue(),
                    topCategoryByUser.getOrDefault(uuid, ""),
                    topCategoryAmountByUser.getOrDefault(uuid, 0.0)
            ));
        }
        return result;
    }

    /**
     * Intercompany cost distribution as Sankey diagram data.
     * Replaces BFF route: /api/cxo/cost/intercompany-distribution
     */
    @GET
    @Path("/intercompany-distribution")
    public IntercompanyDistributionDTO getIntercompanyDistribution(
            @QueryParam("fiscalYear") Integer fiscalYear) {

        LocalDate today = LocalDate.now();
        int fy = fiscalYear != null ? fiscalYear : (today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1);

        String fromKey = String.format("%04d07", fy);
        String toKey = String.format("%04d06", fy + 1);

        String sql = "SELECT d.origin_company AS origin_uuid, d.payer_company AS payer_uuid, " +
                "  c_origin.name AS origin_name, c_payer.name AS payer_name, " +
                "  SUM(d.intercompany_owe) AS total_owe " +
                "FROM fact_operating_cost_distribution_mat d " +
                "JOIN companies c_origin ON c_origin.uuid = d.origin_company " +
                "JOIN companies c_payer ON c_payer.uuid = d.payer_company " +
                "WHERE d.month_key >= :fromKey AND d.month_key <= :toKey " +
                "  AND d.origin_company != d.payer_company " +
                "  AND d.intercompany_owe > 0 " +
                "GROUP BY d.origin_company, d.payer_company, c_origin.name, c_payer.name " +
                "ORDER BY total_owe DESC";

        var query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        // Build unique nodes
        Map<String, String> nodeMap = new LinkedHashMap<>();
        List<IntercompanyDistributionDTO.SankeyLinkDTO> links = new ArrayList<>();

        for (Tuple row : rows) {
            String originUuid = (String) row.get("origin_uuid");
            String payerUuid = (String) row.get("payer_uuid");
            String originName = (String) row.get("origin_name");
            String payerName = (String) row.get("payer_name");
            double amount = ((Number) row.get("total_owe")).doubleValue();

            nodeMap.putIfAbsent(originUuid, originName);
            nodeMap.putIfAbsent(payerUuid, payerName);

            links.add(new IntercompanyDistributionDTO.SankeyLinkDTO(originName, payerName, Math.round(amount)));
        }

        List<IntercompanyDistributionDTO.SankeyNodeDTO> nodes = nodeMap.entrySet().stream()
                .map(e -> new IntercompanyDistributionDTO.SankeyNodeDTO(e.getValue(), e.getKey()))
                .toList();

        return new IntercompanyDistributionDTO(nodes, links, fy);
    }

    /**
     * Intercompany settlement table.
     * Replaces BFF route: /api/cxo/cost/intercompany-table
     */
    @GET
    @Path("/intercompany-table")
    public IntercompanyTableDTO getIntercompanyTable(
            @QueryParam("fiscalYear") Integer fiscalYear) {

        LocalDate today = LocalDate.now();
        int fy = fiscalYear != null ? fiscalYear : (today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1);

        String fromKey = String.format("%04d07", fy);
        String toKey = String.format("%04d06", fy + 1);

        String sql = "SELECT s.payer_company, s.receiver_company, " +
                "  c_payer.name AS payer_name, c_receiver.name AS receiver_name, " +
                "  SUM(s.expected_amount) AS gross_amount, " +
                "  SUM(s.actual_amount) AS net_settlement, " +
                "  MAX(s.settlement_status) AS settlement_status, " +
                "  COUNT(*) AS invoice_count " +
                "FROM fact_intercompany_settlement_mat s " +
                "JOIN companies c_payer ON c_payer.uuid = s.payer_company " +
                "JOIN companies c_receiver ON c_receiver.uuid = s.receiver_company " +
                "WHERE s.month_key >= :fromKey AND s.month_key <= :toKey " +
                "  AND s.payer_company != s.receiver_company " +
                "GROUP BY s.payer_company, s.receiver_company, c_payer.name, c_receiver.name " +
                "ORDER BY gross_amount DESC";

        var query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        List<IntercompanyTableDTO.IntercompanyTableRowDTO> resultRows = rows.stream().map(row ->
                new IntercompanyTableDTO.IntercompanyTableRowDTO(
                        (String) row.get("payer_company"),
                        (String) row.get("payer_name"),
                        (String) row.get("receiver_company"),
                        (String) row.get("receiver_name"),
                        ((Number) row.get("gross_amount")).doubleValue(),
                        ((Number) row.get("net_settlement")).doubleValue(),
                        (String) row.get("settlement_status"),
                        ((Number) row.get("invoice_count")).intValue()
                )).toList();

        return new IntercompanyTableDTO(resultRows, fy);
    }

    /**
     * Bonus pool projection (8% of eligible salary accumulated over FY).
     * Replaces BFF route: /api/cxo/cost/bonus-pool-projection
     */
    @GET
    @Path("/bonus-pool-projection")
    public BonusPoolProjectionDTO getBonusPoolProjection(
            @QueryParam("fiscalYear") Integer fiscalYear,
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        int fy = fiscalYear != null ? fiscalYear : (today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1);

        String fromKey = String.format("%04d07", fy);
        String toKey = String.format("%04d06", fy + 1);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT b.year, b.month, ");
        sql.append("  SUM(b.weighted_avg_salary) AS total_salary, ");
        sql.append("  COUNT(DISTINCT b.useruuid) AS headcount ");
        sql.append("FROM fact_tw_bonus_monthly_mat b ");
        sql.append("WHERE CONCAT(LPAD(b.year, 4, '0'), LPAD(b.month, 2, '0')) >= :fromKey ");
        sql.append("  AND CONCAT(LPAD(b.year, 4, '0'), LPAD(b.month, 2, '0')) <= :toKey ");
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND b.companyuuid IN (:companyIds) ");
        }
        sql.append("GROUP BY b.year, b.month ");
        sql.append("ORDER BY b.year, b.month");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        double accumulated = 0.0;
        List<BonusPoolProjectionDTO.BonusPoolMonthDTO> months = new ArrayList<>();

        for (Tuple row : rows) {
            int year = ((Number) row.get("year")).intValue();
            int month = ((Number) row.get("month")).intValue();
            double totalSalary = ((Number) row.get("total_salary")).doubleValue();
            int headcount = ((Number) row.get("headcount")).intValue();

            double monthlyContribution = totalSalary * 0.08;
            accumulated += monthlyContribution;

            String monthKey = toMonthKey(year, month);
            int fiscalMonthNumber = month >= 7 ? month - 6 : month + 6;

            months.add(new BonusPoolProjectionDTO.BonusPoolMonthDTO(
                    monthKey, year, month,
                    SalaryAnalyticsProvider.formatMonthLabel(year, month),
                    fy, fiscalMonthNumber,
                    Math.round(totalSalary), Math.round(accumulated),
                    Math.round(monthlyContribution), headcount
            ));
        }

        double projectedTotal = accumulated;
        return new BonusPoolProjectionDTO(months, fy, Math.round(projectedTotal));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Revenue vs Budget (Monthly, Trailing 12 Months)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Monthly actual revenue vs budget revenue for trailing 12 months.
     * Used by the Revenue Forecast (Actual vs Budget) chart.
     */
    @GET
    @Path("/revenue-vs-budget")
    public List<RevenueVsBudgetMonthDTO> getRevenueVsBudget(
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        String ttmStartKey = toMonthKey(today.minusMonths(11));
        String toKey = toMonthKey(today.getYear(), today.getMonthValue() + 1 > 12 ? 1 : today.getMonthValue() + 1);
        // Use next month as exclusive upper bound to include current month
        String currentMonthKey = toMonthKey(today);

        Set<String> companies = companyIds == null || companyIds.isEmpty() ? null : companyIds;

        // Actual revenue (grouped by month)
        String actualSql = buildActualRevenueSql(companies);
        // Reuse existing helper but with inclusive upper bound
        String actualSqlInclusive = actualSql.replace("r.month_key < :toKey", "r.month_key <= :toKey");
        var actualQuery = em.createNativeQuery(actualSqlInclusive, Tuple.class);
        actualQuery.setParameter("fromKey", ttmStartKey);
        actualQuery.setParameter("toKey", currentMonthKey);
        if (companies != null) actualQuery.setParameter("companyIds", companies);

        // Budget revenue (grouped by month)
        String budgetSql = buildBudgetRevenueSql(companies);
        var budgetQuery = em.createNativeQuery(budgetSql, Tuple.class);
        budgetQuery.setParameter("fromKey", ttmStartKey);
        budgetQuery.setParameter("toKey", currentMonthKey);
        if (companies != null) budgetQuery.setParameter("companyIds", companies);

        @SuppressWarnings("unchecked") List<Tuple> actualRows = actualQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> budgetRows = budgetQuery.getResultList();

        Map<String, Tuple> actualMap = indexByMonthKey(actualRows);
        Map<String, Tuple> budgetMap = new LinkedHashMap<>();
        for (Tuple t : budgetRows) budgetMap.put(t.get("month_key").toString(), t);

        // Merge all month keys
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(actualMap.keySet());
        allKeys.addAll(budgetMap.keySet());

        List<RevenueVsBudgetMonthDTO> result = new ArrayList<>();
        for (String key : allKeys) {
            Tuple a = actualMap.get(key);
            Tuple b = budgetMap.get(key);
            int year = a != null ? ((Number) a.get("year")).intValue() : ((Number) b.get("year")).intValue();
            int month = a != null ? ((Number) a.get("month_number")).intValue() : ((Number) b.get("month_number")).intValue();
            Double actualRev = a != null ? ((Number) a.get("net_revenue")).doubleValue() : null;
            Double budgetRev = b != null ? ((Number) b.get("budget_revenue")).doubleValue() : null;
            result.add(new RevenueVsBudgetMonthDTO(key, year, month,
                    SalaryAnalyticsProvider.formatMonthLabel(year, month), actualRev, budgetRev));
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Revenue per FTE (Monthly, Trailing 18 Months)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Monthly revenue per FTE for trailing 18 months.
     * Revenue per FTE = total net revenue / average headcount.
     */
    @GET
    @Path("/revenue-per-fte")
    public List<RevenuePerFteMonthDTO> getRevenuePerFteMonthly(
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        String fromKey = toMonthKey(today.minusMonths(17));
        String toKey = toMonthKey(today);

        Set<String> companies = companyIds == null || companyIds.isEmpty() ? null : companyIds;

        // Revenue per month (grouped, no cross-product)
        String revFilter = companies != null ? "AND r.company_id IN (:companyIds) " : "";
        String revSql = "SELECT r.month_key, r.year, r.month_number, " +
                "SUM(r.net_revenue_dkk) AS total_revenue " +
                "FROM fact_company_revenue_mat r " +
                "WHERE r.month_key >= :fromKey AND r.month_key <= :toKey " +
                revFilter +
                "GROUP BY r.month_key, r.year, r.month_number ORDER BY r.month_key";

        var revQuery = em.createNativeQuery(revSql, Tuple.class);
        revQuery.setParameter("fromKey", fromKey);
        revQuery.setParameter("toKey", toKey);
        if (companies != null) revQuery.setParameter("companyIds", companies);

        // Headcount per month (grouped, no cross-product)
        String empFilter = companies != null ? "AND e.company_id IN (:companyIds) " : "";
        String empSql = "SELECT e.month_key, SUM(e.average_headcount) AS total_headcount " +
                "FROM fact_employee_monthly_mat e " +
                "WHERE e.month_key >= :fromKey AND e.month_key <= :toKey " +
                empFilter +
                "GROUP BY e.month_key ORDER BY e.month_key";

        var empQuery = em.createNativeQuery(empSql, Tuple.class);
        empQuery.setParameter("fromKey", fromKey);
        empQuery.setParameter("toKey", toKey);
        if (companies != null) empQuery.setParameter("companyIds", companies);

        @SuppressWarnings("unchecked") List<Tuple> revRows = revQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> empRows = empQuery.getResultList();

        Map<String, Double> empMap = new LinkedHashMap<>();
        for (Tuple t : empRows) {
            empMap.put(t.get("month_key").toString(), ((Number) t.get("total_headcount")).doubleValue());
        }

        List<RevenuePerFteMonthDTO> result = new ArrayList<>();
        for (Tuple rev : revRows) {
            String key = rev.get("month_key").toString();
            int year = ((Number) rev.get("year")).intValue();
            int month = ((Number) rev.get("month_number")).intValue();
            double totalRev = ((Number) rev.get("total_revenue")).doubleValue();
            double headcount = empMap.getOrDefault(key, 0.0);
            Double revPerFte = headcount > 0 ? (double) Math.round(totalRev / headcount) : null;
            result.add(new RevenuePerFteMonthDTO(key, year, month,
                    SalaryAnalyticsProvider.formatMonthLabel(year, month),
                    totalRev, headcount, revPerFte));
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Salary Equality (Chart 2.5)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Gender pay equality analysis by practice and career band.
     * Replaces BFF route: /api/executive/salary-equality
     *
     * Privacy rule: suppress groups where either gender has fewer than 3 individuals.
     */
    @GET
    @Path("/salary-equality")
    public SalaryEqualityDTO getSalaryEquality(
            @QueryParam("companyIds") Set<String> companyIds) {

        String currentMonthKey = toMonthKey(LocalDate.now());

        // Find latest complete month
        String latestMonthSql = "SELECT MAX(month_key) FROM fact_salary_monthly WHERE month_key < :currentKey";
        String latestKey = (String) em.createNativeQuery(latestMonthSql)
                .setParameter("currentKey", currentMonthKey)
                .getSingleResult();
        if (latestKey == null) return new SalaryEqualityDTO(List.of(), List.of(), currentMonthKey);

        // By practice
        List<SalaryEqualityDTO.SalaryEqualityGroupDTO> byPractice = querySalaryEqualityGroups(
                "fsm.practice_id", "fsm.practice_id", latestKey, companyIds, null);

        // By career band — requires JOIN with user_career_level since fact_salary_monthly has no career_band column
        String careerLevelJoin = "JOIN user_career_level ucl ON ucl.useruuid = fsm.useruuid "
                + "AND ucl.active_from = (SELECT MAX(ucl2.active_from) FROM user_career_level ucl2 "
                + "WHERE ucl2.useruuid = fsm.useruuid AND ucl2.active_from <= LAST_DAY(STR_TO_DATE(CONCAT(fsm.month_key, '01'), '%Y%m%d'))) ";
        String bandCase = CareerBandMapper.toSqlCase("ucl.career_level");
        List<SalaryEqualityDTO.SalaryEqualityGroupDTO> byCareerBand = querySalaryEqualityGroups(
                bandCase, bandCase, latestKey, companyIds, careerLevelJoin);

        return new SalaryEqualityDTO(byPractice, byCareerBand, latestKey);
    }

    /**
     * Monthly cost per billable FTE (salary + OPEX components).
     * Replaces BFF route: /api/cxo/cost/cost-per-consultant
     */
    @GET
    @Path("/cost-per-fte")
    public List<CostPerFteDTO> getCostPerFte(
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

        return profitabilityProvider.getCostPerFte(fromDate, toDate, companyIds.isEmpty() ? null : companyIds);
    }

    // ═════════════════════════════════════════════════════════════════════
    // Revenue-Cost Forecast (Charts 1.2 / 1.3)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Revenue vs. cost trend with TTM actuals and forward-looking forecast.
     * Replaces BFF route: /api/executive/revenue-cost-trend
     *
     * <p>Returns:
     * <ul>
     *   <li>12 completed months of actual data (registered revenue, invoice revenue, total cost)</li>
     *   <li>Forecast months from current month through fiscal year end
     *       (budget revenue + weighted pipeline vs. flat TTM average cost)</li>
     * </ul>
     */
    @GET
    @Path("/revenue-cost-forecast")
    public List<RevenueCostForecastDTO> getRevenueCostForecast(
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        String ttmStartKey = toMonthKey(today.minusMonths(12));
        String currentMonthKey = toMonthKey(today);

        Set<String> companies = companyIds == null || companyIds.isEmpty() ? null : companyIds;

        // ── TTM actual data (12 completed months) ────────────────────────

        // Q1: Registered revenue from fact_user_day
        String registeredRevenueSql = buildRegisteredRevenueSql(companies);
        var regQuery = em.createNativeQuery(registeredRevenueSql, Tuple.class);
        regQuery.setParameter("ttmStart", ttmStartKey);
        regQuery.setParameter("currentMonth", currentMonthKey);
        if (companies != null) regQuery.setParameter("companyIds", companies);

        // Q2: Invoice revenue from fact_company_revenue_mat
        String invoiceRevenueSql = buildInvoiceRevenueSql(companies);
        var invQuery = em.createNativeQuery(invoiceRevenueSql, Tuple.class);
        invQuery.setParameter("ttmStart", ttmStartKey);
        invQuery.setParameter("currentMonth", currentMonthKey);
        if (companies != null) invQuery.setParameter("companyIds", companies);

        // Q3: Total cost (OPEX + SALARIES) from fact_opex_mat
        String costSql = buildTotalCostSql(companies);
        var costQuery = em.createNativeQuery(costSql, Tuple.class);
        costQuery.setParameter("ttmStart", ttmStartKey);
        costQuery.setParameter("currentMonth", currentMonthKey);
        if (companies != null) costQuery.setParameter("companyIds", companies);

        @SuppressWarnings("unchecked") List<Tuple> regRows = regQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> invRows = invQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> costRows = costQuery.getResultList();

        // Index by month_key
        Map<String, Tuple> regMap = indexByMonthKey(regRows);
        Map<String, Tuple> invMap = indexByMonthKey(invRows);
        Map<String, Tuple> costMap = indexByMonthKey(costRows);

        // Collect all month keys
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(regMap.keySet());
        allKeys.addAll(invMap.keySet());
        allKeys.addAll(costMap.keySet());

        // Build actual months
        List<RevenueCostForecastDTO> result = new ArrayList<>();
        double totalCostSum = 0;

        for (String key : allKeys) {
            int year = resolveYear(regMap.get(key), invMap.get(key), costMap.get(key));
            int month = resolveMonth(regMap.get(key), invMap.get(key), costMap.get(key));
            double regRev = numericValue(regMap.get(key), "registered_revenue");
            double invRev = numericValue(invMap.get(key), "net_revenue");
            double cost = numericValue(costMap.get(key), "total_cost");
            totalCostSum += Math.round(cost);

            result.add(new RevenueCostForecastDTO(
                    key, year, month,
                    SalaryAnalyticsProvider.formatMonthLabel(year, month),
                    Math.round(regRev), Math.round(invRev), Math.round(cost),
                    null, false));
        }

        // Flat TTM average cost
        Double flatAvgCost = !result.isEmpty() ? Math.round(totalCostSum / result.size()) * 1.0 : null;
        List<RevenueCostForecastDTO> withAvg = result.stream()
                .map(r -> new RevenueCostForecastDTO(r.monthKey(), r.year(), r.monthNumber(),
                        r.monthLabel(), r.registeredRevenueDkk(), r.invoiceRevenueDkk(),
                        r.totalCostDkk(), flatAvgCost, false))
                .collect(Collectors.toCollection(ArrayList::new));

        // ── Forecast months (current through FY end) ─────────────────────

        int currentYear = today.getYear();
        int currentMonth = today.getMonthValue();
        int fyEndYear = currentMonth >= 7 ? currentYear + 1 : currentYear;
        String fyEndKey = toMonthKey(fyEndYear, 6);

        if (currentMonthKey.compareTo(fyEndKey) <= 0) {
            double forecastMonthlyCost = flatAvgCost != null ? flatAvgCost : 0;

            // Q4: Budget revenue
            String budgetSql = buildBudgetRevenueSql(companies);
            var budgetQuery = em.createNativeQuery(budgetSql, Tuple.class);
            budgetQuery.setParameter("fromKey", currentMonthKey);
            budgetQuery.setParameter("toKey", fyEndKey);
            if (companies != null) budgetQuery.setParameter("companyIds", companies);

            // Q5: Pipeline (excl. WON)
            String pipelineSql = buildPipelineSql(companies);
            var pipelineQuery = em.createNativeQuery(pipelineSql, Tuple.class);
            pipelineQuery.setParameter("fromKey", currentMonthKey);
            pipelineQuery.setParameter("toKey", fyEndKey);
            if (companies != null) pipelineQuery.setParameter("companyIds", companies);

            @SuppressWarnings("unchecked") List<Tuple> budgetRows = budgetQuery.getResultList();
            @SuppressWarnings("unchecked") List<Tuple> pipelineRows = pipelineQuery.getResultList();

            Map<String, Double> budgetMap = new HashMap<>();
            for (Tuple row : budgetRows) {
                budgetMap.put((String) row.get("month_key"), numericValue(row, "budget_revenue"));
            }
            Map<String, Double> pipelineMap = new HashMap<>();
            for (Tuple row : pipelineRows) {
                pipelineMap.put((String) row.get("expected_revenue_month_key"), numericValue(row, "weighted_pipeline"));
            }

            // Generate forecast month keys
            int fYear = currentYear;
            int fMonth = currentMonth;
            while (toMonthKey(fYear, fMonth).compareTo(fyEndKey) <= 0) {
                String fKey = toMonthKey(fYear, fMonth);
                double budgetRev = budgetMap.getOrDefault(fKey, 0.0);
                double pipelineRev = pipelineMap.getOrDefault(fKey, 0.0);
                double forecastRevenue = Math.round(budgetRev + pipelineRev);

                withAvg.add(new RevenueCostForecastDTO(
                        fKey, fYear, fMonth,
                        SalaryAnalyticsProvider.formatMonthLabel(fYear, fMonth),
                        forecastRevenue, forecastRevenue, forecastMonthlyCost,
                        flatAvgCost, true));

                fMonth++;
                if (fMonth > 12) { fMonth = 1; fYear++; }
            }
        }

        return withAvg;
    }

    // ═════════════════════════════════════════════════════════════════════
    // EBITDA Forecast (Chart 1.4)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Full fiscal year EBITDA forecast with actuals and projections.
     * Replaces BFF route: /api/executive/ebitda-forecast
     *
     * <p>Methodology:
     * <ul>
     *   <li>Past months: actual revenue, delivery cost, OPEX</li>
     *   <li>Future months: budget + pipeline revenue, TTM delivery cost ratio, TTM avg OPEX</li>
     *   <li>EBITDA = revenue - delivery cost - OPEX, accumulated across FY</li>
     * </ul>
     */
    @GET
    @Path("/ebitda-forecast")
    public List<EbitdaForecastDTO> getEbitdaForecast(
            @QueryParam("companyIds") Set<String> companyIds) {

        LocalDate today = LocalDate.now();
        int currentYear = today.getYear();
        int currentMonth = today.getMonthValue();
        String currentMonthKey = toMonthKey(today);

        // Fiscal year boundaries (July 1 - June 30)
        int fyStartYear = currentMonth >= 7 ? currentYear : currentYear - 1;
        String fyStartKey = toMonthKey(fyStartYear, 7);
        String fyEndKey = toMonthKey(fyStartYear + 1, 6);

        // TTM start (12 months before current month)
        String ttmStartKey = toMonthKey(today.minusMonths(12));

        Set<String> companies = companyIds == null || companyIds.isEmpty() ? null : companyIds;

        // ── Actual data queries ──────────────────────────────────────────

        // Q1: Actual revenue from fact_company_revenue_mat (FY start to current)
        String actualRevSql = buildActualRevenueSql(companies);
        var actualRevQuery = em.createNativeQuery(actualRevSql, Tuple.class);
        actualRevQuery.setParameter("fromKey", fyStartKey);
        actualRevQuery.setParameter("toKey", currentMonthKey);
        if (companies != null) actualRevQuery.setParameter("companyIds", companies);

        // Q2: Actual delivery cost from fact_project_financials_mat
        String actualDelCostSql = buildActualDeliveryCostSql(companies);
        var actualDelCostQuery = em.createNativeQuery(actualDelCostSql, Tuple.class);
        actualDelCostQuery.setParameter("fromKey", fyStartKey);
        actualDelCostQuery.setParameter("toKey", currentMonthKey);
        if (companies != null) actualDelCostQuery.setParameter("companyIds", companies);

        // Q3: Actual OPEX from fact_opex_mat (cost_type = 'OPEX' only)
        String actualOpexSql = buildActualOpexSql(companies);
        var actualOpexQuery = em.createNativeQuery(actualOpexSql, Tuple.class);
        actualOpexQuery.setParameter("fromKey", fyStartKey);
        actualOpexQuery.setParameter("toKey", currentMonthKey);
        if (companies != null) actualOpexQuery.setParameter("companyIds", companies);

        // ── TTM ratio queries ────────────────────────────────────────────

        // Q4: TTM delivery cost ratio
        String ttmDelCostSql = buildTtmDeliveryCostRatioSql(companies);
        var ttmDelCostQuery = em.createNativeQuery(ttmDelCostSql, Tuple.class);
        ttmDelCostQuery.setParameter("ttmStart", ttmStartKey);
        ttmDelCostQuery.setParameter("currentMonth", currentMonthKey);
        if (companies != null) ttmDelCostQuery.setParameter("companyIds", companies);

        // Q5: TTM average monthly OPEX
        String ttmOpexSql = buildTtmOpexSql(companies);
        var ttmOpexQuery = em.createNativeQuery(ttmOpexSql, Tuple.class);
        ttmOpexQuery.setParameter("ttmStart", ttmStartKey);
        ttmOpexQuery.setParameter("currentMonth", currentMonthKey);
        if (companies != null) ttmOpexQuery.setParameter("companyIds", companies);

        // ── Forecast data queries ────────────────────────────────────────

        // Q6: Budget revenue
        String budgetSql = buildBudgetRevenueSql(companies);
        var budgetQuery = em.createNativeQuery(budgetSql, Tuple.class);
        budgetQuery.setParameter("fromKey", currentMonthKey);
        budgetQuery.setParameter("toKey", fyEndKey);
        if (companies != null) budgetQuery.setParameter("companyIds", companies);

        // Q7: Pipeline (excl. WON)
        String pipelineSql = buildPipelineSql(companies);
        var pipelineQuery = em.createNativeQuery(pipelineSql, Tuple.class);
        pipelineQuery.setParameter("fromKey", currentMonthKey);
        pipelineQuery.setParameter("toKey", fyEndKey);
        if (companies != null) pipelineQuery.setParameter("companyIds", companies);

        // Execute all queries
        @SuppressWarnings("unchecked") List<Tuple> actualRevRows = actualRevQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> actualDelCostRows = actualDelCostQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> actualOpexRows = actualOpexQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> ttmDelCostRows = ttmDelCostQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> ttmOpexRows = ttmOpexQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> budgetRows = budgetQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> pipelineRows = pipelineQuery.getResultList();

        // ── Compute TTM ratios ───────────────────────────────────────────

        double ttmDeliveryCostRatio = 0.5; // fallback
        if (!ttmDelCostRows.isEmpty()) {
            Tuple ttmDC = ttmDelCostRows.get(0);
            double totalRev = numericValue(ttmDC, "total_revenue");
            if (totalRev > 0) {
                ttmDeliveryCostRatio = numericValue(ttmDC, "total_delivery_cost") / totalRev;
            }
        }

        double ttmAvgMonthlyOpex = 0;
        if (!ttmOpexRows.isEmpty()) {
            Tuple ttmOp = ttmOpexRows.get(0);
            int monthsCount = (int) numericValue(ttmOp, "months_count");
            if (monthsCount > 0) {
                ttmAvgMonthlyOpex = numericValue(ttmOp, "total_opex") / monthsCount;
            }
        }

        // ── Index data by month_key ──────────────────────────────────────

        Map<String, Double> actualRevMap = new HashMap<>();
        for (Tuple row : actualRevRows) actualRevMap.put((String) row.get("month_key"), numericValue(row, "net_revenue"));

        Map<String, Double> actualDelCostMap = new HashMap<>();
        for (Tuple row : actualDelCostRows) actualDelCostMap.put((String) row.get("month_key"), numericValue(row, "delivery_cost"));

        Map<String, Double> actualOpexMap = new HashMap<>();
        for (Tuple row : actualOpexRows) actualOpexMap.put((String) row.get("month_key"), numericValue(row, "opex_amount"));

        Map<String, Double> budgetMap = new HashMap<>();
        for (Tuple row : budgetRows) budgetMap.put((String) row.get("month_key"), numericValue(row, "budget_revenue"));

        Map<String, Double> pipelineMap = new HashMap<>();
        for (Tuple row : pipelineRows) pipelineMap.put((String) row.get("expected_revenue_month_key"), numericValue(row, "weighted_pipeline"));

        // ── Build result: 12 fiscal months ───────────────────────────────

        List<EbitdaForecastDTO> result = new ArrayList<>();
        double accumulatedEbitda = 0;

        for (var fm : generateFiscalMonthKeys(fyStartYear)) {
            boolean isActual = fm.monthKey().compareTo(currentMonthKey) < 0;

            double revenueDkk;
            double directDeliveryCostDkk;
            double opexDkk;

            if (isActual) {
                revenueDkk = actualRevMap.getOrDefault(fm.monthKey(), 0.0);
                directDeliveryCostDkk = actualDelCostMap.getOrDefault(fm.monthKey(), 0.0);
                opexDkk = actualOpexMap.getOrDefault(fm.monthKey(), 0.0);
            } else {
                double budgetRev = budgetMap.getOrDefault(fm.monthKey(), 0.0);
                double pipelineRev = pipelineMap.getOrDefault(fm.monthKey(), 0.0);
                revenueDkk = budgetRev + pipelineRev;
                directDeliveryCostDkk = revenueDkk * ttmDeliveryCostRatio;
                opexDkk = ttmAvgMonthlyOpex;
            }

            double ebitdaDkk = revenueDkk - directDeliveryCostDkk - opexDkk;
            accumulatedEbitda += ebitdaDkk;

            int fiscalMonthNum = fm.calendarMonth() >= 7 ? fm.calendarMonth() - 6 : fm.calendarMonth() + 6;

            result.add(new EbitdaForecastDTO(
                    fm.monthKey(), fm.year(), fm.calendarMonth(),
                    SalaryAnalyticsProvider.formatMonthLabel(fm.year(), fm.calendarMonth()),
                    fiscalMonthNum,
                    Math.round(revenueDkk), Math.round(directDeliveryCostDkk),
                    Math.round(opexDkk), Math.round(ebitdaDkk),
                    Math.round(accumulatedEbitda), isActual));
        }

        return result;
    }

    // ═════════════════════════════════════════════════════════════════════
    // SQL builders (shared across forecast endpoints)
    // ═════════════════════════════════════════════════════════════════════

    /** Registered revenue from fact_user_day (TTM window). */
    private static String buildRegisteredRevenueSql(Set<String> companies) {
        String filter = companies != null ? "AND d.companyuuid IN (:companyIds) " : "";
        return "SELECT CONCAT(d.year, LPAD(d.month, 2, '0')) AS month_key, " +
                "d.year, d.month AS month_number, " +
                "SUM(d.registered_amount) AS registered_revenue " +
                "FROM fact_user_day d " +
                "WHERE d.consultant_type = 'CONSULTANT' " +
                "AND d.status_type NOT IN ('TERMINATED', 'NON_PAY_LEAVE') " +
                "AND CONCAT(d.year, LPAD(d.month, 2, '0')) >= :ttmStart " +
                "AND CONCAT(d.year, LPAD(d.month, 2, '0')) < :currentMonth " +
                filter +
                "GROUP BY month_key, d.year, d.month ORDER BY month_key";
    }

    /** Invoice revenue from fact_company_revenue_mat (TTM window). */
    private static String buildInvoiceRevenueSql(Set<String> companies) {
        String filter = companies != null ? "AND r.company_id IN (:companyIds) " : "";
        return "SELECT r.month_key, r.year, r.month_number, " +
                "SUM(r.net_revenue_dkk) AS net_revenue " +
                "FROM fact_company_revenue_mat r " +
                "WHERE r.month_key >= :ttmStart AND r.month_key < :currentMonth " +
                filter +
                "GROUP BY r.month_key, r.year, r.month_number ORDER BY r.month_key";
    }

    /** Total cost (OPEX + SALARIES) from fact_opex_mat (TTM window). */
    private static String buildTotalCostSql(Set<String> companies) {
        String filter = companies != null ? "AND o.company_id IN (:companyIds) " : "";
        return "SELECT o.month_key, o.year, o.month_number, " +
                "SUM(o.opex_amount_dkk) AS total_cost " +
                "FROM fact_opex_mat o " +
                "WHERE o.cost_type IN ('OPEX', 'SALARIES') " +
                "AND o.month_key >= :ttmStart AND o.month_key < :currentMonth " +
                filter +
                "GROUP BY o.month_key, o.year, o.month_number ORDER BY o.month_key";
    }

    /** Actual revenue from fact_company_revenue_mat (FY window). */
    private static String buildActualRevenueSql(Set<String> companies) {
        String filter = companies != null ? "AND r.company_id IN (:companyIds) " : "";
        return "SELECT r.month_key, r.year, r.month_number, " +
                "SUM(r.net_revenue_dkk) AS net_revenue " +
                "FROM fact_company_revenue_mat r " +
                "WHERE r.month_key >= :fromKey AND r.month_key < :toKey " +
                filter +
                "GROUP BY r.month_key, r.year, r.month_number ORDER BY r.month_key";
    }

    /** Actual delivery cost from fact_project_financials_mat (FY window). */
    private static String buildActualDeliveryCostSql(Set<String> companies) {
        String filter = companies != null ? "AND p.companyuuid IN (:companyIds) " : "";
        return "SELECT p.month_key, p.year, p.month_number, " +
                "SUM(p.direct_delivery_cost_dkk) AS delivery_cost " +
                "FROM fact_project_financials_mat p " +
                "WHERE p.month_key >= :fromKey AND p.month_key < :toKey " +
                filter +
                "GROUP BY p.month_key, p.year, p.month_number ORDER BY p.month_key";
    }

    /** Actual OPEX from fact_opex_mat (cost_type='OPEX' only, FY window). */
    private static String buildActualOpexSql(Set<String> companies) {
        String filter = companies != null ? "AND o.company_id IN (:companyIds) " : "";
        return "SELECT o.month_key, o.year, o.month_number, " +
                "SUM(o.opex_amount_dkk) AS opex_amount " +
                "FROM fact_opex_mat o " +
                "WHERE o.cost_type = 'OPEX' " +
                "AND o.month_key >= :fromKey AND o.month_key < :toKey " +
                filter +
                "GROUP BY o.month_key, o.year, o.month_number ORDER BY o.month_key";
    }

    /** TTM delivery cost ratio (trailing 12 months). */
    private static String buildTtmDeliveryCostRatioSql(Set<String> companies) {
        String filter = companies != null ? "AND p.companyuuid IN (:companyIds) " : "";
        return "SELECT SUM(p.direct_delivery_cost_dkk) AS total_delivery_cost, " +
                "SUM(p.recognized_revenue_dkk) AS total_revenue " +
                "FROM fact_project_financials_mat p " +
                "WHERE p.month_key >= :ttmStart AND p.month_key < :currentMonth " +
                filter;
    }

    /** TTM average monthly OPEX (trailing 12 months). */
    private static String buildTtmOpexSql(Set<String> companies) {
        String filter = companies != null ? "AND o.company_id IN (:companyIds) " : "";
        return "SELECT COUNT(DISTINCT o.month_key) AS months_count, " +
                "SUM(o.opex_amount_dkk) AS total_opex " +
                "FROM fact_opex_mat o " +
                "WHERE o.cost_type = 'OPEX' " +
                "AND o.month_key >= :ttmStart AND o.month_key < :currentMonth " +
                filter;
    }

    /** Budget revenue from fact_revenue_budget_mat (forecast window). */
    private static String buildBudgetRevenueSql(Set<String> companies) {
        String filter = companies != null ? "AND b.company_id IN (:companyIds) " : "";
        return "SELECT b.month_key, b.year, b.month_number, " +
                "SUM(b.budget_revenue_dkk) AS budget_revenue " +
                "FROM fact_revenue_budget_mat b " +
                "WHERE b.month_key >= :fromKey AND b.month_key <= :toKey " +
                filter +
                "GROUP BY b.month_key, b.year, b.month_number ORDER BY b.month_key";
    }

    /** Weighted pipeline (excl. WON) from fact_pipeline (forecast window). */
    private static String buildPipelineSql(Set<String> companies) {
        String filter = companies != null ? "AND pl.company_id IN (:companyIds) " : "";
        return "SELECT pl.expected_revenue_month_key, pl.year, pl.month_number, " +
                "SUM(pl.weighted_pipeline_dkk) AS weighted_pipeline " +
                "FROM fact_pipeline pl " +
                "WHERE pl.stage_id NOT IN ('WON') " +
                "AND pl.expected_revenue_month_key >= :fromKey " +
                "AND pl.expected_revenue_month_key <= :toKey " +
                filter +
                "GROUP BY pl.expected_revenue_month_key, pl.year, pl.month_number " +
                "ORDER BY pl.expected_revenue_month_key";
    }

    // ═════════════════════════════════════════════════════════════════════
    // Fiscal year helpers
    // ═════════════════════════════════════════════════════════════════════

    /** Value object for a month within a fiscal year. */
    private record FiscalMonth(String monthKey, int year, int calendarMonth) {}

    /**
     * Generate all 12 month keys for a fiscal year (Jul through Jun).
     * @param fyStartYear the calendar year in which July falls
     */
    private static List<FiscalMonth> generateFiscalMonthKeys(int fyStartYear) {
        List<FiscalMonth> months = new ArrayList<>(12);
        for (int fmn = 1; fmn <= 12; fmn++) {
            int calMonth = fmn <= 6 ? fmn + 6 : fmn - 6;
            int calYear = fmn <= 6 ? fyStartYear : fyStartYear + 1;
            months.add(new FiscalMonth(toMonthKey(calYear, calMonth), calYear, calMonth));
        }
        return months;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Tuple extraction helpers
    // ═════════════════════════════════════════════════════════════════════

    private static Map<String, Tuple> indexByMonthKey(List<Tuple> rows) {
        Map<String, Tuple> map = new LinkedHashMap<>();
        for (Tuple row : rows) map.put((String) row.get("month_key"), row);
        return map;
    }

    private static double numericValue(Tuple row, String column) {
        if (row == null) return 0;
        Object val = row.get(column);
        return val instanceof Number n ? n.doubleValue() : 0;
    }

    private static int resolveYear(Tuple... sources) {
        for (Tuple t : sources) {
            if (t != null && t.get("year") != null) return ((Number) t.get("year")).intValue();
        }
        return 0;
    }

    private static int resolveMonth(Tuple... sources) {
        for (Tuple t : sources) {
            if (t != null && t.get("month_number") != null) return ((Number) t.get("month_number")).intValue();
        }
        return 0;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Salary equality helpers
    // ═════════════════════════════════════════════════════════════════════

    private List<SalaryEqualityDTO.SalaryEqualityGroupDTO> querySalaryEqualityGroups(
            String groupExpr, String labelExpr, String monthKey, Set<String> companyIds,
            String extraJoin) {

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(groupExpr).append(" AS group_id, ");
        sql.append(labelExpr).append(" AS group_label, ");
        sql.append("  AVG(CASE WHEN u.gender = 'MALE' THEN fsm.effective_salary END) AS male_avg, ");
        sql.append("  AVG(CASE WHEN u.gender = 'FEMALE' THEN fsm.effective_salary END) AS female_avg, ");
        sql.append("  COUNT(CASE WHEN u.gender = 'MALE' THEN 1 END) AS male_count, ");
        sql.append("  COUNT(CASE WHEN u.gender = 'FEMALE' THEN 1 END) AS female_count ");
        sql.append("FROM fact_salary_monthly fsm ");
        sql.append("JOIN user u ON u.uuid = fsm.useruuid ");
        if (extraJoin != null) {
            sql.append(extraJoin);
        }
        sql.append("WHERE fsm.month_key = :monthKey ");
        sql.append("  AND u.gender IN ('MALE', 'FEMALE') ");
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND fsm.companyuuid IN (:companyIds) ");
        }
        sql.append("GROUP BY group_id, group_label ");
        sql.append("ORDER BY group_label");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("monthKey", monthKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        List<SalaryEqualityDTO.SalaryEqualityGroupDTO> result = new ArrayList<>();
        for (Tuple row : rows) {
            String groupId = row.get("group_id") != null ? row.get("group_id").toString() : "Unknown";
            String groupLabel = row.get("group_label") != null ? row.get("group_label").toString() : "Unknown";
            int maleCount = ((Number) row.get("male_count")).intValue();
            int femaleCount = ((Number) row.get("female_count")).intValue();

            boolean suppressed = maleCount < 3 || femaleCount < 3;

            Double maleAvg = !suppressed && row.get("male_avg") != null ? ((Number) row.get("male_avg")).doubleValue() : null;
            Double femaleAvg = !suppressed && row.get("female_avg") != null ? ((Number) row.get("female_avg")).doubleValue() : null;
            Double gapPct = (maleAvg != null && femaleAvg != null && maleAvg > 0)
                    ? ((maleAvg - femaleAvg) / maleAvg) * 100.0 : null;

            result.add(new SalaryEqualityDTO.SalaryEqualityGroupDTO(
                    groupId, groupLabel, maleAvg, femaleAvg,
                    maleCount, femaleCount, gapPct, suppressed
            ));
        }
        return result;
    }
}
