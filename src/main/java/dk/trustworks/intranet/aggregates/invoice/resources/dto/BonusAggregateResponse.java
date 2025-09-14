package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import dk.trustworks.intranet.model.enums.SalesApprovalStatus;

public record BonusAggregateResponse(
        String invoiceuuid,
        SalesApprovalStatus aggregatedStatus,
        double totalBonusAmount
) {}
