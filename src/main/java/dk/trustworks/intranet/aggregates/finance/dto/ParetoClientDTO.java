package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a single client in the Pareto revenue distribution chart.
 * Used for Chart A: Client Revenue Distribution (TTM).
 *
 * Business Context:
 * - Part of client portfolio concentration analysis
 * - Represents top 20 clients by revenue
 * - Includes cumulative percentage for Pareto visualization
 * - Margin band used for color-coding bars (green/orange/red)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParetoClientDTO {
    /**
     * Client UUID
     */
    private String clientId;

    /**
     * Client display name
     */
    private String clientName;

    /**
     * TTM recognized revenue in millions DKK
     * Converted from raw DKK: revenue / 1,000,000.0
     */
    private double revenueM;

    /**
     * Gross margin percentage
     * Formula: ((revenue - cost) / revenue) * 100.0
     * Range: 0.0 - 100.0
     */
    private double grossMarginPct;

    /**
     * Cumulative percentage of total revenue (Pareto curve)
     * Formula: SUM(revenue_this_client + all_previous) / total_revenue * 100.0
     * Range: 0.0 - 100.0
     * Used for line chart overlay showing concentration
     */
    private double cumulativePct;

    /**
     * Margin band classification for color-coding
     * Values:
     * - "High": grossMarginPct > 30%  (green bar)
     * - "Medium": 15% <= grossMarginPct <= 30%  (orange bar)
     * - "Low": grossMarginPct < 15%  (red bar)
     */
    private String marginBand;
}
