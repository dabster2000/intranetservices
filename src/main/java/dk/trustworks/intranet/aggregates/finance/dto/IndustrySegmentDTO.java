package dk.trustworks.intranet.aggregates.finance.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Industry Segment DTO - Single segment row with distribution metrics.
 * Used in the Industry Distribution chart and table.
 *
 * Contains client count and revenue metrics for a single industry segment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndustrySegmentDTO {

    /**
     * Industry segment code (e.g., "PUBLIC", "HEALTH", "FINANCIAL", "ENERGY", "EDUCATION", "OTHER")
     */
    private String segment;

    /**
     * Human-readable segment display name.
     * Examples: "Public Sector", "Healthcare", "Financial Services"
     */
    private String displayName;

    /**
     * Number of clients in this segment.
     */
    private int clientCount;

    /**
     * Client count as percentage of total clients.
     * Formula: (clientCount / totalClients) * 100
     */
    private double clientCountPercent;

    /**
     * Total revenue from clients in this segment (in millions DKK).
     */
    private double revenueM;

    /**
     * Revenue as percentage of total portfolio revenue.
     * Formula: (revenueM / totalRevenueM) * 100
     */
    private double revenuePercent;

    /**
     * Average engagement length in months for clients in this segment.
     * Provides insight into segment-specific relationship duration.
     */
    private double avgEngagementMonths;
}
