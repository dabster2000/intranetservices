package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import dk.trustworks.intranet.model.enums.SalesApprovalStatus;

import java.time.LocalDate;

public record BonusApprovalRow(
        String invoiceuuid,
        int invoicenumber,
        LocalDate invoicedate,
        String currency,
        String clientName,
        double amountNoTax,
        SalesApprovalStatus aggregatedStatus,
        double totalBonusAmount
) {}
