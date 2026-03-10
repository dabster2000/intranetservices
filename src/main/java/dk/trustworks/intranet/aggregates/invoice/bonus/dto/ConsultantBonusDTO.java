package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

public record ConsultantBonusDTO(
        String consultantUuid,
        String consultantName,
        String groupUuid,
        String groupName,
        double approvedTotal,
        double pendingTotal,
        double rejectedTotal,
        double salesBonus,
        double groupSalesTotal,
        int partnersInGroup,
        boolean salesBonusEligible,
        double productionBonus,
        double ownRevenue,
        boolean productionBonusEligible,
        int invoiceCount,
        double approvalRate,
        boolean payoutExists
) {}
