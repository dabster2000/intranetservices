package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.finance.services.DistributionAwareOpexProvider;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.PoolBasisBreakdown;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadBonusConfigDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.SalaryExclusionMode;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusSalaryExclusion;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the pool basis ("Overskud") for a fiscal year (spec §0.1):
 *
 * <pre>estimate = teamRevenue − (totalGroupCosts − excludedSalaries)</pre>
 *
 * <ul>
 *   <li>{@code teamRevenue} is supplied by the caller (registered revenue of MEMBERs of
 *       teamleadbonus teams, teamlead production excluded — see
 *       {@code TeamBonusProjectionService.calculateCompanyRevenue}).</li>
 *   <li>{@code totalGroupCosts} = group-wide OPEX+salaries (from {@link DistributionAwareOpexProvider})
 *       + external GL direct costs (finance_details × accounting_accounts cost_type='DIRECT_COSTS',
 *       intercompany transfer-price accounts excluded), summed over the considered months.</li>
 *   <li>{@code excludedSalaries} = salaries of the excluded group per month (active LEADER on any team
 *       OR career_track PARTNER/C_LEVEL), adjusted by the admin EXCLUDE_SALARY/INCLUDE_SALARY
 *       overrides.</li>
 * </ul>
 *
 * When {@code config.overskudOverride} is set it wins and {@code basisSource = "OVERRIDE"}.
 */
@JBossLog
@ApplicationScoped
public class TeamleadOverskudService {

    @Inject
    EntityManager em;

    @Inject
    DistributionAwareOpexProvider opexProvider;

    /**
     * @param fiscalYear       fiscal year start year
     * @param teamRevenue      registered revenue of teamleadbonus-team MEMBERs (teamleads excluded)
     * @param config           effective configuration (supplies the optional override)
     * @param consideredMonths completed months of the FY (empty ⇒ everything but the override is 0)
     */
    public PoolBasisBreakdown computePoolBasis(int fiscalYear, double teamRevenue,
                                               TeamleadBonusConfigDTO config,
                                               List<YearMonth> consideredMonths) {
        if (consideredMonths.isEmpty()) {
            return buildBreakdown(teamRevenue, 0.0, 0.0, config);
        }

        List<String> monthKeys = consideredMonths.stream()
                .map(ym -> String.format("%04d%02d", ym.getYear(), ym.getMonthValue()))
                .toList();
        String fromKey = monthKeys.getFirst();
        String toKey = monthKeys.getLast();

        double opexSalaries = sumOpexSalaries(fromKey, toKey, monthKeys);
        double glDirectCosts = sumGlDirectCosts(fromKey, toKey);
        double totalCosts = opexSalaries + glDirectCosts;
        double excludedSalaries = sumExcludedSalaries(fiscalYear, monthKeys);

        return buildBreakdown(teamRevenue, totalCosts, excludedSalaries, config);
    }

    // ---- cost components ----

    /** Group-wide OPEX+salaries (settlement-aware), summed over the considered months. */
    private double sumOpexSalaries(String fromKey, String toKey, List<String> monthKeys) {
        Map<String, Double> byMonth = opexProvider.getMonthlyOpex(fromKey, toKey, null, CostSource.BOOKED);
        Set<String> keys = new HashSet<>(monthKeys);
        return byMonth.entrySet().stream()
                .filter(e -> keys.contains(e.getKey()))
                .mapToDouble(Map.Entry::getValue)
                .sum();
    }

    /**
     * External GL direct costs, group-wide, over the considered months. Mirrors
     * {@code CostAnalyticsResource.buildMonthlyGlDirectCostSql}: cost_type='DIRECT_COSTS' with the
     * intercompany transfer-price accounts (3050/3055/3070/3075/1350) excluded.
     */
    private double sumGlDirectCosts(String fromKey, String toKey) {
        Object result = em.createNativeQuery("""
                SELECT COALESCE(SUM(fd.amount), 0.0) AS gl_direct_cost
                FROM finance_details fd
                INNER JOIN accounting_accounts aa
                    ON fd.accountnumber = aa.account_code
                    AND fd.companyuuid  = aa.companyuuid
                WHERE aa.cost_type = 'DIRECT_COSTS'
                  AND fd.accountnumber NOT IN (3050, 3055, 3070, 3075, 1350)
                  AND DATE_FORMAT(fd.expensedate, '%Y%m') BETWEEN :fromKey AND :toKey
                  AND fd.amount != 0
                  AND fd.postingstatus IN (:postingStatuses)
                """)
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .setParameter("postingStatuses", CostSource.BOOKED.postingStatusNames())
                .getSingleResult();
        return result != null ? ((Number) result).doubleValue() : 0.0;
    }

