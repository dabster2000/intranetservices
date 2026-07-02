package dk.trustworks.intranet.aggregates.finance.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated overview for a team dashboard: 4 KPIs + roster + attention items.
 */
public record TeamOverviewDTO(
        String teamId,
        String teamName,
        /** Current members of ANY type (snapshot) — always equals roster.size() so the
         *  headcount KPI reconciles with the roster shown on the same screen */
        int memberCount,
        /** Team utilization for the selected FY window (SUM billable / SUM net available * 100),
         *  temporal team membership; null when the team has no consultant net-available hours */
        Double utilizationPercent,
        /** Revenue for the current fiscal year so far (DKK) */
        double revenueFY,
        /** Total salary cost for the current fiscal year so far (DKK) */
        double salaryCostFY,
        /** Average days since last contract end for bench consultants; null if none on bench */
        Double avgBenchDays,
        /** Number of CONSULTANT-type members currently on bench (no active contract) */
        int benchCount,
        List<TeamRosterMemberDTO> roster,
        List<TeamAttentionItemDTO> attentionItems
) {

    public record TeamRosterMemberDTO(
            String userId,
            String firstname,
            String lastname,
            String practice,
            String status,
            /** Current userstatus type: CONSULTANT, STAFF, STUDENT, EXTERNAL */
            String consultantType,
            /** FY-window utilization while a member of THIS team (temporal bounding);
             *  null = not measured (no consultant net-available hours) — render N/A, not 0% */
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
