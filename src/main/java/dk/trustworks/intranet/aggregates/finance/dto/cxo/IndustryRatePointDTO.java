package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One quarter's rate values for a single industry segment.
 *
 * @param quarterKey     calendar quarter key, e.g. {@code "2026-Q2"}
 * @param avgRateDkk     hours-weighted average billable rate (DKK/hour) of the
 *                       registered work in the quarter; {@code null} when the
 *                       segment has no billable hours in the quarter
 * @param hours          billable hours registered in the quarter
 * @param clientCount    distinct clients with billable hours in the quarter
 */
public record IndustryRatePointDTO(
        String quarterKey,
        Double avgRateDkk,
        double hours,
        int clientCount
) {}
