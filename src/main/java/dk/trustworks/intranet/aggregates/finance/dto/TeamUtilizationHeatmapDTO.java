package dk.trustworks.intranet.aggregates.finance.dto;

import java.util.List;

/**
 * Per-member per-month utilization matrix for a team heatmap.
 */
public record TeamUtilizationHeatmapDTO(
        List<String> months,
        List<MemberUtilizationRow> members
) {

    public record MemberUtilizationRow(
            String userId,
            String firstname,
            String lastname,
            /** One entry per month in the months list, same order. Null if no data for that month. */
            List<Double> utilizationByMonth
    ) {}
}
