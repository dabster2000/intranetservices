package dk.trustworks.intranet.aggregates.practices.dto.cxo;

/** Consultant-level evidence for a planned-unallocated or actually underutilized staffing signal. */
public record PracticeStaffingConsultantDTO(
        String userUuid,
        String fullName,
        String practiceId,
        boolean hasPlannedEvidence,
        Double plannedBudgetHours,
        Double plannedNetAvailableHours,
        Double plannedUtilizationPct,
        boolean plannedUnallocated,
        boolean hasActualEvidence,
        Double actualBillableHours,
        Double actualNetAvailableHours,
        Double actualUtilizationPct,
        Double actualUnusedFte
) {}
