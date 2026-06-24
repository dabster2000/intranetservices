package dk.trustworks.intranet.aggregates.clientstatus.dto;

/** One invoice issued for the client-month in the drill-down. */
public record ClientStatusInvoiceLine(
        String invoiceUuid,
        int invoiceNumber,
        String type,
        String status,
        double amount,          // sum excl. VAT (items hours×rate)
        String invoicedate      // ISO yyyy-MM-dd
) {}
