package dk.trustworks.intranet.aggregates.people.dto.cxo;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;

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
        CxoSqlSupport.validateMonthBucket(monthKey, monthLabel, year, monthNumber);
        if (consultant < 0 || student < 0 || staff < 0 || total < 0)
            throw new IllegalArgumentException("counts must be non-negative");
        if (total != consultant + student + staff)
            throw new IllegalArgumentException(
                    "total must equal consultant + student + staff, but " + total + " != " +
                            consultant + " + " + student + " + " + staff);
    }
}
