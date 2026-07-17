package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full teamlead-bonus admin dashboard payload for one fiscal year (spec §3). One {@link LeaderRow}
 * per teamleadbonus team's FY leader; teams with an unknown leader appear with leaderUuid
 * {@code "unknown"} and zeroed figures.
 */
@Schema(name = "TeamleadDashboard", description = "Teamlead bonus admin dashboard for a fiscal year")
public record TeamleadDashboardDTO(
        int fiscalYear,
        TeamleadBonusConfigDTO config,
        PoolSummary poolSummary,
        List<LeaderRow> leaders
) {

    @Schema(name = "TeamleadPoolSummary", description = "Pool amount and basis composition")
    public record PoolSummary(
            double teamRevenue,
            double totalCosts,
            double excludedSalaries,
            double estimate,
            Double overskudOverride,
            double poolBasis,
            String basisSource,
            double poolAmount,
            double sumRawPoints,
            double pricePerPoint,
            int consideredMonths
    ) {}

    @Schema(name = "TeamleadLeaderRow", description = "Per-team leader bonus row")
    public record LeaderRow(
            String teamId,
            String teamName,
            String leaderUuid,
            String leaderName,
            int monthsAsLeader,
            double teamAvgUtilization,
            boolean utilOverridden,
            String utilOverrideNote,
            double avgTeamSize,
            double teamFactor,
            double rawPoints,
            double poolShare,
            double adjustedPoolBonus,
            double ownRevenue,
            double proratedThreshold,
            double productionBonus,
            double splitBonus,
            double prepaidAuto,
            double prepaidManual,
            double totalBonus,
            PayoutInfo payout
    ) {}

    @Schema(name = "TeamleadPayoutInfo", description = "Existing payout for a leader (null when unpaid)")
    public record PayoutInfo(
            LocalDate payoutMonth,
            double poolAmount,
            double productionAmount,
            double splitAmount,
            double prepaidDeduction,
            double totalAmount,
            LocalDateTime createdAt,
            String createdBy
    ) {}
}
