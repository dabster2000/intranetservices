package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Backend DTO for Industry Distribution chart.
 *
 * Contains client portfolio distribution by industry segment including
 * client counts, revenue amounts, and percentages for each segment.
 *
 * Used by CxO Dashboard Client Portfolio Tab - Industry Distribution chart.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndustryDistributionDTO {

    /**
     * List of industry segments with their metrics.
     * Sorted by clientCount descending (largest segments first).
     */
    private List<IndustrySegmentDTO> segments;

    /**
     * Total number of clients across all segments.
     * Used for percentage calculations.
     */
    private int totalClients;

    /**
     * Total revenue across all segments in millions DKK.
     * Used for percentage calculations.
     */
    private double totalRevenueM;
}
