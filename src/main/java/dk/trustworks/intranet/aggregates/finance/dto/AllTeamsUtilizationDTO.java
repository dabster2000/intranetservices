package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * FY utilization for a single team, used for cross-team ranking.
 */
public record AllTeamsUtilizationDTO(
        String teamId,
        String teamName,
        int memberCount,
        double billableHours,
        double netAvailableHours,
        /** Utilization: billable / netAvailable * 100; null if no available hours */
        Double utilizationPercent,
        /** True if this is the requesting team */
        boolean isCurrentTeam
) {}
