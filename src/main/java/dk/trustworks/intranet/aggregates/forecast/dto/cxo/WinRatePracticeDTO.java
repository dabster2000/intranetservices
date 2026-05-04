package dk.trustworks.intranet.aggregates.forecast.dto.cxo;

/**
 * One per-practice slice within a {@link WinRateStageDTO}, returned by
 * GET /forecast/cxo/win-rates.
 *
 * <p>Mirrors the inner array element of the {@code WinRateStageDTO}
 * TypeScript contract in {@code src/lib/types/cxo.ts}. Each row corresponds to
 * one (stage_id, practice) bucket from {@code fact_historical_win_rates};
 * {@code calibratedPct} is the calibrated win-rate percentage carried through
 * verbatim from the source row.</p>
 */
public record WinRatePracticeDTO(
        String practice,
        double calibratedPct,
        long sampleSize
) {
    public WinRatePracticeDTO {
        java.util.Objects.requireNonNull(practice, "practice");
    }
}
