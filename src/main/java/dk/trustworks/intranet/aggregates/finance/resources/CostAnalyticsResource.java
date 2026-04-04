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
                "fsm.practice_id", "fsm.practice_id", latestKey, companyIds);

        // By career band
        String bandCase = CareerBandMapper.toSqlCase("fsm.career_band");
        List<SalaryEqualityDTO.SalaryEqualityGroupDTO> byCareerBand = querySalaryEqualityGroups(
                bandCase, bandCase, latestKey, companyIds);

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

    private List<SalaryEqualityDTO.SalaryEqualityGroupDTO> querySalaryEqualityGroups(
            String groupExpr, String labelExpr, String monthKey, Set<String> companyIds) {

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(groupExpr).append(" AS group_id, ");
        sql.append(labelExpr).append(" AS group_label, ");
        sql.append("  AVG(CASE WHEN u.gender = 'MALE' THEN fsm.effective_salary END) AS male_avg, ");
        sql.append("  AVG(CASE WHEN u.gender = 'FEMALE' THEN fsm.effective_salary END) AS female_avg, ");
        sql.append("  COUNT(CASE WHEN u.gender = 'MALE' THEN 1 END) AS male_count, ");
        sql.append("  COUNT(CASE WHEN u.gender = 'FEMALE' THEN 1 END) AS female_count ");
        sql.append("FROM fact_salary_monthly fsm ");
        sql.append("JOIN user u ON u.uuid = fsm.useruuid ");
        sql.append("WHERE fsm.month_key = :monthKey ");
        sql.append("  AND u.gender IN ('MALE', 'FEMALE') ");
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND fsm.company_id IN (:companyIds) ");
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
