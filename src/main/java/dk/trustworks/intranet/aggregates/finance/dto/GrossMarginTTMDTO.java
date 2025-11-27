package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Gross Margin % (TTM) KPI
 *
 * Provides trailing twelve months gross margin calculation with:
 * - Current TTM revenue and cost
 * - Current margin percentage
 * - Prior period comparison (year-over-year)
 * - Monthly margin sparkline (12 data points)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GrossMarginTTMDTO {

    /**
     * Total revenue for current 12-month period (DKK)
     */
    private double currentTTMRevenue;

    /**
     * Total direct delivery costs for current 12-month period (DKK)
     */
    private double currentTTMCost;

    /**
     * Current gross margin percentage
     * Formula: ((revenue - cost) / revenue) × 100
     */
    private double currentMarginPercent;

    /**
     * Total revenue for prior 12-month period (DKK)
     */
    private double priorTTMRevenue;

    /**
     * Total direct delivery costs for prior 12-month period (DKK)
     */
    private double priorTTMCost;

    /**
     * Prior gross margin percentage
     * Formula: ((revenue - cost) / revenue) × 100
     */
    private double priorMarginPercent;

    /**
     * Margin change in percentage points
     * Example: 35.2% → 38.1% = +2.9 percentage points
     */
    private double marginChangePct;

    /**
     * Last 12 months of monthly margin percentages for sparkline chart
     * Array contains margin % values (not revenue values)
     */
    private double[] sparklineData;
}
