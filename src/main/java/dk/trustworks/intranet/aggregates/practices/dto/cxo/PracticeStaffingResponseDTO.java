package dk.trustworks.intranet.aggregates.practices.dto.cxo;

import dk.trustworks.intranet.aggregates.utilization.dto.ActualDataStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Group-only staffing response with explainable summary and consultant detail. */
public record PracticeStaffingResponseDTO(
        String plannedMonthKey,
        String priorPlannedMonthKey,
        LocalDate actualFromDate,
        LocalDate actualToDate,
        LocalDate actualThroughDate,
        LocalDate requestedActualThroughDate,
        LocalDate actualDataThroughDate,
        ActualDataStatus actualDataStatus,
        Integer actualSourceLagDays,
        Instant sourceRefreshedAt,
        String practiceAttribution,
        LocalDate practiceAttributionCoverageStartDate,
        String attributionNote,
        List<PracticeStaffingSummaryDTO> practices,
        List<PracticeStaffingConsultantDTO> consultants
) {
    public PracticeStaffingResponseDTO {
        practices = List.copyOf(practices);
        consultants = List.copyOf(consultants);
    }
}
