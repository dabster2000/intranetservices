package dk.trustworks.intranet.aggregates.people.dto.cxo;

/**
 * One month in the trailing-24-months headcount-by-type series returned by
 * GET /people/cxo/headcount-growth.
 *
 * <p>Mirrors the {@code HeadcountGrowthMonthDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. For each month, counts users whose most
 * recent {@code userstatus} row on or before month-end has
 * {@code status='ACTIVE'} and {@code type IN ('CONSULTANT', 'STUDENT', 'STAFF')}.</p>
 *
 * <p>Counts are non-negative integers (output of
 * {@code COUNT(DISTINCT useruuid)}); {@code total = consultant + student + staff}
 * is computed server-side. {@code monthLabel} is computed server-side via
 * {@code Month.getDisplayName(SHORT, ENGLISH)}.</p>
 */
public record HeadcountGrowthMonthDTO(
        String monthKey,
        String monthLabel,
        int year,
        int monthNumber,
        long consultant,
        long student,
        long staff,
        long total
) {
    public HeadcountGrowthMonthDTO {
        if (monthKey == null || !monthKey.matches("\\d{6}"))
            throw new IllegalArgumentException("monthKey must be YYYYMM, was " + monthKey);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
        if (monthNumber < 1 || monthNumber > 12)
            throw new IllegalArgumentException("monthNumber out of range: " + monthNumber);
        if (monthLabel == null)
            throw new IllegalArgumentException("monthLabel must not be null");
        if (consultant < 0 || student < 0 || staff < 0 || total < 0)
            throw new IllegalArgumentException("counts must be non-negative");
    }
}
