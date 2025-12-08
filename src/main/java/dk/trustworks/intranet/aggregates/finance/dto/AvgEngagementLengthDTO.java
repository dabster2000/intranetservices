package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Average Engagement Length KPI.
 *
 * Measures the portfolio-wide average customer relationship duration in months.
 * Includes year-over-year comparison and monthly sparkline data for trend visualization.
 *
 * Definition: Engagement length is calculated as the duration between the first and last
 * work record (from the work table) for each client, measured in months.
 *
 * Used by CxO Dashboard Client Portfolio Tab.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AvgEngagementLengthDTO {

    /**
     * Portfolio-wide average engagement length in months.
     * Calculated as: AVG(engagement_months) across all clients with work records.
     */
    private double avgEngagementMonths;

    /**
     * Prior year average engagement length in months.
     * Used for year-over-year comparison.
     */
    private double priorYearAvgMonths;

    /**
     * Year-over-year percentage change.
     * Formula: ((avgEngagementMonths - priorYearAvgMonths) / priorYearAvgMonths) * 100
     * Positive = longer engagements, Negative = shorter engagements.
     */
    private double changePercent;

    /**
     * Total count of clients included in the average calculation.
     * Only clients with at least one work record are counted.
     */
    private int totalClientsWithEngagement;

    /**
     * Sparkline data: 12 monthly rolling average values.
     * Array index 0 = oldest month (12 months ago)
     * Array index 11 = most recent month
     * Each value represents the rolling average engagement length at that point in time.
     */
    private double[] sparklineData;
}
