package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Realization Rate % (Firm-wide) KPI
 *
 * Measures how much of the expected billable value (at contracted rates) is actually invoiced.
 * Formula: Realization Rate = (Actual Billed Revenue / Expected Revenue at Contract Rate) × 100
 *
 * Expected Revenue = Σ(workduration × rate) from work_full entries
 * Actual Revenue = Σ(recognized_revenue_dkk) from fact_project_financials
 *
 * A rate below 100% indicates value leakage through:
 * - Discounts given after work performed
 * - Write-offs / unbilled time
 * - Contract renegotiations
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RealizationRateDTO {

    /**
     * Actual billed/invoiced revenue for current TTM period (DKK)
     * Source: fact_project_financials.recognized_revenue_dkk
     */
    private double currentBilledRevenue;

    /**
     * Expected revenue at contracted rates for current TTM period (DKK)
     * Source: SUM(workduration × rate) from work_full
     */
    private double currentExpectedRevenue;

    /**
     * Current realization rate percentage
     * Formula: (currentBilledRevenue / currentExpectedRevenue) × 100
     */
    private double currentRealizationPercent;

    /**
     * Prior period realization rate percentage (for YoY comparison)
     */
    private double priorRealizationPercent;

    /**
     * Change in realization rate (percentage points)
     * Example: 97.5% → 98.8% = +1.3 percentage points
     */
    private double realizationChangePct;

    /**
     * Last 12 months of monthly realization rate percentages for sparkline chart
     * Array contains realization % values for each month
     */
    private double[] sparklineData;
}
