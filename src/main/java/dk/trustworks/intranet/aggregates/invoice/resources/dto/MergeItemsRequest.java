package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import java.util.List;

public record MergeItemsRequest(
        String targetItemUuid,
        List<String> sourceItemUuids,
        String displayName,
        double rate
) {}
