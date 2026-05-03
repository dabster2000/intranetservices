package dk.trustworks.intranet.aggregates.executive.dto.people;

/**
 * One month in the trailing-24-months gender diversity trend returned by
 * GET /executive/people/gender-trend.
 *
 * <p>Mirrors the {@code ExecGenderTrendMonthDTO} TypeScript contract in
 * {@code src/lib/types/executive.ts}. Source tables are {@code userstatus} and
 * {@code user}; rows correspond to the most-recent {@code userstatus} row on or
 * before each month-end for active employees ({@code type IN ('CONSULTANT',
 * 'STUDENT', 'STAFF')}, {@code status='ACTIVE'}).</p>
 *
 * <p>{@code maleCount}/{@code femaleCount}/{@code unknownCount} are non-negative
 * integers from {@code COUNT(DISTINCT useruuid)}. {@code femalePct} is computed
 * server-side as
 * {@code round((femaleCount / (maleCount + femaleCount)) * 10000) / 100} —
 * unknown gender is excluded from the denominator. {@code femalePct} is
 * {@code null} when the denominator is zero (no MALE/FEMALE rows that month).
 * {@code monthLabel} is computed server-side via
 * {@code Month.getDisplayName(SHORT, ENGLISH)}.</p>
 */
public record ExecGenderTrendMonthDTO(
        String monthKey,
        String monthLabel,
        int year,
        int monthNumber,
        long maleCount,
        long femaleCount,
        long unknownCount,
        Double femalePct
) {
    public ExecGenderTrendMonthDTO {
        if (monthKey == null || !monthKey.matches("\\d{6}"))
            throw new IllegalArgumentException("monthKey must be YYYYMM, was " + monthKey);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
        if (monthNumber < 1 || monthNumber > 12)
            throw new IllegalArgumentException("monthNumber out of range: " + monthNumber);
        if (monthLabel == null)
            throw new IllegalArgumentException("monthLabel must not be null");
        if (maleCount < 0)
            throw new IllegalArgumentException("maleCount must be non-negative: " + maleCount);
        if (femaleCount < 0)
            throw new IllegalArgumentException("femaleCount must be non-negative: " + femaleCount);
        if (unknownCount < 0)
            throw new IllegalArgumentException("unknownCount must be non-negative: " + unknownCount);
        if (femalePct != null && !Double.isFinite(femalePct))
            throw new IllegalArgumentException("femalePct must be finite or null: " + femalePct);
    }
}
