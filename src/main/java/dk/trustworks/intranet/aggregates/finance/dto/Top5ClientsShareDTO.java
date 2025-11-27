package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Top 5 Clients' Revenue Share KPI
 *
 * Measures the percentage of revenue from the 5 largest clients in the trailing twelve months (TTM),
 * indicating revenue concentration risk and client diversification health.
 *
 * Formula: Top 5 Share % = (Top 5 Clients Revenue / Total Revenue) × 100
 *
 * Status Logic: INVERTED - Lower concentration is better (lower risk, better diversification)
 *
 * Used by CxO Dashboard Executive Summary Tab.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Top5ClientsShareDTO {

    /**
     * Total recognized revenue in the TTM window (DKK)
     */
    private double totalRevenue;

    /**
     * Revenue from top 5 clients in the TTM window (DKK)
     */
    private double top5ClientsRevenue;

    /**
     * Current top 5 clients share percentage
     * Formula: (top5ClientsRevenue / totalRevenue) × 100
     */
    private double currentTop5SharePercent;

    /**
     * Total revenue in prior TTM window (for period comparison)
     */
    private double priorTotalRevenue;

    /**
     * Revenue from top 5 clients in prior TTM window
     */
    private double priorTop5ClientsRevenue;

    /**
     * Prior period top 5 clients share percentage
     */
    private double priorTop5SharePercent;

    /**
     * Change in top 5 share from prior period (percentage points)
     * Example: 45% → 40% = -5.0 percentage points (IMPROVEMENT - lower concentration)
     */
    private double top5ShareChangePct;
}
