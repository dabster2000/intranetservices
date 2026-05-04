package dk.trustworks.intranet.aggregates.forecast.dto.cxo;

/**
 * Per-stage slice of the pipeline-health view returned inside
 * {@link PipelineHealthMonthDTO#byStage()}.
 *
 * <p>Mirrors the {@code PipelineHealthStageDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. Source rows come from
 * {@code fact_pipeline_snapshot} grouped by snapshot_month and stage_id.</p>
 */
public record PipelineHealthStageDTO(
        String stageId,
        double expectedDkk,
        double weightedDkk,
        long opportunityCount
) {
    public PipelineHealthStageDTO {
        java.util.Objects.requireNonNull(stageId, "stageId");
    }
}
