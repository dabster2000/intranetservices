package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Billable Utilization - Last 4 Weeks KPI
 *
 * Measures operational efficiency by tracking billable hours as a percentage of
 * total available hours over a rolling 4-week window.
 *
 * Formula: Utilization % = (Billable Hours / Total Available Hours) × 100
 *
 * Provides:
 * - Current 4-week utilization %
 * - Prior 4-week period comparison (weeks -8 to -4)
 * - Percentage point change for trend analysis
 *
 * Note: This is a user-centric metric (not project-based), so only practice
 * and company filters apply.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BillableUtilizationLast4WeeksDTO {

    /**
     * Total billable hours for current 4-week period (last 28 days)
     */
    private double currentBillableHours;

    /**
     * Total available hours for current 4-week period (last 28 days)
     * Available hours = Total working hours - Absence hours
     */
    private double currentAvailableHours;

    /**
     * Current billable utilization percentage
     * Formula: (currentBillableHours / currentAvailableHours) × 100
     */
    private double currentUtilizationPercent;

    /**
     * Total billable hours for prior 4-week period (weeks -8 to -4)
     */
    private double priorBillableHours;

    /**
     * Total available hours for prior 4-week period (weeks -8 to -4)
     */
    private double priorAvailableHours;

    /**
     * Prior billable utilization percentage
     * Formula: (priorBillableHours / priorAvailableHours) × 100
     */
    private double priorUtilizationPercent;

    /**
     * Change in utilization from prior period (percentage points)
     * Example: 82% → 85% = +3.0 percentage points
     */
    private double utilizationChangePct;
}
