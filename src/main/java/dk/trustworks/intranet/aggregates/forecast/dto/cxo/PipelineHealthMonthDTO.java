package dk.trustworks.intranet.aggregates.forecast.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * One snapshot month in the pipeline-health view returned by
 * GET /forecast/cxo/pipeline-health.
 *
 * <p>Mirrors the {@code PipelineHealthMonthDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. Source rows come from
 * {@code fact_pipeline_snapshot} (per-stage rows) joined with
 * {@code fact_revenue_budget_mat} (per-month budget target). Aggregation by
 * snapshot month happens in the service layer (mirroring the BFF route's
 * Java-side aggregation).</p>
 *
 * <p>{@code coverageRatio = totalWeightedDkk / budgetTargetDkk} when the
 * budget target is positive, otherwise 0. {@code monthLabel} is computed
 * server-side via {@code Month.getDisplayName(SHORT, ENGLISH)}. {@code byStage}
 * is defensively copied to keep the record immutable.</p>
 */
public record PipelineHealthMonthDTO(
        String month,
        String monthLabel,
        double totalExpectedDkk,
        double totalWeightedDkk,
        double budgetTargetDkk,
        double coverageRatio,
        List<PipelineHealthStageDTO> byStage
) {
    public PipelineHealthMonthDTO {
        if (month == null || !month.matches("\\d{6}"))
            throw new IllegalArgumentException("month must be YYYYMM, was " + month);
        Objects.requireNonNull(monthLabel, "monthLabel");
        byStage = byStage == null ? List.of() : List.copyOf(byStage);
    }
}
