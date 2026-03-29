package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Summary of a single team's bonus points for the ranking chart.
 * One entry per bonus-eligible team, sorted by rawPoints descending.
 */
@Schema(name = "AllTeamsBonusRanking", description = "Per-team bonus points for ranking comparison")
public record AllTeamsBonusRankingDTO(
        @Schema(description = "Team UUID") String teamId,
        @Schema(description = "Team name") String teamName,
        @Schema(description = "Leader full name") String leaderName,
        @Schema(description = "Raw points = utilAboveMin * 5 * teamFactor") double rawPoints,
        @Schema(description = "Team factor: <7 = 1.0, 7-10.99 = 1.5, >=11 = 2.0") double teamFactor,
        @Schema(description = "Team average utilization across considered months") double teamAvgUtilization,
        @Schema(description = "Average active member count") int memberCount,
        @Schema(description = "True if this is the requesting user's team") boolean isCurrentTeam
) {}
