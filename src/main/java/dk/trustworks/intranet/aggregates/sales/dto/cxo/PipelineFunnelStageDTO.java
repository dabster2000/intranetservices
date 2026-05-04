package dk.trustworks.intranet.aggregates.sales.dto.cxo;

/**
 * One pipeline stage in the funnel returned by GET /sales/cxo/pipeline-funnel.
 *
 * <p>Mirrors the {@code PipelineFunnelStageDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. Stage rows are emitted in the canonical funnel
 * order (DETECTED → QUALIFIED → SHORTLISTED → PROPOSAL → NEGOTIATION) via a
 * {@code FIELD()} ORDER BY in the source query.</p>
 *
 * <p>{@code stageLabel} is the human-readable display label resolved server-side
 * from {@code stageId} so the UI does not need a stage-label dictionary. Unknown
 * stage IDs fall back to the raw value.</p>
 *
 * <p>{@code opportunityCount} is a {@code long} because {@code COUNT(DISTINCT)}
 * returns BIGINT in MariaDB; the frontend's {@code number} type accommodates
 * the wider range.</p>
 */
public record PipelineFunnelStageDTO(
        String stageId,
        String stageLabel,
        double expectedRevenueDkk,
        double weightedPipelineDkk,
        long opportunityCount
) {
    public PipelineFunnelStageDTO {
        java.util.Objects.requireNonNull(stageId, "stageId");
        java.util.Objects.requireNonNull(stageLabel, "stageLabel");
    }
}
