package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client Detail DTO - Single client row with TTM metrics.
 * Used in the Client Portfolio Detail Table (Table E).
 *
 * Contains comprehensive client metrics including:
 * - Revenue and margin performance
 * - Growth metrics (YoY)
 * - Engagement indicators (projects, service lines)
 * - Activity tracking (last invoice date)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientDetailDTO {
    /**
     * Unique client identifier (UUID)
     */
    private String clientId;

    /**
     * Client display name
     */
    private String clientName;

    /**
     * Client sector/industry (e.g., "PUBLIC", "C25 Health")
     */
    private String sector;

    /**
     * Trailing 12-month revenue in millions DKK
     */
    private double ttmRevenueM;

    /**
     * Gross margin percentage (0-100 scale)
     * Calculated as: ((revenue - direct_costs) / revenue) * 100
     */
    private double grossMarginPct;

    /**
     * Year-over-year revenue growth percentage
     * Positive = growth, negative = decline
     */
    private double yoyGrowthPct;

    /**
     * Count of currently active projects for this client
     * (Projects with activity in the TTM window)
     */
    private int activeProjectsCount;

    /**
     * Date of the first invoice for this client (YYYY-MM-DD format)
     * Used to filter out new clients with insufficient history for YoY comparisons
     */
    private String firstInvoiceDate;

    /**
     * Date of the most recent invoice (YYYY-MM-DD format)
     * Used to identify dormant clients
     */
    private String lastInvoiceDate;

    /**
     * Count of distinct service lines engaged with this client
     * Indicates cross-sell penetration
     */
    private int serviceLineCount;
}
