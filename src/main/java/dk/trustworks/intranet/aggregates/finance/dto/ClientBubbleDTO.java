package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a single client bubble in the portfolio matrix chart.
 * Used for Chart B: Client Portfolio Matrix (Revenue vs Margin).
 *
 * Business Context:
 * - Visualizes client positioning on revenue/margin axes
 * - Bubble size indicates relative revenue importance
 * - Sector grouping enables strategic portfolio analysis
 * - Helps identify high-value vs low-margin clients
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientBubbleDTO {
    /**
     * Client UUID
     */
    private String clientId;

    /**
     * Client display name (shown in tooltip)
     */
    private String clientName;

    /**
     * TTM recognized revenue in millions DKK (X-axis)
     * Converted from raw DKK: revenue / 1,000,000.0
     */
    private double revenueM;

    /**
     * Gross margin percentage (Y-axis)
     * Formula: ((revenue - cost) / revenue) * 100.0
     * Range: 0.0 - 100.0
     */
    private double grossMarginPct;

    /**
     * Relative bubble size (Z-axis)
     * Formula: (client_revenue / max_revenue) * 100.0
     * Range: 0.0 - 100.0
     * Normalized to ensure largest bubble = 100, smallest = proportional
     */
    private double bubbleSize;

    /**
     * Sector name for series grouping
     * Used to create color-coded series in bubble chart
     * Examples: "Public", "C25 Health", "C25 Financial", "C25 Energy", "Other"
     */
    private String sector;
}
