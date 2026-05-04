package dk.trustworks.intranet.aggregates.executive.dto.people;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;

/**
 * One month in the trailing-24-months headcount-by-type series returned by
 * GET /executive/people/headcount-by-type.
 *
 * <p>Mirrors the {@code ExecHeadcountMonthDTO} TypeScript contract in
 * {@code src/lib/types/executive.ts} (the wire interface name). The Java
 * record name is qualified with {@code ByType} for symmetry with the route
 * path; the JSON shape produced by Jackson default serialization matches the
 * TS interface 1:1 (camelCase field names, no {@code @JsonProperty}).</p>
 *
 * <p>Source: {@code userstatus}; for each of the 24 months, counts users
 * whose most-recent {@code userstatus} row on or before month-end has
 * {@code status='ACTIVE'} and {@code type IN ('CONSULTANT', 'STUDENT', 'STAFF',
 * 'EXTERNAL')}. Differs from the {@code /people/cxo/headcount-growth} curve
 * in that {@code EXTERNAL} is included.</p>
 *
 * <p>Counts are non-negative integers from {@code COUNT(DISTINCT useruuid)};
 * {@code total} equals the sum of the four type counts.
 * {@code monthLabel} is computed server-side via
 * {@code Month.getDisplayName(SHORT, ENGLISH)}.</p>
 */
public record ExecHeadcountByTypeMonthDTO(
        String monthKey,
        String monthLabel,
        int year,
        int monthNumber,
        long consultant,
        long student,
        long staff,
        long external,
        long total
) {
    public ExecHeadcountByTypeMonthDTO {
        CxoSqlSupport.validateMonthBucket(monthKey, monthLabel, year, monthNumber);
        if (consultant < 0)
            throw new IllegalArgumentException("consultant must be non-negative: " + consultant);
        if (student < 0)
            throw new IllegalArgumentException("student must be non-negative: " + student);
        if (staff < 0)
            throw new IllegalArgumentException("staff must be non-negative: " + staff);
        if (external < 0)
            throw new IllegalArgumentException("external must be non-negative: " + external);
        if (total < 0)
            throw new IllegalArgumentException("total must be non-negative: " + total);
        if (total != consultant + student + staff + external)
            throw new IllegalArgumentException(
                    "total must equal consultant + student + staff + external, but " + total +
                            " != " + consultant + " + " + student + " + " + staff + " + " + external);
    }
}
