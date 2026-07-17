package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadDashboardDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadDashboardDTO.LeaderRow;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadDashboardDTO.PayoutInfo;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.TeamleadDashboardDTO.PoolSummary;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.TeamleadBonusPayout;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamBonusProjectionService.LeaderBonusRow;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.TeamBonusProjectionService.TeamleadContext;
import dk.trustworks.intranet.domain.user.entity.Team;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the teamlead-bonus admin dashboard for a fiscal year: the effective config, the pool
 * summary (basis composition + price-per-point), and one leader row per teamleadbonus team, each
 * annotated with an existing payout (if any). All heavy computation is delegated to
 * {@link TeamBonusProjectionService}; this service only maps and looks up payouts.
 */
@JBossLog
@ApplicationScoped
public class TeamleadBonusDashboardService {

    @Inject
    TeamBonusProjectionService projectionService;

    public TeamleadDashboardDTO getDashboard(int fiscalYear) {
        TeamleadContext ctx = projectionService.buildContext(fiscalYear);

        List<Team> teams = Team.list("teamleadbonus = true");
        List<LeaderBonusRow> rows = new ArrayList<>();
        for (Team team : teams) {
            rows.add(projectionService.computeLeaderRow(team, ctx));
        }

        // Dual-leadership guard: if one user leads several teamleadbonus teams, the per-user
        // components (production, split, prepaid) are kept on the deterministic primary row only
        // and stripped from the secondary rows, so KPI/footer totals never double-count them
        // (mirrors TeamleadBonusPayoutService, which pays the combined row once).
        Map<String, List<LeaderBonusRow>> rowsByLeader = new HashMap<>();
        for (LeaderBonusRow row : rows) {
            if (!"unknown".equals(row.leaderUuid())) {
                rowsByLeader.computeIfAbsent(row.leaderUuid(), k -> new ArrayList<>()).add(row);
            }
        }
        Map<String, String> primaryTeamIdByLeader = new HashMap<>();
        rowsByLeader.forEach((leaderUuid, leaderRows) -> {
            if (leaderRows.size() > 1) {
                primaryTeamIdByLeader.put(leaderUuid,
                        TeamBonusProjectionService.selectPrimaryRow(leaderRows).teamId());
            }
        });

        List<LeaderRow> leaders = new ArrayList<>();
        for (LeaderBonusRow row : rows) {
            String primaryTeamId = primaryTeamIdByLeader.get(row.leaderUuid());
            LeaderBonusRow effective = primaryTeamId != null && !primaryTeamId.equals(row.teamId())
                    ? TeamBonusProjectionService.stripPerUserComponents(row)
                    : row;
            leaders.add(toLeaderRow(effective, findPayout(fiscalYear, effective.leaderUuid())));
        }
        leaders.sort(Comparator.comparingDouble(LeaderRow::totalBonus).reversed()
                .thenComparing(LeaderRow::teamName));

        PoolSummary poolSummary = new PoolSummary(
                ctx.poolBasis().teamRevenue(),
                ctx.poolBasis().totalCosts(),
                ctx.poolBasis().excludedSalaries(),
                ctx.poolBasis().estimate(),
                ctx.poolBasis().override(),
                ctx.poolBasis().poolBasis(),
                ctx.poolBasis().basisSource(),
                round(ctx.poolAmount(), 2),
                round(ctx.sumRawPoints(), 6),
                ctx.pricePerPoint(),
                ctx.consideredMonths().size());

        return new TeamleadDashboardDTO(fiscalYear, ctx.config(), poolSummary, leaders);
    }

    private LeaderRow toLeaderRow(LeaderBonusRow row, PayoutInfo payout) {
        return new LeaderRow(
                row.teamId(),
                row.teamName(),
                row.leaderUuid(),
                row.leaderName(),
                row.monthsAsLeader(),
                row.teamAvgUtilization(),
                row.utilOverridden(),
                row.utilOverrideNote(),
                row.avgTeamSize(),
                row.teamFactor(),
                row.rawPoints(),
                row.poolShare(),
                row.adjustedPoolBonus(),
                row.ownRevenue(),
                row.proratedThreshold(),
                row.productionBonus(),
                row.splitBonus(),
                row.prepaidAuto(),
                row.prepaidManual(),
                row.totalBonus(),
                payout);
    }

    private PayoutInfo findPayout(int fiscalYear, String leaderUuid) {
        if (leaderUuid == null || "unknown".equals(leaderUuid)) return null;
        TeamleadBonusPayout p = TeamleadBonusPayout.findByFiscalYearAndUser(fiscalYear, leaderUuid);
        if (p == null) return null;
        return new PayoutInfo(
                p.payoutMonth,
                p.poolAmount,
                p.productionAmount,
                p.splitAmount,
                p.prepaidDeduction,
                p.totalAmount,
                p.createdAt,
                p.createdBy);
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
