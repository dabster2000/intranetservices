package dk.trustworks.intranet.aggregates.invoice.model.enums;

/**
 * Invoice control review status for CLIENT invoices.
 * Tracks the review and approval workflow in the invoice-controlling-admin page.
 */
public enum InvoiceControlStatus {

    NOT_REVIEWED,
    UNDER_REVIEW,
    APPROVED,
    REJECTED

}