    /**
     * Σ over considered months of {@code fact_salary_monthly.salary_sum} for users in the excluded
     * group that month: (active LEADER teamrole on any team) OR (career_track PARTNER/C_LEVEL),
     * then adjusted by admin overrides (EXCLUDE_SALARY adds a user, INCLUDE_SALARY removes a
     * derived user). Temporal resolution uses the month boundaries derived from {@code month_key}.
     */
    private double sumExcludedSalaries(int fiscalYear, List<String> monthKeys) {
        Set<String> forceExclude = new HashSet<>();
        Set<String> forceInclude = new HashSet<>();
        for (TeamleadBonusSalaryExclusion ex : TeamleadBonusSalaryExclusion.listByFiscalYear(fiscalYear)) {
            if (ex.mode == SalaryExclusionMode.EXCLUDE_SALARY) forceExclude.add(ex.useruuid);
            else if (ex.mode == SalaryExclusionMode.INCLUDE_SALARY) forceInclude.add(ex.useruuid);
        }

        StringBuilder sql = new StringBuilder("""
                SELECT COALESCE(SUM(fsm.salary_sum), 0.0)
                FROM fact_salary_monthly fsm
                WHERE fsm.month_key IN (:monthKeys)
                  AND (
                    EXISTS (
                        SELECT 1 FROM teamroles tr
                        WHERE tr.useruuid = fsm.useruuid
                          AND tr.membertype = 'LEADER'
                          AND tr.startdate <= LAST_DAY(STR_TO_DATE(CONCAT(fsm.month_key, '01'), '%Y%m%d'))
                          AND (tr.enddate IS NULL
                               OR tr.enddate > STR_TO_DATE(CONCAT(fsm.month_key, '01'), '%Y%m%d'))
                    )
                    OR EXISTS (
                        SELECT 1 FROM user_career_level ucl
                        WHERE ucl.useruuid = fsm.useruuid
                          AND ucl.career_track IN ('PARTNER', 'C_LEVEL')
                          AND ucl.active_from <= LAST_DAY(STR_TO_DATE(CONCAT(fsm.month_key, '01'), '%Y%m%d'))
                          AND ucl.active_from = (
                              SELECT MAX(ucl2.active_from) FROM user_career_level ucl2
                              WHERE ucl2.useruuid = fsm.useruuid
                                AND ucl2.active_from <= LAST_DAY(STR_TO_DATE(CONCAT(fsm.month_key, '01'), '%Y%m%d'))
                          )
                    )
                """);
        if (!forceExclude.isEmpty()) sql.append("    OR fsm.useruuid IN (:forceExclude)\n");
        sql.append("  )\n");
        if (!forceInclude.isEmpty()) sql.append("  AND fsm.useruuid NOT IN (:forceInclude)\n");

        var query = em.createNativeQuery(sql.toString()).setParameter("monthKeys", monthKeys);
        if (!forceExclude.isEmpty()) query.setParameter("forceExclude", forceExclude);
        if (!forceInclude.isEmpty()) query.setParameter("forceInclude", forceInclude);

        Object result = query.getSingleResult();
        return result != null ? ((Number) result).doubleValue() : 0.0;
    }

    // ---- assembly ----

    private PoolBasisBreakdown buildBreakdown(double teamRevenue, double totalCosts,
                                              double excludedSalaries, TeamleadBonusConfigDTO config) {
        double estimate = teamRevenue - (totalCosts - excludedSalaries);
        Double override = config.overskudOverride();
        boolean hasOverride = override != null;
        double poolBasis = hasOverride ? override : estimate;
        String source = hasOverride ? PoolBasisBreakdown.SOURCE_OVERRIDE : PoolBasisBreakdown.SOURCE_ESTIMATE;
        return new PoolBasisBreakdown(
                round2(teamRevenue),
                round2(totalCosts),
                round2(excludedSalaries),
                round2(estimate),
                override,
                round2(poolBasis),
                source);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
