package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.dto.analytics.*;
import dk.trustworks.intranet.aggregates.finance.services.DistributionAwareOpexProvider;
import dk.trustworks.intranet.aggregates.finance.services.analytics.CareerBandMapper;
import dk.trustworks.intranet.aggregates.finance.services.analytics.ProfitabilityProvider;
import dk.trustworks.intranet.aggregates.finance.services.analytics.SalaryAnalyticsProvider;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import dk.trustworks.intranet.financeservice.model.enums.RevenueBasis;
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
import java.time.temporal.ChronoUnit;
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
    DistributionAwareOpexProvider opexProvider;

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
            @QueryParam("companyIds") String companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

        return salaryAnalyticsProvider.getAvgSalaryByBand(fromDate, toDate, parseCommaSeparated(companyIds));
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
            @QueryParam("companyIds") String companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

        return salaryAnalyticsProvider.getTotalSalaryByBand(fromDate, toDate, parseCommaSeparated(companyIds));
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
            @QueryParam("companyIds") String companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

        return salaryAnalyticsProvider.getSalaryCostRatio(fromDate, toDate, parseCommaSeparated(companyIds));
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
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
            @QueryParam("companyIds") String companyIds) {

        Set<String> companies = parseCommaSeparated(companyIds);

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(11).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

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
        if (companies != null) {
            sql.append("  AND EXISTS (SELECT 1 FROM userstatus us2 WHERE us2.useruuid = e.useruuid ");
            sql.append("    AND us2.statusdate = (SELECT MAX(us3.statusdate) FROM userstatus us3 WHERE us3.useruuid = e.useruuid AND us3.statusdate <= CURDATE()) ");
            sql.append("    AND us2.companyuuid IN (:companyIds)) ");
        }
        sql.append("GROUP BY e.useruuid, u.firstname, u.lastname ");
        sql.append("ORDER BY total_expenses DESC LIMIT 10");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);
        if (companies != null) {
            query.setParameter("companyIds", companies);
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
        catQuery.setParameter("toDate", toDate);

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
            @QueryParam("companyIds") String companyIds) {

        Set<String> companies = parseCommaSeparated(companyIds);

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
        if (companies != null) {
            sql.append("  AND b.companyuuid IN (:companyIds) ");
        }
        sql.append("GROUP BY b.year, b.month ");
        sql.append("ORDER BY b.year, b.month");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companies != null) {
            query.setParameter("companyIds", companies);
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
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
            @QueryParam("companyIds") String companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(11).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;
        String fromKey = toMonthKey(fromDate);
        String toKey = toMonthKey(toDate);

        Set<String> companies = parseCommaSeparated(companyIds);

        // Actual revenue (grouped by month)
        String actualSql = buildActualRevenueSql(companies);
        // Reuse existing helper but with inclusive upper bound
        String actualSqlInclusive = actualSql.replace("r.month_key < :toKey", "r.month_key <= :toKey");
        var actualQuery = em.createNativeQuery(actualSqlInclusive, Tuple.class);
        actualQuery.setParameter("fromKey", fromKey);
        actualQuery.setParameter("toKey", toKey);
        if (companies != null) actualQuery.setParameter("companyIds", companies);

        // Budget revenue (grouped by month)
        String budgetSql = buildBudgetRevenueSql(companies);
        var budgetQuery = em.createNativeQuery(budgetSql, Tuple.class);
        budgetQuery.setParameter("fromKey", fromKey);
        budgetQuery.setParameter("toKey", toKey);
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
            @QueryParam("fromDate") String fromDateStr,
            @QueryParam("toDate") String toDateStr,
            @QueryParam("companyIds") String companyIds) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;
        String fromKey = toMonthKey(fromDate);
        String toKey = toMonthKey(toDate);

        Set<String> companies = parseCommaSeparated(companyIds);

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
            @QueryParam("companyIds") String companyIds) {

        Set<String> companies = parseCommaSeparated(companyIds);

        String currentMonthKey = toMonthKey(LocalDate.now());

        // Find latest complete month
        String latestMonthSql = "SELECT MAX(month_key) FROM fact_salary_monthly WHERE month_key < :currentKey";
        String latestKey = (String) em.createNativeQuery(latestMonthSql)
                .setParameter("currentKey", currentMonthKey)
                .getSingleResult();
        if (latestKey == null) return new SalaryEqualityDTO(List.of(), List.of(), currentMonthKey);

        // By practice
        List<SalaryEqualityDTO.SalaryEqualityGroupDTO> byPractice = querySalaryEqualityGroups(
                "fsm.practice_id", "fsm.practice_id", latestKey, companies, null);

        // By career band — requires JOIN with user_career_level since fact_salary_monthly has no career_band column
        String careerLevelJoin = "JOIN user_career_level ucl ON ucl.useruuid = fsm.useruuid "
                + "AND ucl.active_from = (SELECT MAX(ucl2.active_from) FROM user_career_level ucl2 "
                + "WHERE ucl2.useruuid = fsm.useruuid AND ucl2.active_from <= LAST_DAY(STR_TO_DATE(CONCAT(fsm.month_key, '01'), '%Y%m%d'))) ";
        String bandCase = CareerBandMapper.toSqlCase("ucl.career_level");
        List<SalaryEqualityDTO.SalaryEqualityGroupDTO> byCareerBand = querySalaryEqualityGroups(
                bandCase, bandCase, latestKey, companies, careerLevelJoin);

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
            @QueryParam("companyIds") String companyIds,
            @QueryParam("costSource") String costSourceParam) {

        LocalDate today = LocalDate.now();
        LocalDate fromDate = fromDateStr != null ? LocalDate.parse(fromDateStr) : today.minusMonths(17).withDayOfMonth(1);
        LocalDate toDate = toDateStr != null ? LocalDate.parse(toDateStr) : today;

        return profitabilityProvider.getCostPerFte(fromDate, toDate, parseCommaSeparated(companyIds),
                CostSource.fromQueryParam(costSourceParam));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Revenue-Cost Forecast (Charts 1.2 / 1.3)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Revenue vs. cost trend with current-fiscal-year actuals and forward-looking forecast.
     * Replaces BFF route: /api/executive/revenue-cost-trend
     *
     * <p>Window choice — fiscal year (July → June), matching {@code getExpectedAccumulatedEBITDA}.
     * The TTM window is still queried internally because it feeds the gross-margin and
     * average OPEX/Salary inputs used by the forecast projection (same methodology as the
     * EBITDA chart). Only months from {@code fyStartKey} onwards appear in the returned list,
     * so the chart's accumulated "Implied EBITDA" reconciles with the EBITDA chart's
     * accumulated EBITDA at every point.
     *
     * <p>Returns:
     * <ul>
     *   <li>Actual months of the current fiscal year (registered revenue, invoice revenue, total cost)</li>
     *   <li>Forecast months from current month through fiscal year end
     *       (budget revenue + weighted pipeline vs. flat TTM average cost)</li>
     * </ul>
     */
    @GET
    @Path("/revenue-cost-forecast")
    public List<RevenueCostForecastDTO> getRevenueCostForecast(
            @QueryParam("companyIds") String companyIds,
            @QueryParam("costSource") String costSourceParam,
            @QueryParam("basis") String basisParam,
            @QueryParam("fiscalYear") Integer fiscalYear,
            @QueryParam("asOfDate") String asOfDateStr) {

        LocalDate systemToday = LocalDate.now();
        LocalDate today = asOfDateStr != null && !asOfDateStr.isBlank() ? LocalDate.parse(asOfDateStr) : systemToday;

        // Fiscal year: July 1 → June 30 (matches getExpectedAccumulatedEBITDA in CxoFinanceService).
        int fyStartYear = fiscalYear != null ? fiscalYear : (today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1);
        int currentFyStartYear = systemToday.getMonthValue() >= 7 ? systemToday.getYear() : systemToday.getYear() - 1;
        boolean completedSelectedFiscalYear = fiscalYear != null && fyStartYear < currentFyStartYear;
        String fyStartKey = toMonthKey(fyStartYear, 7);
        String fyEndKey = toMonthKey(fyStartYear + 1, 6);
        String actualEndExclusiveKey = completedSelectedFiscalYear ? toMonthKey(fyStartYear + 1, 7) : toMonthKey(today);
        String ttmStartKey = completedSelectedFiscalYear ? fyStartKey : toMonthKey(today.minusMonths(12));
        String ttmEndKey = completedSelectedFiscalYear ? fyEndKey : toMonthKey(today.minusMonths(1));

        Set<String> companies = parseCommaSeparated(companyIds);
        CostSource costSource = CostSource.fromQueryParam(costSourceParam);
        // WORK_PERIOD basis (default INVOICED): invoice revenue + internal-synth cost are
        // bucketed by invoices.year/month; CREATED-internal GL cost (expensedate) is offset
        // and re-added on the work month. Subcontractor GL direct cost and OPEX/salary stay
        // on the month incurred. INVOICED is byte-identical to the historical behaviour.
        boolean workPeriod = (RevenueBasis.fromQueryParam(basisParam) == RevenueBasis.WORK_PERIOD);

        // ── TTM actual data (queried for projection inputs; display filtered to FY) ──

        // Q1: Registered revenue from fact_user_day
        String registeredRevenueSql = buildRegisteredRevenueSql(companies);
        var regQuery = em.createNativeQuery(registeredRevenueSql, Tuple.class);
        regQuery.setParameter("ttmStart", ttmStartKey);
        regQuery.setParameter("currentMonth", actualEndExclusiveKey);
        if (companies != null) regQuery.setParameter("companyIds", companies);

        // Q2: Invoice revenue (basis-aware: invoicedate _mat vs work-period view)
        String invoiceRevenueSql = buildInvoiceRevenueSql(companies, workPeriod);
        var invQuery = em.createNativeQuery(invoiceRevenueSql, Tuple.class);
        invQuery.setParameter("ttmStart", ttmStartKey);
        invQuery.setParameter("currentMonth", actualEndExclusiveKey);
        if (companies != null) invQuery.setParameter("companyIds", companies);

        @SuppressWarnings("unchecked") List<Tuple> regRows = regQuery.getResultList();
        @SuppressWarnings("unchecked") List<Tuple> invRows = invQuery.getResultList();

        // Index by month_key
        Map<String, Tuple> regMap = indexByMonthKey(regRows);
        Map<String, Tuple> invMap = indexByMonthKey(invRows);
        Map<String, Double> opexSalaryMap = opexProvider
                .getMonthlyOpex(ttmStartKey, ttmEndKey, companies, costSource);

        // Q3a: GL DIRECT_COSTS by month (signed, costSource-aware)
        String glDirectSql = buildMonthlyGlDirectCostSql(companies);
        var glDirectQuery = em.createNativeQuery(glDirectSql, Tuple.class);
        glDirectQuery.setParameter("fromKey", ttmStartKey);
        glDirectQuery.setParameter("toKey", ttmEndKey);
        glDirectQuery.setParameter("postingStatuses", costSource.postingStatusNames());
        if (companies != null) glDirectQuery.setParameter("companyIds", companies);
        @SuppressWarnings("unchecked") List<Tuple> glDirectRows = glDirectQuery.getResultList();
        Map<String, Double> glDirectMap = new HashMap<>();
        for (Tuple row : glDirectRows) {
            glDirectMap.put((String) row.get("month_key"), numericValue(row, "gl_direct_cost"));
        }

        // Q3b: QUEUED INTERNAL synthesized cost by month (debtor-side, costSource-independent;
        // basis-aware bucketing)
        String queuedSql = buildMonthlyQueuedInternalCostSql(companies, workPeriod);
        var queuedQuery = em.createNativeQuery(queuedSql, Tuple.class);
        queuedQuery.setParameter("fromKey", ttmStartKey);
        queuedQuery.setParameter("toKey", ttmEndKey);
        if (companies != null) queuedQuery.setParameter("companyIds", companies);
        @SuppressWarnings("unchecked") List<Tuple> queuedRows = queuedQuery.getResultList();
        Map<String, Double> queuedMap = new HashMap<>();
        for (Tuple row : queuedRows) {
            queuedMap.put((String) row.get("month_key"), numericValue(row, "queued_cost"));
        }

        // Q3c (WORK_PERIOD only): re-time CREATED INTERNAL cost onto the work month.
        // createdSynth (work-month bucket) is ADDED and glInternal (the GL expensedate copy
        // already inside glDirect) is SUBTRACTED, so the cost is never double-counted and FY
        // totals are conserved — same mechanism as the EBITDA chart's F3/WORK_PERIOD path.
        Map<String, Double> createdSynthMap = new HashMap<>();
        Map<String, Double> glInternalMap = new HashMap<>();
        if (workPeriod) {
            var createdQuery = em.createNativeQuery(buildMonthlyCreatedInternalCostWpSql(companies), Tuple.class);
            createdQuery.setParameter("fromKey", ttmStartKey);
            createdQuery.setParameter("toKey", ttmEndKey);
            if (companies != null) createdQuery.setParameter("companyIds", companies);
            @SuppressWarnings("unchecked") List<Tuple> createdRows = createdQuery.getResultList();
            for (Tuple row : createdRows) {
                createdSynthMap.put((String) row.get("month_key"), numericValue(row, "created_cost"));
            }

            var glInternalQuery = em.createNativeQuery(buildMonthlyGlInternalCostSql(companies), Tuple.class);
            glInternalQuery.setParameter("fromKey", ttmStartKey);
            glInternalQuery.setParameter("toKey", ttmEndKey);
            glInternalQuery.setParameter("postingStatuses", costSource.postingStatusNames());
            if (companies != null) glInternalQuery.setParameter("companyIds", companies);
            @SuppressWarnings("unchecked") List<Tuple> glInternalRows = glInternalQuery.getResultList();
            for (Tuple row : glInternalRows) {
                glInternalMap.put((String) row.get("month_key"), numericValue(row, "gl_internal_cost"));
            }
        }

        // Collect all month keys
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(regMap.keySet());
        allKeys.addAll(invMap.keySet());
        allKeys.addAll(opexSalaryMap.keySet());
        allKeys.addAll(glDirectMap.keySet());
        allKeys.addAll(queuedMap.keySet());
        allKeys.addAll(createdSynthMap.keySet());
        allKeys.addAll(glInternalMap.keySet());

        // Iterate ALL keys in the TTM window so projection inputs (gross margin, avg
        // OPEX+salary) are computed over a stable 12-month base. Only months within the
        // current fiscal year are emitted to the result list — that's what aligns the
        // chart's accumulated trajectory with getExpectedAccumulatedEBITDA.
        List<RevenueCostForecastDTO> result = new ArrayList<>();
        double totalCostSum = 0;
        double totalOpexSalarySum = 0;
        double totalInvoiceRevenueSum = 0;
        double totalDirectCostSum = 0;
        int ttmMonthCount = 0;

        for (String key : allKeys) {
            int year = resolveYear(key, regMap.get(key), invMap.get(key));
            int month = resolveMonth(key, regMap.get(key), invMap.get(key));
            double regRev = numericValue(regMap.get(key), "registered_revenue");
            double invRev = numericValue(invMap.get(key), "net_revenue");
            double opexSalary = opexSalaryMap.getOrDefault(key, 0.0);
            double glDirect = glDirectMap.getOrDefault(key, 0.0);
            double queued = queuedMap.getOrDefault(key, 0.0);
            double directDelivery = glDirect + queued;
            if (workPeriod) {
                // re-time CREATED-internal: +synth(work month) −GL(expensedate, already in glDirect)
                directDelivery += createdSynthMap.getOrDefault(key, 0.0)
                                - glInternalMap.getOrDefault(key, 0.0);
            }
            double totalCost = opexSalary + directDelivery;

            totalCostSum += Math.round(totalCost);
            totalOpexSalarySum += Math.round(opexSalary);
            totalInvoiceRevenueSum += invRev;
            totalDirectCostSum += directDelivery;
            ttmMonthCount++;

            if (key.compareTo(fyStartKey) < 0 || key.compareTo(fyEndKey) > 0) continue;

            result.add(new RevenueCostForecastDTO(
                    key, year, month,
                    SalaryAnalyticsProvider.formatMonthLabel(year, month),
                    Math.round(regRev), Math.round(invRev),
                    Math.round(totalCost), Math.round(directDelivery),
                    null, false));
        }

        // Flat TTM average total cost — kept on every row as horizontal reference line
        Double flatAvgCost = ttmMonthCount > 0 ? Math.round(totalCostSum / ttmMonthCount) * 1.0 : null;
        // TTM avg OPEX+Salary — used as the OPEX/Salary forecast component (the
        // direct-delivery forecast component is derived from the gross-margin ratio).
        double flatAvgOpexSalary = ttmMonthCount > 0 ? totalOpexSalarySum / ttmMonthCount : 0;
        // TTM gross margin = (revenue - direct cost) / revenue. Same formula the EBITDA
        // chart uses for forecast direct delivery cost projection.
        double ttmGrossMargin = totalInvoiceRevenueSum > 0
                ? (totalInvoiceRevenueSum - totalDirectCostSum) / totalInvoiceRevenueSum
                : 0;

        List<RevenueCostForecastDTO> withAvg = result.stream()
                .map(r -> new RevenueCostForecastDTO(r.monthKey(), r.year(), r.monthNumber(),
                        r.monthLabel(), r.registeredRevenueDkk(), r.invoiceRevenueDkk(),
                        r.totalCostDkk(), r.directDeliveryCostDkk(), flatAvgCost, false))
                .collect(Collectors.toCollection(ArrayList::new));

        // ── Forecast months (current through FY end) ─────────────────────

        String forecastStartKey = actualEndExclusiveKey.compareTo(fyStartKey) < 0 ? fyStartKey : actualEndExclusiveKey;

        if (!completedSelectedFiscalYear && forecastStartKey.compareTo(fyEndKey) <= 0) {
            // Q4: Budget revenue
            String budgetSql = buildBudgetRevenueSql(companies);
            var budgetQuery = em.createNativeQuery(budgetSql, Tuple.class);
            budgetQuery.setParameter("fromKey", forecastStartKey);
            budgetQuery.setParameter("toKey", fyEndKey);
            if (companies != null) budgetQuery.setParameter("companyIds", companies);

            // Q5: Pipeline (excl. WON)
            String pipelineSql = buildPipelineSql(companies);
            var pipelineQuery = em.createNativeQuery(pipelineSql, Tuple.class);
            pipelineQuery.setParameter("fromKey", forecastStartKey);
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
            int fYear = Integer.parseInt(forecastStartKey.substring(0, 4));
            int fMonth = Integer.parseInt(forecastStartKey.substring(4, 6));
            while (toMonthKey(fYear, fMonth).compareTo(fyEndKey) <= 0) {
                String fKey = toMonthKey(fYear, fMonth);
                double budgetRev = budgetMap.getOrDefault(fKey, 0.0);
                double pipelineRev = pipelineMap.getOrDefault(fKey, 0.0);
                double forecastRevenue = Math.round(budgetRev + pipelineRev);

                // Forecast direct delivery cost via TTM gross margin (matches EBITDA chart).
                double forecastDirectDelivery = Math.round(forecastRevenue * (1.0 - ttmGrossMargin));
                double forecastTotalCost = Math.round(flatAvgOpexSalary + forecastDirectDelivery);

                withAvg.add(new RevenueCostForecastDTO(
                        fKey, fYear, fMonth,
                        SalaryAnalyticsProvider.formatMonthLabel(fYear, fMonth),
                        forecastRevenue, forecastRevenue,
                        forecastTotalCost, forecastDirectDelivery,
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
            @QueryParam("companyIds") String companyIds) {

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

        Set<String> companies = parseCommaSeparated(companyIds);

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

    /**
     * Invoice revenue (TTM window). INVOICED basis reads the invoicedate-keyed
     * materialized fact {@code fact_company_revenue_mat}; WORK_PERIOD reads the live
     * {@code fact_company_revenue_workperiod} view (same invoices bucketed by
     * invoices.year/month). Both expose identical columns.
     */
    private static String buildInvoiceRevenueSql(Set<String> companies, boolean workPeriod) {
        String table = workPeriod ? "fact_company_revenue_workperiod" : "fact_company_revenue_mat";
        String filter = companies != null ? "AND r.company_id IN (:companyIds) " : "";
        return "SELECT r.month_key, r.year, r.month_number, " +
                "SUM(r.net_revenue_dkk) AS net_revenue " +
                "FROM " + table + " r " +
                "WHERE r.month_key >= :ttmStart AND r.month_key < :currentMonth " +
                filter +
                "GROUP BY r.month_key, r.year, r.month_number ORDER BY r.month_key";
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
                "AND o.posting_status = 'BOOKED' " +
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
                "AND o.posting_status = 'BOOKED' " +
                "AND o.month_key >= :ttmStart AND o.month_key < :currentMonth " +
                filter;
    }

    /**
     * Monthly GL DIRECT_COSTS from finance_details (signed sum), parameterized by costSource.
     * Identical predicate to {@code CxoFinanceService.queryMonthlyDirectCostByMonth} so the
     * Revenue/Cost chart and EBITDA chart subtract the same direct delivery cost.
     */
    private static String buildMonthlyGlDirectCostSql(Set<String> companies) {
        String filter = companies != null ? "AND fd.companyuuid IN (:companyIds) " : "";
        return "SELECT DATE_FORMAT(fd.expensedate, '%Y%m') AS month_key, " +
                "       COALESCE(SUM(fd.amount), 0.0) AS gl_direct_cost " +
                "FROM finance_details fd " +
                "INNER JOIN accounting_accounts aa " +
                "    ON fd.accountnumber = aa.account_code " +
                "    AND fd.companyuuid  = aa.companyuuid " +
                "WHERE aa.cost_type = 'DIRECT_COSTS' " +
                "  AND DATE_FORMAT(fd.expensedate, '%Y%m') BETWEEN :fromKey AND :toKey " +
                "  AND fd.amount != 0 " +
                "  AND fd.postingstatus IN (:postingStatuses) " +
                filter +
                "GROUP BY DATE_FORMAT(fd.expensedate, '%Y%m') ORDER BY month_key";
    }

    /**
     * Monthly QUEUED INTERNAL invoice cost, attributed to debtor company, in DKK.
     * Mirrors {@code CxoFinanceService.queryMonthlyQueuedInternalCostByMonth}. QUEUED
     * INTERNALs aren't yet in the GL, so they're synthesized here. Independent of
     * costSource (QUEUED rows aren't subject to BOOKED/DRAFT classification).
     */
    private static String buildMonthlyQueuedInternalCostSql(Set<String> companies, boolean workPeriod) {
        String mexpr = workPeriod
                ? "CONCAT(LPAD(i.year, 4, '0'), LPAD(i.month, 2, '0'))"
                : "DATE_FORMAT(i.invoicedate, '%Y%m')";
        String filter = companies != null ? "AND i.debtor_companyuuid IN (:companyIds) " : "";
        return "SELECT " + mexpr + " AS month_key, " +
                "       COALESCE(SUM(ii.rate * ii.hours * " +
                "           CASE WHEN i.currency = 'DKK' THEN 1.0 " +
                "                ELSE COALESCE((SELECT c.conversion FROM currences c " +
                "                              WHERE c.currency = i.currency " +
                "                                AND c.month = DATE_FORMAT(i.invoicedate, '%Y%m') LIMIT 1), 1.0) " +
                "           END), 0.0) AS queued_cost " +
                "FROM invoices i " +
                "JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid " +
                "WHERE i.type = 'INTERNAL' " +
                "  AND i.status = 'QUEUED' " +
                "  AND i.debtor_companyuuid IS NOT NULL " +
                "  AND " + mexpr + " BETWEEN :fromKey AND :toKey " +
                "  AND ii.rate IS NOT NULL " +
                "  AND ii.hours IS NOT NULL " +
                filter +
                "GROUP BY " + mexpr + " ORDER BY month_key";
    }

    /**
     * WORK_PERIOD only: CREATED INTERNAL synth cost bucketed by the WORK period
     * (invoices.year/month), debtor-attributed. Added to direct delivery and offset by
     * {@link #buildMonthlyGlInternalCostSql} (the GL expensedate copy) so CREATED-internal
     * cost re-times onto the work month without double counting — mirrors the EBITDA chart.
     */
    private static String buildMonthlyCreatedInternalCostWpSql(Set<String> companies) {
        String mexpr = "CONCAT(LPAD(i.year, 4, '0'), LPAD(i.month, 2, '0'))";
        String filter = companies != null ? "AND i.debtor_companyuuid IN (:companyIds) " : "";
        return "SELECT " + mexpr + " AS month_key, " +
                "       COALESCE(SUM(ii.rate * ii.hours * " +
                "           CASE WHEN i.currency = 'DKK' THEN 1.0 " +
                "                ELSE COALESCE((SELECT c.conversion FROM currences c " +
                "                              WHERE c.currency = i.currency " +
                "                                AND c.month = DATE_FORMAT(i.invoicedate, '%Y%m') LIMIT 1), 1.0) " +
                "           END), 0.0) AS created_cost " +
                "FROM invoices i " +
                "JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid " +
                "WHERE i.type = 'INTERNAL' " +
                "  AND i.status = 'CREATED' " +
                "  AND i.debtor_companyuuid IS NOT NULL " +
                "  AND " + mexpr + " BETWEEN :fromKey AND :toKey " +
                "  AND ii.rate IS NOT NULL " +
                "  AND ii.hours IS NOT NULL " +
                filter +
                "GROUP BY " + mexpr + " ORDER BY month_key";
    }

    /**
     * WORK_PERIOD only: the GL-booked copy of CREATED INTERNAL cost on the expensedate month
     * (accounts 3050/3055/3070/3075/1350), costSource-aware. Subtracted from direct delivery
     * when re-timing CREATED-internal cost onto the work month. Same predicate as
     * {@code CxoFinanceService.queryMonthlyCreatedInternalGlCostByMonth}.
     */
    private static String buildMonthlyGlInternalCostSql(Set<String> companies) {
        String filter = companies != null ? "AND fd.companyuuid IN (:companyIds) " : "";
        return "SELECT DATE_FORMAT(fd.expensedate, '%Y%m') AS month_key, " +
                "       COALESCE(SUM(fd.amount), 0.0) AS gl_internal_cost " +
                "FROM finance_details fd " +
                "INNER JOIN accounting_accounts aa " +
                "    ON fd.accountnumber = aa.account_code " +
                "    AND fd.companyuuid  = aa.companyuuid " +
                "WHERE aa.cost_type = 'DIRECT_COSTS' " +
                "  AND fd.accountnumber IN (3050, 3055, 3070, 3075, 1350) " +
                "  AND DATE_FORMAT(fd.expensedate, '%Y%m') BETWEEN :fromKey AND :toKey " +
                "  AND fd.amount != 0 " +
                "  AND fd.postingstatus IN (:postingStatuses) " +
                filter +
                "GROUP BY DATE_FORMAT(fd.expensedate, '%Y%m') ORDER BY month_key";
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

    private static int resolveYear(String monthKey, Tuple... sources) {
        for (Tuple t : sources) {
            if (t != null && t.get("year") != null) return ((Number) t.get("year")).intValue();
        }
        return Integer.parseInt(monthKey.substring(0, 4));
    }

    private static int resolveMonth(String monthKey, Tuple... sources) {
        for (Tuple t : sources) {
            if (t != null && t.get("month_number") != null) return ((Number) t.get("month_number")).intValue();
        }
        return Integer.parseInt(monthKey.substring(4, 6));
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

    /**
     * Cost-data freshness for the Executive Dashboard banner.
     *
     * Reports the latest `expensedate` in `finance_details` (overall and per
     * company). The Cost / Career Level Costs / Practices tabs read from
     * `fact_opex_mat`, which derives from `finance_details`. Because monthly
     * periods only close in e-conomics 1-3 weeks after month-end, the most
     * recent month is often incomplete — surfacing this lets users avoid
     * making decisions on partial data.
     */
    @GET
    @Path("/cost-data-freshness")
    public CostDataFreshnessDTO getCostDataFreshness() {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT
                    fd.companyuuid AS company_uuid,
                    c.name         AS company_name,
                    MAX(fd.expensedate) AS latest_expense_date
                FROM finance_details fd
                LEFT JOIN companies c ON c.uuid = fd.companyuuid
                WHERE fd.postingstatus = 'BOOKED'
                GROUP BY fd.companyuuid, c.name
                ORDER BY latest_expense_date DESC
                """, Tuple.class).getResultList();

        LocalDate today = LocalDate.now();
        LocalDate overallLatest = null;
        List<CostDataFreshnessDTO.CompanyFreshness> perCompany = new ArrayList<>(rows.size());

        for (Tuple row : rows) {
            String companyUuid = row.get("company_uuid", String.class);
            String companyName = row.get("company_name", String.class);
            // Hibernate's typed accessor handles whichever java.sql.Date / LocalDate /
            // Timestamp the JDBC driver returns. Newer MariaDB Connector/J versions
            // return LocalDate directly, so the previous (java.sql.Date) cast threw
            // ClassCastException at runtime.
            LocalDate latest = row.get("latest_expense_date", LocalDate.class);
            Integer daysBehind = latest != null
                    ? (int) ChronoUnit.DAYS.between(latest, today)
                    : null;
            perCompany.add(new CostDataFreshnessDTO.CompanyFreshness(
                    companyUuid, companyName, latest, daysBehind));
            if (latest != null && (overallLatest == null || latest.isAfter(overallLatest))) {
                overallLatest = latest;
            }
        }

        Integer overallDaysBehind = overallLatest != null
                ? (int) ChronoUnit.DAYS.between(overallLatest, today)
                : null;

        return new CostDataFreshnessDTO(overallLatest, overallDaysBehind, perCompany);
    }

    /** Upper bound on distinct companyIds — the tenant has a handful of companies; a larger
     *  list can only be malformed/abusive input. Caps the IN() clause (defense-in-depth). */
    private static final int MAX_COMPANY_IDS = 50;

    /**
     * Parses comma-separated string into a Set of trimmed values.
     * Returns null if input is null or empty. Rejects pathologically large lists
     * (&gt; {@link #MAX_COMPANY_IDS}) with HTTP 400 so an unbounded IN() clause cannot be
     * forced from the query string (the endpoints already require {@code dashboard:read}).
     */
    private Set<String> parseCommaSeparated(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Set<String> result = new HashSet<>();
        for (String value : input.split(",")) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        if (result.size() > MAX_COMPANY_IDS) {
            throw new BadRequestException("companyIds: too many values (max " + MAX_COMPANY_IDS + ")");
        }
        return result.isEmpty() ? null : result;
    }
}
