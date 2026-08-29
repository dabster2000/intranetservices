package dk.trustworks.intranet.aggregates.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for Realization Rate (TTM) KPI.
 * Returns realization rate percentage and year-over-year comparison.
 *
 * Realization Rate = (Billed Value / Expected Value) * 100
 * - Expected Value = SUM(workduration × contract_rate) where rate &gt; 0, from work_full_optimized
 * - Billed Value = SUM(recognized_revenue_dkk) from fact_project_financials_mat,
 *   deduplicated per (project_id, month_key)
 *
 * Internal Trustworks work is excluded on both sides — it is never invoiced.
 *
 * Use Case: Measures value leakage - how much of the work performed at contracted rates
 * actually turned into invoiced revenue. A rate below 100% indicates leakage through
 * discounts given after the work, write-offs, or unbilled time.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RealizationRateDTO {
    /**
     * Current TTM realization rate percentage (0-100+)
     */
    private double currentTTMPercent;

    /**
     * Prior year TTM realization rate percentage (0-100+)
     */
    private double priorTTMPercent;

    /**
     * Year-over-year change in percentage points (NOT percentage)
     * Example: 85% → 90% = +5.0 points
     */
    private double yoyChangePoints;

    /**
     * 12-month sparkline showing monthly realization rates
     * Array[0] = oldest month (12 months ago)
     * Array[11] = most recent month
     */
    private double[] sparklineData;
}
