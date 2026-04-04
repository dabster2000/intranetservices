package dk.trustworks.intranet.aggregates.finance.services.analytics;

import dk.trustworks.intranet.aggregates.finance.dto.OpexRow;
import dk.trustworks.intranet.aggregates.finance.services.DistributionAwareOpexProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/**
 * Analytics layer over {@link DistributionAwareOpexProvider}.
 *
 * Ensures ALL OPEX queries go through the FY-aware distribution algorithm,
 * fixing the inconsistency where team dashboard previously queried
 * fact_opex_mat directly.
 */
@JBossLog
@ApplicationScoped
public class OpexAnalyticsProvider {

    @Inject
    DistributionAwareOpexProvider distributionProvider;

    @Inject
    EntityManager em;

    /**
     * Total OPEX amount for the given period (FY-aware).
     * Filters to non-payroll rows only (excludes SALARIES).
     */
    public double getTotalOpex(String fromKey, String toKey, Set<String> companyIds) {
        List<OpexRow> rows = distributionProvider.getDistributionAwareOpex(
                fromKey, toKey, companyIds, null, null);
        return rows.stream()
                .filter(r -> !r.isPayrollFlag())
                .mapToDouble(OpexRow::opexAmountDkk)
                .sum();
    }

    /**
     * Overhead per active consultant (total OPEX / headcount / months).
     * Uses active consultant count from fact_user_day for consistent denominator.
     */
    public double getOverheadPerConsultant(String fromKey, String toKey, Set<String> companyIds) {
        double totalOpex = getTotalOpex(fromKey, toKey, companyIds);

        // Count active consultants across period
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(DISTINCT fud.useruuid) AS headcount ");
        sql.append("FROM fact_user_day fud ");
        sql.append("WHERE fud.consultant_type = 'CONSULTANT' ");
        sql.append("  AND fud.status_type = 'ACTIVE' ");
        sql.append("  AND fud.document_date >= STR_TO_DATE(CONCAT(:fromKey, '01'), '%Y%m%d') ");
        sql.append("  AND fud.document_date <= LAST_DAY(STR_TO_DATE(CONCAT(:toKey, '01'), '%Y%m%d')) ");
        if (companyIds != null && !companyIds.isEmpty()) {
            sql.append("  AND fud.companyuuid IN (:companyIds) ");
        }

        var query = em.createNativeQuery(sql.toString(), Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        if (companyIds != null && !companyIds.isEmpty()) {
            query.setParameter("companyIds", companyIds);
        }

        Tuple result = (Tuple) query.getSingleResult();
        long headcount = result.get("headcount") != null ? ((Number) result.get("headcount")).longValue() : 0;

        if (headcount == 0) return 0.0;

        // Calculate months in range
        YearMonth from = YearMonth.of(
                Integer.parseInt(fromKey.substring(0, 4)),
                Integer.parseInt(fromKey.substring(4)));
        YearMonth to = YearMonth.of(
                Integer.parseInt(toKey.substring(0, 4)),
                Integer.parseInt(toKey.substring(4)));
        long months = ChronoUnit.MONTHS.between(from, to) + 1;

        return totalOpex / headcount / months;
    }

    /**
     * Team's allocated share of total OPEX.
     * Formula: totalOpex * (teamMemberCount / totalActiveConsultants)
     */
    public double getTeamAllocatedOpex(String teamId, String fromKey, String toKey, Set<String> companyIds) {
        double totalOpex = getTotalOpex(fromKey, toKey, companyIds);

        // Team member count (current)
        String teamSql = "SELECT COUNT(DISTINCT tr.useruuid) AS team_count " +
                "FROM teamroles tr " +
                "WHERE tr.teamuuid = :teamId AND tr.membertype = 'MEMBER' " +
                "  AND tr.startdate <= CURDATE() " +
                "  AND (tr.enddate IS NULL OR tr.enddate > CURDATE())";

        long teamCount = ((Number) em.createNativeQuery(teamSql)
                .setParameter("teamId", teamId)
                .getSingleResult()).longValue();

        // Total active consultants
        String totalSql = "SELECT COUNT(DISTINCT fud.useruuid) AS total_count " +
                "FROM fact_user_day fud " +
                "WHERE fud.consultant_type = 'CONSULTANT' AND fud.status_type = 'ACTIVE' " +
                "  AND fud.document_date >= STR_TO_DATE(CONCAT(:fromKey, '01'), '%Y%m%d') " +
                "  AND fud.document_date <= LAST_DAY(STR_TO_DATE(CONCAT(:toKey, '01'), '%Y%m%d'))";

        long totalConsultants = ((Number) em.createNativeQuery(totalSql)
                .setParameter("fromKey", fromKey)
                .setParameter("toKey", toKey)
                .getSingleResult()).longValue();

        if (totalConsultants == 0) return 0.0;
        return totalOpex * ((double) teamCount / totalConsultants);
    }
}
