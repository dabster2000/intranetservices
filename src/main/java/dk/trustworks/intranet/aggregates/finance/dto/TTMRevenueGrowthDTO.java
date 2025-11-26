package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for TTM (Trailing Twelve Months) Revenue Growth % KPI
 *
 * Calculates year-over-year revenue growth:
 * Growth % = ((CurrentTTM - PriorTTM) / PriorTTM) × 100
 *
 * Used by CXO Dashboard Executive Summary Tab
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TTMRevenueGrowthDTO {

    /**
     * Total revenue for current 12-month period (DKK)
     * Example: Dec 2024 - Nov 2025 = 54,000,000 DKK
     */
    private double currentTTMRevenue;

    /**
     * Total revenue for prior 12-month period (DKK)
     * Example: Dec 2023 - Nov 2024 = 50,000,000 DKK
     */
    private double priorTTMRevenue;

    /**
     * Year-over-year growth percentage
     * Example: ((54M - 50M) / 50M) × 100 = 8.0%
     */
    private double growthPercent;

    /**
     * Last 12 months of monthly revenue for sparkline chart (DKK)
     * Array of 12 values from oldest to newest month
     * Example: [4.2M, 4.5M, 4.3M, ..., 4.8M]
     */
    private double[] sparklineData;
}
