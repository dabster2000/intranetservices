package dk.trustworks.intranet.aggregates.invoice.resources.dto;

import java.time.LocalDate;

/**
 * Projection DTO for the E-Invoicing listing endpoint.
 * Represents a booked invoice that was sent via EAN.
 *
 * SPEC-INV-001 S8.10.
 */
public record EInvoicingListItem(
        String invoiceUuid,
        int invoicenumber,
        String billingClientName,
        String billingClientEan,
        LocalDate invoicedate,
        double sumNoTax,
        double vat,
        String economicsStatus
) {}
