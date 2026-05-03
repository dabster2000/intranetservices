package dk.trustworks.intranet.aggregates.people.dto.cxo;

/**
 * One month in the trailing-24-months employee turnover series returned by
 * GET /people/cxo/turnover-ttm.
 *
 * <p>Mirrors the {@code TurnoverMonthDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. Source table is {@code userstatus}; rows
 * correspond to {@code statusdate} entries within the trailing 24 months
 * for {@code type IN ('CONSULTANT', 'STUDENT', 'STAFF')}.</p>
 *
 * <p>Counts are non-negative integers because they are
 * {@code SUM(CASE WHEN status = '...' THEN 1 ELSE 0 END)}; {@code net} is
 * computed server-side as {@code hires - terminations} and may be negative.
 * {@code monthLabel} is computed server-side via
 * {@code Month.getDisplayName(SHORT, ENGLISH)}.</p>
 */
public record TurnoverTtmMonthDTO(
        String monthKey,
        String monthLabel,
        int year,
        int monthNumber,
        long hires,
        long terminations,
        long net
) {
    public TurnoverTtmMonthDTO {
        if (monthKey == null || !monthKey.matches("\\d{6}"))
            throw new IllegalArgumentException("monthKey must be YYYYMM, was " + monthKey);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
        if (monthNumber < 1 || monthNumber > 12)
            throw new IllegalArgumentException("monthNumber out of range: " + monthNumber);
        if (monthLabel == null)
            throw new IllegalArgumentException("monthLabel must not be null");
    }
}
