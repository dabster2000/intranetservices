package dk.trustworks.intranet.aggregates.invoice.model.dto;

import java.util.List;

public record AttributionResolution(
    List<ResolvedItem> items,
    boolean allResolved,
    int flaggedCount
) {}
