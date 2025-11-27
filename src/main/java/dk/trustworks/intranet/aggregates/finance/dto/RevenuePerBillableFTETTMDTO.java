package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Revenue per Billable FTE (TTM) KPI
 *
 * Measures revenue efficiency per billable full-time equivalent employee.
 * Formula: Revenue per FTE = Total TTM Revenue / Average Billable FTE Count
 *
 * Provides:
 * - Current TTM revenue per FTE
 * - Prior period comparison (year-over-year)
 * - Change percentage for trend analysis
 * - 12-month sparkline for visualization
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevenuePerBillableFTETTMDTO {

    /**
     * Total revenue for current 12-month period (DKK)
     */
    private double currentTTMRevenue;

    /**
     * Average billable FTE count over current 12-month period
     * FTE = Full-Time Equivalent (1 FTE = 160.33 hours/month)
     */
    private double currentAvgBillableFTE;

    /**
     * Current revenue per billable FTE (DKK)
     * Formula: currentTTMRevenue / currentAvgBillableFTE
     */
    private double currentRevenuePerFTE;

    /**
     * Total revenue for prior 12-month period (DKK)
     */
    private double priorTTMRevenue;

    /**
     * Average billable FTE count over prior 12-month period
     */
    private double priorAvgBillableFTE;

    /**
     * Prior revenue per billable FTE (DKK)
     * Formula: priorTTMRevenue / priorAvgBillableFTE
     */
    private double priorRevenuePerFTE;

    /**
     * Change in revenue per FTE from prior period (percentage)
     * Example: 2.1M kr → 2.3M kr = +9.5% change
     */
    private double revenuePerFTEChangePct;

    /**
     * Last 12 months of monthly revenue per FTE for sparkline chart
     * Array contains revenue per FTE values in DKK
     */
    private double[] sparklineData;
}
