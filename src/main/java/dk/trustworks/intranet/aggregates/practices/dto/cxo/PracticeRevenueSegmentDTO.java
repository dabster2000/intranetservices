package dk.trustworks.intranet.aggregates.practices.dto.cxo;

public record PracticeRevenueSegmentDTO(
        String segmentId,
        String label,
        Metrics current,
        Metrics prior,
        String revenueDeltaDkk,
        String revenueDeltaPct) {

    public record Metrics(
            String displayRevenueDkk,
            String revenueDisplayStatus,
            String netAttributedRevenueDkk,
            String provisionalNetAttributedRevenueDkk,
            String confirmedAttributedRevenueDkk,
            String estimatedRevenueDkk,
            String partialAttributionAffectedRevenueDkk,
            String unassignedRevenueDkk,
            String sourceStatus,
            String sourceReason,
            String valuationStatus,
            String valuationReason,
            String attributionStatus,
            String attributionReason,
            String sourceCoveragePct,
            String availabilityReason,
            String explanation) {
    }
}
