package dk.trustworks.intranet.aggregates.forecast.dto.cxo;

import java.util.Objects;

/**
 * One month in the revenue forecast confidence-band view returned by
 * GET /forecast/cxo/revenue-forecast.
 *
 * <p>Mirrors the {@code RevenueForecastBandMonthDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. The window is trailing 6 months + current +
 * 12 forward months, generated in Java by walking a monthly cursor — every
 * month appears exactly once even if the underlying tables have no row.</p>
 *
 * <p>{@code actualRevenueDkk} is intentionally boxed: it is {@code null} for
 * future months (mirrors TS contract) and may also be {@code null} for past
 * months when the actuals materialised view has no row yet.</p>
 *
 * <p>The three forecast bands are nested layers:
 * <ul>
 *   <li>{@code forecastLowDkk} = backlog (committed delivery)</li>
 *   <li>{@code forecastMidDkk} = backlog + weighted pipeline</li>
 *   <li>{@code forecastHighDkk} = backlog + weighted pipeline + renewal estimate
 *       (60% of expiring revenue from {@code fact_revenue_runoff})</li>
 * </ul>
 */
public record RevenueForecastBandMonthDTO(
        String month,
        String monthLabel,
        Double actualRevenueDkk,
        double budgetDkk,
        double forecastLowDkk,
        double forecastMidDkk,
        double forecastHighDkk
) {
    public RevenueForecastBandMonthDTO {
        if (month == null || !month.matches("\\d{6}"))
            throw new IllegalArgumentException("month must be YYYYMM, was " + month);
        Objects.requireNonNull(monthLabel, "monthLabel");
    }
}
