package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DTO representing the complete bubble chart data for client portfolio matrix.
 * Used for Chart B: Client Portfolio Matrix (Revenue vs Margin).
 *
 * Business Context:
 * - Visualizes client portfolio positioning on 2D matrix
 * - X-axis: Revenue (size of client)
 * - Y-axis: Margin (profitability)
 * - Z-axis (bubble size): Relative revenue importance
 * - Color: Sector grouping for strategic analysis
 *
 * Chart Specifications:
 * - Chart Type: Bubble (Scatter with size dimension)
 * - Size: 4 columns × 450px
 * - One series per sector (color-coded)
 * - Quadrant interpretation:
 *   • Top-right: High revenue + High margin (strategic accounts)
 *   • Top-left: Low revenue + High margin (growth opportunities)
 *   • Bottom-right: High revenue + Low margin (at-risk accounts)
 *   • Bottom-left: Low revenue + Low margin (consider exit)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientPortfolioBubbleDTO {
    /**
     * Clients grouped by sector for color-coded series
     * Key: Sector name (e.g., "Public", "C25 Health")
     * Value: List of clients in that sector
     *
     * Frontend creates one DataSeries per sector
     */
    private Map<String, List<ClientBubbleDTO>> sectorData;

    /**
     * Maximum revenue in millions across all clients
     * Used for X-axis scaling
     * Typically set axis max to maxRevenueM * 1.1 for padding
     */
    private double maxRevenueM;

    /**
     * Maximum gross margin percentage across all clients
     * Used for Y-axis scaling
     * Typically set axis max to 100.0 or maxMarginPct * 1.1
     */
    private double maxMarginPct;
}
