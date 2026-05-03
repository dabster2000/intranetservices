package dk.trustworks.intranet.aggregates.sales.dto.cxo;

/**
 * One future month in the backlog coverage curve returned by
 * GET /sales/cxo/backlog-coverage.
 *
 * <p>Mirrors the {@code BacklogCoverageMonthDTO} TypeScript contract in
 * {@code src/lib/types/cxo.ts}. Window is forward-looking only:
 * {@code delivery_month_key >= currentMonthKey} so the curve shows the declining
 * coverage of signed revenue over future months.</p>
 *
 * <p>{@code consultantCount} is a {@code double} (not a count) because the value
 * is the SUM of {@code consultant_count} across rows — which may legitimately
 * be fractional when fact_backlog stores partial-month allocations. The
 * frontend's {@code number} type accommodates this.</p>
 *
 * <p>{@code monthLabel} is computed server-side via
 * {@code Month.getDisplayName(SHORT, ENGLISH)} so the UI does not need a
 * month-name lookup table.</p>
 */
public record BacklogCoverageMonthDTO(
        String monthKey,
        int year,
        int monthNumber,
        String monthLabel,
        double backlogRevenueDkk,
        double consultantCount
) {
    public BacklogCoverageMonthDTO {
        if (monthKey == null || !monthKey.matches("\\d{6}"))
            throw new IllegalArgumentException("monthKey must be YYYYMM, was " + monthKey);
        if (monthNumber < 1 || monthNumber > 12)
            throw new IllegalArgumentException("monthNumber out of range: " + monthNumber);
        if (year < 2000 || year > 2100)
            throw new IllegalArgumentException("year out of range: " + year);
    }
}
