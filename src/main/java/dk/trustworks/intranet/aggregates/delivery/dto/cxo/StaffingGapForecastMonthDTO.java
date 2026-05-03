package dk.trustworks.intranet.aggregates.delivery.dto.cxo;

/**
 * One month in the 12-month staffing supply-vs-demand forecast returned by
 * GET /delivery/cxo/staffing-gap-forecast.
 *
 * <p>Mirrors the {@code MonthlyStaffingGapDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. Source tables are {@code fact_user_day}
 * (active consultant headcount → supply) and {@code fact_backlog} (booked
 * consultant FTEs → demand).</p>
 *
 * <p>Series spans the current month plus the next 11 months. For months that
 * have no row in {@code fact_user_day}, supply is forward-filled from the
 * latest non-zero supply value (matches BFF semantics). {@code gapFte} is
 * computed server-side as {@code supplyFte - demandFte}: positive values
 * indicate surplus capacity, negative values indicate a deficit.
 * {@code monthLabel} is computed server-side; {@code isForecast} is
 * {@code true} for every month after the current month.</p>
 */
public record StaffingGapForecastMonthDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        double supplyFte,
        double demandFte,
        double gapFte,
        boolean isForecast
) {
    public StaffingGapForecastMonthDTO {
        if (monthKey == null || !monthKey.matches("\\d{6}"))
            throw new IllegalArgumentException("monthKey must be YYYYMM, was " + monthKey);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
        if (monthNumber < 1 || monthNumber > 12)
            throw new IllegalArgumentException("monthNumber out of range: " + monthNumber);
        if (monthLabel == null)
            throw new IllegalArgumentException("monthLabel must not be null");
        if (!Double.isFinite(supplyFte))
            throw new IllegalArgumentException("supplyFte must be finite: " + supplyFte);
        if (!Double.isFinite(demandFte))
            throw new IllegalArgumentException("demandFte must be finite: " + demandFte);
        if (!Double.isFinite(gapFte))
            throw new IllegalArgumentException("gapFte must be finite: " + gapFte);
    }
}
