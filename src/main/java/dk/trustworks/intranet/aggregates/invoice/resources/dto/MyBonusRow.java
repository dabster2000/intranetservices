package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MyBonusRow(
        String invoiceuuid,
        int invoicenumber,
        LocalDate invoicedate,
        String currency,
        String clientname,
        InvoiceType type,
        String bonusuuid,
        InvoiceBonus.ShareType shareType,
        double shareValue,
        double computedAmount,
        SalesApprovalStatus status,
        String approvedBy,
        LocalDateTime approvedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String overrideNote,
        double engineOriginalAmount       // <--- NEW
) {}

