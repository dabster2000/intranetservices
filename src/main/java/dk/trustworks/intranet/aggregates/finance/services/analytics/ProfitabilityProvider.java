package dk.trustworks.intranet.aggregates.finance.services.analytics;

import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.aggregates.finance.dto.analytics.CostPerFteDTO;
import dk.trustworks.intranet.aggregates.finance.services.DistributionAwareOpexProvider;
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
}
