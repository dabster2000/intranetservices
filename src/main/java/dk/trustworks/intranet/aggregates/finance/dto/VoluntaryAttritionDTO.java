package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Voluntary Attrition - 12-Month Rolling KPI
 *
 * Measures the 12-month rolling rate of employees who voluntarily left the organization.
 * Key indicator of employee satisfaction and organizational health.
 *
 * Formula: Voluntary Attrition % = (Voluntary Leavers / Average Headcount) × 100
 *
 * Provides:
 * - Current 12-month attrition %
 * - Prior 12-month period comparison (months -24 to -12)
 * - Percentage point change for trend analysis
 * - Monthly sparkline data (12 data points)
 *
 * Note: This is a user-centric metric (not project-based), so only practice
 * and company filters apply.
 *
 * Known limitation: Currently all leavers are counted as "voluntary" since
 * the userstatus table lacks a termination_reason field. Enhancement needed
 * when HR system captures termination reason.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoluntaryAttritionDTO {

    /**
     * Total voluntary leavers in the current 12-month window
     */
    private int totalVoluntaryLeavers12m;

    /**
     * Average headcount across the 12-month window
     * Calculated as average of monthly (headcount_start + headcount_end) / 2
     */
    private double averageHeadcount12m;

    /**
     * Current 12-month attrition percentage
     * Formula: (totalVoluntaryLeavers12m / averageHeadcount12m) × 100
     */
    private double currentAttritionPercent;

    /**
     * Prior 12-month period attrition percentage (for comparison)
     * Calculated from months -24 to -12 relative to asOfDate
     */
    private double priorAttritionPercent;

    /**
     * Change in attrition from prior period (percentage points)
     * Example: 12% → 10% = -2.0 percentage points (improvement)
     * Note: Negative change is good (decreasing attrition)
     */
    private double attritionChangePct;

    /**
     * Monthly sparkline data - 12 data points representing monthly attrition %
     * Each value is the rolling 12-month attrition as of that month end
     * Index 0 = oldest month, Index 11 = current month
     */
    private double[] monthlySparklineData;
}
