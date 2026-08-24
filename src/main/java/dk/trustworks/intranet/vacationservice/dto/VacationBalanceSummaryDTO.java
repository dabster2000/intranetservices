package dk.trustworks.intranet.vacationservice.dto;

/** Aggregated across open pools — one grid row per employee. */
public record VacationBalanceSummaryDTO(
        String useruuid,
        String fullname,
        double ferieRemaining,
        double feriefridageRemaining,
        double totalRemaining,
        double pendingDays,
        double plannedDays,
        int warningCount) {
}
