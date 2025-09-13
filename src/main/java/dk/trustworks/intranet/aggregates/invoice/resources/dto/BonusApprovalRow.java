package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import dk.trustworks.intranet.model.Company;

import java.time.LocalDate;
import java.util.List;

public record BonusApprovalRow(
        String invoiceuuid,
        int invoicenumber,
        LocalDate invoicedate,
        String currency,
        String clientName,
        double amountNoTax,
        SalesApprovalStatus aggregatedStatus,
        double totalBonusAmount,
        List<Company> companies
) {}
