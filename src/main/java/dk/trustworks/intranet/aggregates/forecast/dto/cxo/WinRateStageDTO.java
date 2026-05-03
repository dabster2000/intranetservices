package dk.trustworks.intranet.aggregates.forecast.dto.cxo;

import java.util.List;
import java.util.Objects;

/**
 * One pipeline stage in the win-rate calibration view returned by
 * GET /forecast/cxo/win-rates.
 *
 * <p>Mirrors the {@code WinRateStageDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. Source rows from
 * {@code fact_historical_win_rates} are grouped by {@code stage_id};
 * the per-row {@code practice} and {@code deal_type} columns expand into
 * the {@code byPractice} and {@code byDealType} arrays.</p>
 *
 * <p>The aggregate {@code calibratedWinRatePct} is recomputed from the
 * summed {@code wonCount}/{@code reachedCount} totals (BFF parity, route
 * lines 128-134); {@code deltaPct} is the recomputed value minus
 * {@code staticProbabilityPct}. Stage rows are emitted in canonical funnel
 * order via {@code ORDER BY FIELD(...)} in the source query and a
 * {@code LinkedHashMap} preserves that insertion order.</p>
 */
public record WinRateStageDTO(
        String stageId,
        String stageLabel,
        double calibratedWinRatePct,
        double staticProbabilityPct,
        double deltaPct,
        long sampleSize,
        long wonCount,
        long reachedCount,
        List<WinRatePracticeDTO> byPractice,
        List<WinRateDealTypeDTO> byDealType
) {
    public WinRateStageDTO {
        Objects.requireNonNull(stageId, "stageId");
        byPractice = byPractice == null ? List.of() : List.copyOf(byPractice);
        byDealType = byDealType == null ? List.of() : List.copyOf(byDealType);
    }
}
