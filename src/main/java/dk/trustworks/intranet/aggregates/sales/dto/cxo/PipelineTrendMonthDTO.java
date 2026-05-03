package dk.trustworks.intranet.aggregates.sales.dto.cxo;

/**
 * One month in the forward-looking weighted-pipeline trend returned by
 * GET /sales/cxo/pipeline-trend.
 *
 * <p>Mirrors the {@code PipelineTrendMonthDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. The window is forward-looking 12 months
 * (current month through current month + 11) because {@code fact_pipeline}
 * only stores open leads with future-dated delivery months — a trailing
 * window would always return empty results.</p>
 *
 * <p>{@code monthKey} is the {@code expected_revenue_month_key} value from
 * fact_pipeline ({@code YYYYMM}). The {@code year} and {@code monthNumber}
 * are derived from {@code expected_revenue_month_key} via {@code LEFT} and
 * {@code RIGHT} CASTs to keep the projection self-contained — the materialized
 * view does expose {@code year} and {@code month_number} columns, but parsing
 * from the key avoids relying on the denormalized form and matches the legacy
 * BFF SQL exactly.</p>
 *
 * <p>{@code monthLabel} is computed server-side via
 * {@code Month.getDisplayName(SHORT, ENGLISH)}.</p>
 */
public record PipelineTrendMonthDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        double weightedPipelineDkk
) {
    public PipelineTrendMonthDTO {
        if (monthKey == null || !monthKey.matches("\\d{6}"))
            throw new IllegalArgumentException("monthKey must be YYYYMM, was " + monthKey);
        if (monthNumber < 1 || monthNumber > 12)
            throw new IllegalArgumentException("monthNumber out of range: " + monthNumber);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
    }
}
