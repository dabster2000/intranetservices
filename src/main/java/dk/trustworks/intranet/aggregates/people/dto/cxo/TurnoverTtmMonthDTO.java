package dk.trustworks.intranet.aggregates.people.dto.cxo;

import dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport;

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
        CxoSqlSupport.validateMonthBucket(monthKey, monthLabel, year, monthNumber);
        if (hires < 0)
            throw new IllegalArgumentException("hires must be non-negative, was " + hires);
        if (terminations < 0)
            throw new IllegalArgumentException("terminations must be non-negative, was " + terminations);
        // net = hires - terminations may legitimately be negative when
        // terminations > hires, so it is intentionally not constrained.
    }
}
