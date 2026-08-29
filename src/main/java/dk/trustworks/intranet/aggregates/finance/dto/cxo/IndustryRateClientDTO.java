package dk.trustworks.intranet.aggregates.finance.dto.cxo;

/**
 * One client row in an industry's rate drill-down panel.
 *
 * @param clientUuid                 client UUID; {@code null} for the synthetic
 *                                   "(Unattributed)" bucket
 * @param clientName                 display name
 * @param windowHours                billable hours across the 12-quarter window
 * @param windowAvgRateDkk           hours-weighted average rate across the window
 * @param latestQuarterHours         billable hours in the latest full quarter
 * @param latestQuarterAvgRateDkk    hours-weighted average rate in the latest
 *                                   full quarter; {@code null} when no hours
 */
public record IndustryRateClientDTO(
        String clientUuid,
        String clientName,
        double windowHours,
        double windowAvgRateDkk,
        double latestQuarterHours,
        Double latestQuarterAvgRateDkk
) {}
