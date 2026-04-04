package dk.trustworks.intranet.aggregates.finance.services.analytics;

import dk.trustworks.intranet.aggregates.finance.dto.analytics.SalaryByBandDTO;
import dk.trustworks.intranet.aggregates.finance.dto.analytics.SalaryCostRatioDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper.toMonthKey;

/**
 * Provides salary aggregation queries for all cost dashboards.
 *
 * All salary data comes from {@code fact_user_day} with standard filters:
 * consultant_type='CONSULTANT', status_type='ACTIVE', salary > 0.
 *
 * Career level resolution always uses LAST_DAY() of the month for consistency.
 */
@JBossLog
@ApplicationScoped
public class SalaryAnalyticsProvider {

    @Inject
    EntityManager em;

    /**
     * Average salary per career band per month.
     * Used by CXO salary-development chart.
     *
     * Formula: AVG(MAX(salary) per consultant per month) grouped by band.
     */
    public List<SalaryByBandDTO> getAvgSalaryByBand(LocalDate fromDate, LocalDate toDate, Set<String> companyIds) {
        return querySalaryByBand("AVG", fromDate, toDate, companyIds);
    }

    /**
     * Total salary per career band per month.
     * Used by executive total-salary-development chart.
     *
     * Formula: SUM(MAX(salary) per consultant per month) grouped by band.
     */
    public List<SalaryByBandDTO> getTotalSalaryByBand(LocalDate fromDate, LocalDate toDate, Set<String> companyIds) {
        return querySalaryByBand("SUM", fromDate, toDate, companyIds);
    }

    /**
     * Monthly salary-to-revenue ratio (trailing 18 months).
     * Formula: (SUM(consultant salaries) / net_revenue) * 100
     *
     * Revenue source: fact_company_revenue_mat (canonical invoice-based).
     */
    public List<SalaryCostRatioDTO> getSalaryCostRatio(LocalDate fromDate, LocalDate toDate, Set<String> companyIds) {
        String fromKey = toMonthKey(fromDate);
        String toKey = toMonthKey(toDate);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT r.month_key, ");
        sql.append("  COALESCE(sal.total_salary, 0) AS total_salary, ");
        sql.append("  COALESCE(SUM(r.net_revenue_dkk), 0) AS total_revenue ");
        sql.append("FROM fact_company_revenue_mat r ");
        sql.append("LEFT JOIN ( ");
        sql.append("  SELECT CONCAT(LPAD(yr, 4, '0'), LPAD(mn, 2, '0')) AS month_key, ");
        sql.append("    SUM(monthly_sal) AS total_salary ");
        sql.append("  FROM ( ");
        sql.append("    SELECT fud.year AS yr, fud.month AS mn, ");
        sql.append("      MAX(fud.salary) AS monthly_sal ");
        sql.append("    FROM fact_user_day fud ");
        sql.append("    WHERE fud.consultant_type = 'CONSULTANT' ");
        sql.append("      AND fud.status_type = 'ACTIVE' ");
        sql.append("      AND fud.salary > 0 ");
        sql.append("      AND fud.document_date >= :fromDate ");
        sql.append("      AND fud.document_date <= :toDate ");
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("      AND fud.companyuuid IN (:companyIds) ");
        }
        sql.append("    GROUP BY fud.useruuid, fud.year, fud.month ");
        sql.append("  ) per_user GROUP BY yr, mn ");
        sql.append(") sal ON sal.month_key = r.month_key ");
        sql.append("WHERE r.month_key >= :fromKey AND r.month_key <= :toKey ");
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("AND r.company_id IN (:companyIds) ");
        }
        sql.append("GROUP BY r.month_key, sal.total_salary ");
        sql.append("ORDER BY r.month_key");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        List<SalaryCostRatioDTO> result = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            String monthKey = (String) row.get("month_key");
            int year = Integer.parseInt(monthKey.substring(0, 4));
            int month = Integer.parseInt(monthKey.substring(4));
            double salary = ((Number) row.get("total_salary")).doubleValue();
            double revenue = ((Number) row.get("total_revenue")).doubleValue();
            Double ratio = revenue > 0 ? (salary / revenue) * 100.0 : null;

            result.add(new SalaryCostRatioDTO(
                    monthKey, year, month, formatMonthLabel(year, month),
                    Math.round(salary), Math.round(revenue), ratio
            ));
        }
        return result;
    }

    // ========================================================================
    // Internal
    // ========================================================================

    private List<SalaryByBandDTO> querySalaryByBand(String aggregateFunction, LocalDate fromDate, LocalDate toDate, Set<String> companyIds) {
        String bandCase = CareerBandMapper.toSqlCase("ucl.career_level");

        // Step 1: Per-consultant monthly salary (deduplicate daily rows with MAX)
        // Step 2: Join career level at LAST_DAY of month (consistent resolution)
        // Step 3: Aggregate (AVG or SUM) per band per month
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT per_user.yr AS yr, per_user.mn AS mn, ");
        sql.append(bandCase).append(" AS career_band, ");
        sql.append(aggregateFunction).append("(per_user.monthly_sal) AS salary_value, ");
        sql.append("COUNT(*) AS consultant_count ");
        sql.append("FROM ( ");
        sql.append("  SELECT fud.useruuid, fud.year AS yr, fud.month AS mn, ");
        sql.append("    MAX(fud.salary) AS monthly_sal ");
        sql.append("  FROM fact_user_day fud ");
        sql.append("  WHERE fud.consultant_type = 'CONSULTANT' ");
        sql.append("    AND fud.status_type = 'ACTIVE' ");
        sql.append("    AND fud.salary > 0 ");
        sql.append("    AND fud.document_date >= :fromDate ");
        sql.append("    AND fud.document_date <= :toDate ");
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("    AND fud.companyuuid IN (:companyIds) ");
        }
        sql.append("  GROUP BY fud.useruuid, fud.year, fud.month ");
        sql.append(") per_user ");
        // Join career level effective at end of month
        sql.append("LEFT JOIN user_career_level ucl ON ucl.useruuid = per_user.useruuid ");
        sql.append("  AND ucl.active_from = ( ");
        sql.append("    SELECT MAX(ucl2.active_from) FROM user_career_level ucl2 ");
        sql.append("    WHERE ucl2.useruuid = per_user.useruuid ");
        sql.append("    AND ucl2.active_from <= LAST_DAY(STR_TO_DATE(CONCAT(per_user.yr, LPAD(per_user.mn, 2, '0'), '01'), '%Y%m%d')) ");
        sql.append("  ) ");
        sql.append("GROUP BY per_user.yr, per_user.mn, career_band ");
        sql.append("ORDER BY per_user.yr, per_user.mn, career_band");

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        @SuppressWarnings("unchecked")
        List<Tuple> rows = query.getResultList();

        List<SalaryByBandDTO> result = new ArrayList<>(rows.size());
        for (Tuple row : rows) {
            int year = ((Number) row.get("yr")).intValue();
            int month = ((Number) row.get("mn")).intValue();
            String band = (String) row.get("career_band");
            double salary = row.get("salary_value") != null ? ((Number) row.get("salary_value")).doubleValue() : 0.0;
            int count = ((Number) row.get("consultant_count")).intValue();

            String monthKey = toMonthKey(year, month);
            String monthLabel = formatMonthLabel(year, month);

            result.add(new SalaryByBandDTO(monthKey, year, month, monthLabel, band, Math.round(salary), count));
        }

        return result;
    }

    public static String formatMonthLabel(int year, int month) {
        return Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + year;
    }
}
