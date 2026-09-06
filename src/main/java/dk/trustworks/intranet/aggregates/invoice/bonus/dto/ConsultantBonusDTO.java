package dk.trustworks.intranet.aggregates.invoice.bonus.dto;

import java.util.List;

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
        boolean payoutExists,
        /** Which company must pay which part of {@code salesBonus}; empty when no sales bonus is earned. */
        List<CompanyBonusShareDTO> salesBonusByCompany
) {}
