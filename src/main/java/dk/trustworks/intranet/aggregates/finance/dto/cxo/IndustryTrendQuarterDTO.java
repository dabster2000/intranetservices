package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One x-axis bucket of the industry revenue trend chart.
 *
 * @param quarterKey       calendar quarter key, e.g. {@code "2026-Q3"}
 * @param year             calendar year
 * @param quarterNumber    calendar quarter 1–4
 * @param phase            {@code "ACTUAL"} for full past quarters, {@code "FORECAST"}
 *                         for the current quarter and later
 * @param fiscalYearLabel  {@code "FY26/27"} when this quarter starts a Trustworks
 *                         fiscal year (July quarters); {@code null} otherwise
 */
public record IndustryTrendQuarterDTO(
        String quarterKey,
        int year,
        int quarterNumber,
        String phase,
        String fiscalYearLabel
) {
    public static final String PHASE_ACTUAL = "ACTUAL";
    public static final String PHASE_FORECAST = "FORECAST";
}
