package dk.trustworks.intranet.aggregates.forecast.dto.cxo;

/**
 * One per-deal-type slice within a {@link WinRateStageDTO}, returned by
 * GET /forecast/cxo/win-rates.
 *
 * <p>Mirrors the inner array element of the {@code WinRateStageDTO}
 * TypeScript contract in {@code src/lib/types/cxo.ts}. The BFF dedupes deal
 * types per stage (first occurrence wins); this service mirrors that
 * behaviour so each {@code (stageId, dealType)} pair appears at most once.</p>
 */
public record WinRateDealTypeDTO(
        String dealType,
        double calibratedPct,
        long sampleSize
) {}
