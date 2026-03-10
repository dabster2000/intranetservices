package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

public record GroupAnalyticsDTO(
        String groupUuid,
        String groupName,
        double groupSalesTotal,
        double groupBonusTotal,
        int partnerCount,
        double groupApprovalRate
) {}
