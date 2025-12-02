package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data Transfer Object for Average Revenue Per Client KPI (KPI 4).
 *
 * Measures average revenue generated per active client in the TTM window.
 * Indicates account value and commercial effectiveness.
 *
 * Calculation:
 * - Current TTM Avg Revenue = Total Revenue / Active Client Count
 * - Prior TTM Avg Revenue = Prior Total Revenue / Prior Active Client Count
 * - YoY Change % = ((Current - Prior) / Prior) × 100
 *
 * Uses TTM (trailing 12 months) window for stability.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AvgRevenuePerClientDTO {

    /**
     * Average revenue per client in current TTM period (DKK).
     */
    private double currentTTMAvgRevenue;

    /**
     * Average revenue per client in prior TTM period (DKK).
     * Used for year-over-year comparison.
     */
    private double priorTTMAvgRevenue;

    /**
     * Year-over-year change in average revenue per client (percentage).
     * Positive = growing account values, Negative = declining account values.
     */
    private double yoyChangePercent;

    /**
     * 12-month sparkline data showing monthly average revenue per client (DKK).
     * Array length = 12 (one value per month ending at toDate).
     */
    private double[] sparklineData;
}
