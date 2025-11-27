package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Repeat Business Share KPI
 *
 * Measures the percentage of revenue from clients with multiple projects (≥2 projects)
 * in the last 24 months, indicating business diversification and client stickiness.
 *
 * Formula: Repeat Business Share % = (Repeat Client Revenue / Total Revenue) × 100
 *
 * Repeat Client Definition: Client with ≥2 distinct projects in the 24-month window
 *
 * Used by CxO Dashboard Executive Summary Tab.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepeatBusinessShareDTO {

    /**
     * Total recognized revenue in the 24-month window (DKK)
     */
    private double totalRevenue;

    /**
     * Revenue from repeat clients (clients with ≥2 projects) in DKK
     */
    private double repeatClientRevenue;

    /**
     * Current repeat business share percentage
     * Formula: (repeatClientRevenue / totalRevenue) × 100
     */
    private double currentRepeatSharePercent;

    /**
     * Total revenue in prior 24-month window (for period comparison)
     */
    private double priorTotalRevenue;

    /**
     * Revenue from repeat clients in prior 24-month window
     */
    private double priorRepeatClientRevenue;

    /**
     * Prior period repeat business share percentage
     */
    private double priorRepeatSharePercent;

    /**
     * Change in repeat business share from prior period (percentage points)
     * Example: 65% → 70% = +5.0 percentage points
     */
    private double repeatShareChangePct;

    /**
     * 12-month sparkline data (monthly repeat business share percentages)
     * Array of 12 values representing the last 12 months of repeat business share %
     */
    private double[] sparklineData;
}
