package dk.trustworks.intranet.aggregates.finance.services.analytics;

import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.aggregates.finance.dto.TeamBillingRateDTO;
import dk.trustworks.intranet.aggregates.finance.dto.TeamContributionMarginDTO;
import dk.trustworks.intranet.aggregates.finance.dto.analytics.CostPerFteDTO;
import dk.trustworks.intranet.aggregates.finance.services.DistributionAwareOpexProvider;
import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.toMonthKey;

/**
 * Composes {@link SalaryAnalyticsProvider}, {@link OpexAnalyticsProvider},
 * and {@link RevenueAnalyticsProvider} for profitability calculations.
 *
 * Initial version: cost-per-FTE only. Revenue-based profitability methods
 * will be added in Phase 4.
 */
@JBossLog
@ApplicationScoped
public class ProfitabilityProvider {

    @Inject
    SalaryAnalyticsProvider salaryProvider;

    @Inject
    OpexAnalyticsProvider opexProvider;

    @Inject
    RevenueAnalyticsProvider revenueProvider;

    @Inject
    DistributionAwareOpexProvider distributionProvider;

    @Inject
    EntityManager em;

    /**
     * Monthly cost per billable FTE (salary + OPEX per FTE).
     * Uses FY-aware OPEX via DistributionAwareOpexProvider.
     *
     * Replaces BFF route: /api/cxo/cost/cost-per-consultant
     */
    public List<CostPerFteDTO> getCostPerFte(LocalDate fromDate, LocalDate toDate, Set<String> companyIds) {
        String fromKey = toMonthKey(fromDate);
        String toKey = toMonthKey(toDate);

        // 1. Query FTE per month from fact_employee_monthly_mat
        StringBuilder fteSql = new StringBuilder();
        fteSql.append("SELECT e.month_key, SUM(e.fte_billable) AS billable_fte ");
        fteSql.append("FROM fact_employee_monthly_mat e ");
        fteSql.append("WHERE e.month_key >= :fromKey AND e.month_key <= :toKey ");
        if (companyIds != null && !companyIds.isEmpty()) {
            fteSql.append("AND e.company_id IN (:companyIds) ");
        }
        fteSql.append("GROUP BY e.month_key ORDER BY e.month_key");

        var fteQuery = em.createNativeQuery(fteSql.toString(), Tuple.class);
        fteQuery.setParameter("fromKey", fromKey);
        fteQuery.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            fteQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> fteRows = fteQuery.getResultList();

        // 2. Query salary per month from fact_user_day (same pattern as SalaryAnalyticsProvider)
        StringBuilder salSql = new StringBuilder();
        salSql.append("SELECT CONCAT(LPAD(yr, 4, '0'), LPAD(mn, 2, '0')) AS month_key, ");
        salSql.append("  SUM(monthly_sal) AS total_salary ");
        salSql.append("FROM ( ");
        salSql.append("  SELECT fud.year AS yr, fud.month AS mn, MAX(fud.salary) AS monthly_sal ");
        salSql.append("  FROM fact_user_day fud ");
        salSql.append("  WHERE fud.consultant_type = 'CONSULTANT' ");
        salSql.append("    AND fud.status_type = 'ACTIVE' AND fud.salary > 0 ");
        salSql.append("    AND fud.document_date >= :fromDate AND fud.document_date <= :toDate ");
        if (companyIds != null && !companyIds.isEmpty()) {
            salSql.append("    AND fud.companyuuid IN (:companyIds) ");
        }
        salSql.append("  GROUP BY fud.useruuid, fud.year, fud.month ");
        salSql.append(") per_user GROUP BY yr, mn");

        var salQuery = em.createNativeQuery(salSql.toString(), Tuple.class);
        salQuery.setParameter("fromDate", fromDate);
        salQuery.setParameter("toDate", toDate);
        if (companyIds != null && !companyIds.isEmpty()) {
            salQuery.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> salRows = salQuery.getResultList();
        Map<String, Double> salaryByMonth = new HashMap<>();
        for (Tuple row : salRows) {
            salaryByMonth.put((String) row.get("month_key"), ((Number) row.get("total_salary")).doubleValue());
        }

        // 3. Query OPEX per month via FY-aware provider
        List<OpexRow> opexRows = distributionProvider.getDistributionAwareOpex(
                fromKey, toKey, companyIds, null, null);
        Map<String, Double> opexByMonth = new HashMap<>();
        for (OpexRow row : opexRows) {
            if (!row.isPayrollFlag()) {
                opexByMonth.merge(row.monthKey(), row.opexAmountDkk(), Double::sum);
            }
        }

        // 4. Combine into result
        List<CostPerFteDTO> result = new ArrayList<>();
        for (Tuple fteRow : fteRows) {
            String monthKey = (String) fteRow.get("month_key");
            double fte = ((Number) fteRow.get("billable_fte")).doubleValue();
            int year = Integer.parseInt(monthKey.substring(0, 4));
            int month = Integer.parseInt(monthKey.substring(4));

            double salary = salaryByMonth.getOrDefault(monthKey, 0.0);
            double opex = opexByMonth.getOrDefault(monthKey, 0.0);

            Double salaryPerFte = fte > 0 ? (double) Math.round(salary / fte) : null;
            Double opexPerFte = fte > 0 ? (double) Math.round(opex / fte) : null;

            result.add(new CostPerFteDTO(
                    monthKey, year, month,
                    SalaryAnalyticsProvider.formatMonthLabel(year, month),
                    salaryPerFte, opexPerFte, fte,
                    Math.round(salary), Math.round(opex)
            ));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Contribution Margin
    // -----------------------------------------------------------------------

    /**
     * Team contribution margin: revenue - salary - allocated OPEX.
     * Uses FY-aware OPEX via OpexAnalyticsProvider (consistency fix).
     */
    public TeamContributionMarginDTO getContributionMargin(String teamId, int fiscalYear) {
        var fyRange = UtilizationCalculationHelper.getFiscalYearRange(fiscalYear);
        String fromKey = fyRange.startKey();
        String toKey = fyRange.endKey();
        LocalDate fromDate = fyRange.start();
        LocalDate toDate = fyRange.end();

        List<String> memberUuids = getTeamMemberUuids(teamId);
        if (memberUuids.isEmpty()) {
            return new TeamContributionMarginDTO(teamId, "", fiscalYear, 0, 0, 0, 0, 0, null, null);
        }

        // Revenue (time-registration based for team)
        double revenue = revenueProvider.getTeamRevenue(memberUuids, fromDate, toDate);

        // Salary cost from fact_salary_monthly_teamroles
        String salSql = "SELECT COALESCE(SUM(fsmt.salary_sum), 0) FROM fact_salary_monthly_teamroles fsmt " +
                "WHERE fsmt.teamuuid = :teamId AND fsmt.month_key >= :fromKey AND fsmt.month_key <= :toKey";
        double salaryCost = ((Number) em.createNativeQuery(salSql)
                .setParameter("teamId", teamId)
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .getSingleResult()).doubleValue();

        // Allocated OPEX (FY-aware, consistent denominator)
        double allocatedOpex = opexProvider.getTeamAllocatedOpex(teamId, fromKey, toKey, null);

        double grossMargin = revenue - salaryCost;
        double contributionMargin = grossMargin - allocatedOpex;
        Double grossMarginPct = revenue > 0 ? (grossMargin / revenue) * 100.0 : null;
        Double contributionMarginPct = revenue > 0 ? (contributionMargin / revenue) * 100.0 : null;

        String teamName = getTeamName(teamId);

        return new TeamContributionMarginDTO(teamId, teamName, fiscalYear,
                revenue, salaryCost, allocatedOpex, grossMargin, contributionMargin,
                grossMarginPct, contributionMarginPct);
    }

    // -----------------------------------------------------------------------
    // Break-Even Rates
    // -----------------------------------------------------------------------

    /**
     * Break-even billing rate per consultant.
     * Formula: (monthly_salary + monthly_overhead) / (net_available_hours * 0.75)
     */
    public List<TeamBillingRateDTO> getBreakEvenRates(List<String> memberUuids, LocalDate fromDate, LocalDate toDate) {
        if (memberUuids == null || memberUuids.isEmpty()) return List.of();

        String fromKey = toMonthKey(fromDate);
        String toKey = toMonthKey(toDate);

        // Monthly overhead per consultant (FY-aware)
        double monthlyOverhead = opexProvider.getOverheadPerConsultant(fromKey, toKey, null);

        // Actual effective rates
        String actualSql = "SELECT fud.useruuid AS user_id, u.firstname, u.lastname, " +
                "  COALESCE(SUM(fud.registered_amount), 0) AS revenue, " +
                "  COALESCE(SUM(fud.registered_billable_hours), 0) AS billable_hours " +
                "FROM fact_user_day fud JOIN user u ON u.uuid = fud.useruuid " +
                "WHERE fud.useruuid IN (:memberUuids) " +
                "  AND fud.document_date >= :fromDate AND fud.document_date <= :toDate " +
                "  AND fud.consultant_type = 'CONSULTANT' AND fud.status_type = 'ACTIVE' " +
                "GROUP BY fud.useruuid, u.firstname, u.lastname";

        @SuppressWarnings("unchecked")
        List<Tuple> actualRows = em.createNativeQuery(actualSql, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .getResultList();

        // Break-even rates: salary + overhead / (net_hours * 0.75)
        String beSql = "SELECT sal.useruuid AS user_id, " +
                "  CASE WHEN COALESCE(avail.net_hours, 0) * 0.75 > 0 " +
                "    THEN (sal.monthly_salary + :overhead) / (avail.net_hours * 0.75) ELSE NULL END AS break_even " +
                "FROM (SELECT useruuid, AVG(max_sal) AS monthly_salary FROM " +
                "  (SELECT useruuid, MAX(salary) AS max_sal FROM fact_user_day " +
                "   WHERE useruuid IN (:memberUuids) AND consultant_type = 'CONSULTANT' AND salary > 0 " +
                "   AND document_date >= :fromDate AND document_date <= :toDate " +
                "   GROUP BY useruuid, year, month) ms GROUP BY useruuid) sal " +
                "LEFT JOIN (SELECT useruuid, AVG(mn) AS net_hours FROM " +
                "  (SELECT useruuid, SUM(net_available_hours) AS mn FROM fact_user_day " +
                "   WHERE useruuid IN (:memberUuids) AND consultant_type = 'CONSULTANT' AND status_type = 'ACTIVE' " +
                "   AND document_date >= :fromDate AND document_date <= :toDate " +
                "   GROUP BY useruuid, year, month) ma GROUP BY useruuid) avail ON avail.useruuid = sal.useruuid";

        @SuppressWarnings("unchecked")
        List<Tuple> beRows = em.createNativeQuery(beSql, Tuple.class)
                .setParameter("memberUuids", memberUuids)
                .setParameter("fromDate", fromDate)
                .setParameter("toDate", toDate)
                .setParameter("overhead", monthlyOverhead)
                .getResultList();

        Map<String, Double> breakEvenByUser = new HashMap<>();
        for (Tuple row : beRows) {
            String userId = (String) row.get("user_id");
            Double be = row.get("break_even") != null ? ((Number) row.get("break_even")).doubleValue() : null;
            if (be != null) breakEvenByUser.put(userId, be);
        }

        List<TeamBillingRateDTO> result = new ArrayList<>();
        for (Tuple row : actualRows) {
            String userId = (String) row.get("user_id");
            double revenue = ((Number) row.get("revenue")).doubleValue();
            double hours = ((Number) row.get("billable_hours")).doubleValue();
            Double actualRate = hours > 0 ? revenue / hours : null;
            Double breakEven = breakEvenByUser.get(userId);
            Double margin = (actualRate != null && breakEven != null) ? actualRate - breakEven : null;

            result.add(new TeamBillingRateDTO(userId,
                    (String) row.get("firstname"), (String) row.get("lastname"),
                    actualRate, breakEven, margin));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private List<String> getTeamMemberUuids(String teamId) {
        String sql = "SELECT DISTINCT tr.useruuid FROM teamroles tr " +
                "WHERE tr.teamuuid = :teamId AND tr.membertype = 'MEMBER' " +
                "AND tr.startdate <= CURDATE() AND (tr.enddate IS NULL OR tr.enddate > CURDATE())";
        @SuppressWarnings("unchecked")
        List<String> uuids = em.createNativeQuery(sql).setParameter("teamId", teamId).getResultList();
        return uuids;
    }

    private String getTeamName(String teamId) {
        try {
            return (String) em.createNativeQuery("SELECT name FROM team WHERE uuid = :id")
                    .setParameter("id", teamId).getSingleResult();
        } catch (Exception e) {
            return "";
        }
    }
}
