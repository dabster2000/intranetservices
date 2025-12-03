package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Backend DTO for Cross-Sell / Service Line Penetration Heatmap (Chart D)
 *
 * Provides revenue matrix showing which service lines are used by top clients.
 * Matrix dimensions:
 * - Y-axis: Top 15 clients by TTM revenue
 * - X-axis: All service lines (PM, BA, SA, DEV, CYB, etc.)
 * - Cell value: Revenue in DKK (millions) for client × service line combination
 *
 * Used by CxO Dashboard Client Portfolio Tab (Chart D: Cross-Sell/Service Line Penetration Heatmap).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServiceLinePenetrationDTO {

    /**
     * Ordered list of top 15 client names (Y-axis labels)
     * Sorted by total TTM revenue descending
     * Example: ["Client A", "Client B", "Client C", ...]
     */
    private List<String> clientNames;

    /**
     * Ordered list of service line IDs (X-axis labels)
     * Typically ["PM", "BA", "SA", "DEV", "CYB"]
     * Order matches the service line order in the system
     */
    private List<String> serviceLines;

    /**
     * Revenue matrix: revenueMatrix[clientIndex][serviceLineIndex] = revenue in DKK
     * Dimensions: [15 clients] × [N service lines]
     * Values are in DKK (not millions) - frontend converts for display
     * Zero or null values indicate no revenue for that client/service line combination
     *
     * Example:
     * revenueMatrix[0][1] = revenue for "Client A" (index 0) in "BA" service (index 1)
     */
    private double[][] revenueMatrix;

    /**
     * Maximum revenue value across all cells in the matrix
     * Used by frontend to scale the heatmap color gradient
     * Value in DKK (frontend converts to millions for display)
     */
    private double maxRevenue;
}
