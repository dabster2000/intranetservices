package dk.trustworks.intranet.aggregates.practices.dto.cxo;

public record PracticeContributionPracticeDTO(
        String practiceId,
        String label,
        Metrics current,
        Metrics prior,
        String revenueDeltaDkk,
        String revenueDeltaPct,
        String costDeltaDkk,
        String costDeltaPct,
        String contributionDeltaDkk,
        String contributionDeltaPct,
        String contributionMarginDeltaPoints) {

    public record Metrics(
            String netAttributedRevenueDkk,
            String provisionalNetAttributedRevenueDkk,
            String salaryDkk,
            String opexDkk,
            String operatingCostDkk,
            String contributionDkk,
            String contributionMarginPct,
            String averageFte,
            String costPerFteDkk,
            String confirmedAttributedRevenueDkk,
            String estimatedRevenueDkk,
            String partialAttributionAffectedRevenueDkk,
            String unassignedRevenueDkk,
            String sourceStatus,
            String sourceReason,
            String attributionCoveragePct,
            String valuationStatus,
            String valuationReason,
            String attributionStatus,
            String attributionReason,
            String costCompletenessStatus,
            String costCompletenessReason,
            String fteCompletenessStatus,
            String fteCompletenessReason,
            int expectedFteCellCount,
            int coveredFteCellCount,
            int missingFteCellCount,
            String practiceBasisStatus,
            String practiceBasisReason,
            String contributionStatus,
            String availabilityReason) {
    }
}
