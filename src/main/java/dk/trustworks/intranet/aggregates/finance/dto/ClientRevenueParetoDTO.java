package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO representing the complete Pareto chart data for client revenue distribution.
 * Used for Chart A: Client Revenue Distribution (TTM).
 *
 * Business Context:
 * - Visualizes revenue concentration risk across client portfolio
 * - Shows top 20 clients by revenue (Pareto principle: 80/20 rule)
 * - Combines horizontal bar chart (revenue) + line chart (cumulative %)
 * - Bars color-coded by margin band (high/medium/low profitability)
 *
 * Chart Specifications:
 * - Chart Type: Horizontal Bar + Line (combination)
 * - Size: 8 columns × 450px
 * - X-axis: Client names
 * - Left Y-axis: Revenue (M kr)
 * - Right Y-axis: Cumulative % (0-100)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientRevenueParetoDTO {
    /**
     * Top 20 clients ordered by revenue DESC
     * Each client includes:
     * - Revenue in millions
     * - Gross margin percentage
     * - Cumulative percentage (Pareto curve)
     * - Margin band (for color-coding)
     */
    private List<ParetoClientDTO> clients;

    /**
     * Total TTM revenue across ALL clients (not just top 20) in millions DKK
     * Used for cumulative percentage calculation
     * Note: This represents the portfolio total, not the sum of top 20
     */
    private double totalRevenueM;
}
