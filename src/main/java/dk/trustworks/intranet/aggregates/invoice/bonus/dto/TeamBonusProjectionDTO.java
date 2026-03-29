package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.List;

/**
 * Complete bonus projection for a team leader within a fiscal year.
 * Includes pool bonus breakdown, production bonus, and monthly utilization detail.
 */
@Schema(name = "TeamBonusProjection", description = "Pool + production bonus projection for a team leader")
public record TeamBonusProjectionDTO(
        @Schema(description = "Fiscal year (starting year)") int fiscalYear,
        @Schema(description = "Team UUID") String teamId,
        @Schema(description = "Team name") String teamName,
        @Schema(description = "Leader UUID") String leaderUuid,
        @Schema(description = "Leader full name") String leaderName,
        @Schema(description = "Pool bonus breakdown") PoolBonusDetail poolBonus,
        @Schema(description = "Production bonus breakdown") ProductionBonusDetail productionBonus,
        @Schema(description = "Combined total bonus (pool + production)") double combinedBonus,
        @Schema(description = "Previous FY bonus total, null if unavailable") Double previousFyBonus
) {

    /**
     * Pool bonus calculation detail with full transparency into intermediate values.
     */
    @Schema(name = "PoolBonusDetail", description = "Pool bonus calculation breakdown")
    public record PoolBonusDetail(
            @Schema(description = "Team average utilization across considered months") double teamAvgUtilization,
            @Schema(description = "Utilization above the 65% minimum threshold") double utilAboveMin,
            @Schema(description = "Average active team member count across considered months") double avgTeamSize,
            @Schema(description = "Team factor: <7 = 1.0, 7-10.99 = 1.5, >=11 = 2.0") double teamFactor,
            @Schema(description = "Raw points = utilAboveMin * 5 * teamFactor") double rawPoints,
            @Schema(description = "Dynamic price per point (DKK)") double pricePerPoint,
            @Schema(description = "Pool share = rawPoints * 100 * pricePerPoint (DKK)") double poolShare,
            @Schema(description = "Months the leader held the role in this FY") int monthsAsLeader,
            @Schema(description = "Prorated pool bonus = (poolShare / 12) * monthsAsLeader (DKK)") double adjustedPoolBonus,
            @Schema(description = "Monthly utilization breakdown for waterfall chart") List<MonthlyUtilization> monthlyUtilization
    ) {}

    /**
     * Single month's utilization snapshot for the team.
     */
    @Schema(name = "MonthlyUtilization", description = "Monthly utilization data point")
    public record MonthlyUtilization(
            @Schema(description = "Month in YYYY-MM format") String month,
            @Schema(description = "Team utilization ratio for this month") double utilization,
            @Schema(description = "Active member count for this month") int memberCount
    ) {}

    /**
     * Production bonus calculation detail.
     */
    @Schema(name = "ProductionBonusDetail", description = "Production bonus calculation breakdown")
    public record ProductionBonusDetail(
            @Schema(description = "Leader's own billable revenue YTD (DKK)") double ownRevenueYTD,
            @Schema(description = "Prorated annual threshold (DKK)") double proratedThreshold,
            @Schema(description = "Production bonus = MAX((revenue - threshold) * 0.20, 0) (DKK)") double productionBonus,
            @Schema(description = "Annualized revenue projection based on completed months (DKK)") double annualizedRevenue
    ) {}
}
