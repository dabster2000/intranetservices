package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Forecast Utilization - Next 8 Weeks KPI
 *
 * Forward-looking measure of consultant utilization based on scheduled work
 * in the backlog for the next 8 weeks. Indicates pipeline health and capacity planning needs.
 *
 * Formula: Forecast Utilization % = (Forecast Billable Hours / Total Capacity Hours) × 100
 *
 * Provides:
 * - Current 8-week forecast utilization %
 * - Prior 8-week period comparison (weeks -16 to -8)
 * - Percentage point change for trend analysis
 * - Weekly sparkline data (8 data points)
 *
 * Note: This is a user-centric metric (not project-based), so only practice
 * and company filters apply.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ForecastUtilizationDTO {

    /**
     * Total forecast billable hours for next 8 weeks
     */
    private double totalForecastBillableHours;

    /**
     * Total capacity hours for next 8 weeks
     * Capacity = Available work hours minus planned absences
     */
    private double totalCapacityHours;

    /**
     * Forecast utilization percentage for next 8 weeks
     * Formula: (totalForecastBillableHours / totalCapacityHours) × 100
     */
    private double forecastUtilizationPercent;

    /**
     * Prior 8-week period utilization percentage (for comparison)
     * Calculated from weeks -16 to -8 relative to asOfDate
     */
    private double priorForecastUtilizationPercent;

    /**
     * Change in utilization from prior period (percentage points)
     * Example: 72% → 78% = +6.0 percentage points
     */
    private double utilizationChangePct;

    /**
     * Weekly sparkline data - 8 data points representing weekly utilization %
     * Index 0 = current/first week, Index 7 = 8th week
     */
    private double[] weeklySparklineData;
}
