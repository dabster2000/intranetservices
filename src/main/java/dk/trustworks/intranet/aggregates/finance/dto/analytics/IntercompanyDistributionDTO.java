package dk.trustworks.intranet.aggregates.finance.dto.analytics;

import java.util.List;

/**
 * Intercompany cost distribution formatted for Sankey diagram rendering.
 * Nodes represent companies; links represent intercompany cost flows.
 */
public record IntercompanyDistributionDTO(
        /** Unique company nodes in the distribution. */
        List<SankeyNodeDTO> nodes,
        /** Directed cost flow links between companies. */
        List<SankeyLinkDTO> links,
        /** Fiscal year for the data. */
        int fiscalYear
) {
    /** A node in the Sankey diagram representing a company. */
    public record SankeyNodeDTO(String id, String uuid) {}

    /** A directed link in the Sankey diagram representing a cost flow. */
    public record SankeyLinkDTO(String source, String target, double value) {}
}
