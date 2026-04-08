package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated overview for a team dashboard: 4 KPIs + roster + attention items.
 */
public record TeamOverviewDTO(
        String teamId,
        String teamName,
        int memberCount,
        /** Team utilization for the current fiscal year so far (SUM billable / SUM net available * 100) */
        Double utilizationPercent,
        /** Revenue for the current fiscal year so far (DKK) */
        double revenueFY,
        /** Total salary cost for the current fiscal year so far (DKK) */
        double salaryCostFY,
        /** Average days since last contract end for bench consultants; null if none on bench */
        Double avgBenchDays,
        List<TeamRosterMemberDTO> roster,
        List<TeamAttentionItemDTO> attentionItems
) {

    public record TeamRosterMemberDTO(
            String userId,
            String firstname,
            String lastname,
            String practice,
            String status,
            Double utilizationPercent,
            boolean hasActiveContract,
            String careerLevel,
            String careerTrack,
            List<RosterContract> activeContracts
    ) {}

    public record RosterContract(
            String clientName,
            String contractName,
            LocalDate activeTo,
            double weeklyHours
    ) {}

    public record TeamAttentionItemDTO(
            String type,
            String severity,
            String userId,
            String firstname,
            String lastname,
            String message
    ) {}
}
