package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backend DTO for Active Clients (TTM) KPI
 *
 * Measures the number of distinct clients with recognized revenue in a trailing 12-month (TTM) period.
 * Includes year-over-year comparison and monthly sparkline data for trend visualization.
 *
 * Definition: A client is "active" if they have recognized_revenue_dkk > 0 in at least one month
 * within the specified 12-month window.
 *
 * Used by CxO Dashboard Executive Summary Tab.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActiveClientsDTO {

    /**
     * Number of distinct active clients in current 12-month window
     * Calculated using COUNT(DISTINCT client_id) with revenue > 0
     */
    private int currentTTMCount;

    /**
     * Number of distinct active clients in prior year's 12-month window
     * Used for year-over-year comparison
     */
    private int priorTTMCount;

    /**
     * Absolute change in client count (currentTTMCount - priorTTMCount)
     * Positive = growth, Negative = decline
     */
    private int yoyChange;

    /**
     * Year-over-year percentage change
     * Formula: ((currentTTMCount - priorTTMCount) / priorTTMCount) × 100
     * Example: 45 → 50 = +11.11%
     */
    private double yoyChangePercent;

    /**
     * Sparkline data: 12 monthly active client counts
     * Array index 0 = oldest month (12 months ago)
     * Array index 11 = most recent month
     * Each value represents distinct clients active in that single month
     */
    private int[] sparklineData;
}
