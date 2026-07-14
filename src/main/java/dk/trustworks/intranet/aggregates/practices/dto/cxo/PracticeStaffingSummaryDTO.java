package dk.trustworks.intranet.aggregates.practices.dto.cxo;

/** Practice staffing risks from planned allocation and completed-day actual utilization. */
public record PracticeStaffingSummaryDTO(
        String practiceId,
        int plannedUnallocatedHeadcount,
        int priorPlannedUnallocatedHeadcount,
        int plannedHeadcountDelta,
        Integer actualUnderutilizedHeadcount,
        Double actualUnusedFte
) {}
