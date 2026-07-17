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
            PayoutInfo payout,
            // Editable-calculation-source fields (spec §6/§4):
            @Schema(description = "This (team, leader) is excluded from the teamlead bonus this FY")
            boolean excluded,
            @Schema(description = "Exclusion note (null when not excluded)") String excludedNote,
            @Schema(description = "Every leader of the team is excluded → team removed entirely")
            boolean teamFullyExcluded,
            @Schema(description = "Full-FY team raw points (same value on all of the team's rows)")
            double teamRawPoints,
            @Schema(description = "Considered months attributed to ANY leader of this team")
            int coveredMonths,
            @Schema(description = "This leader's hybrid slice = weight_L / ΣW (0..1); 1.0 for a sole leader")
            double sharePct
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
