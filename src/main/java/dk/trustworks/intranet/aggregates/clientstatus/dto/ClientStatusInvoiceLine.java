package dk.trustworks.intranet.aggregates.clientstatus.dto;

/** One invoice issued for the client-month in the drill-down. */
public record ClientStatusInvoiceLine(
        String invoiceUuid,
        int invoiceNumber,
        String type,
        String status,
        double signedGrossConsultant,  // Σ consultant lines (hours×rate), SIGNED by type (CREDIT_NOTE ⇒ ×-1)
        double discountTotal,          // Σ non-consultant lines (hours×rate), SIGNED by type
        double amountNet,              // signedGrossConsultant + discountTotal
        String projectUuid,
        String projectName,
        String creditnoteForUuid,      // nullable; invoices.creditnote_for_uuid
        Integer invoiceRef,            // nullable; invoices.invoice_ref mapped 0 → null
        String invoicedate             // ISO yyyy-MM-dd
) {}
