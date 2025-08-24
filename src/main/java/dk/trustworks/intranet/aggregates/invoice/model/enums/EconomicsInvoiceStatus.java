package dk.trustworks.intranet.aggregates.invoice.model.enums;

/**
 * E-conomics lifecycle status for an invoice.
 * NA: not uploaded to e-conomics yet
 * UPLOADED: voucher and attachment uploaded to a journal in e-conomics
 * BOOKED: accountant has booked the voucher/invoice
 * PAID: invoice has been fully paid (remainder == 0)
 */
public enum EconomicsInvoiceStatus {
    NA,
    UPLOADED,
    BOOKED,
    PAID
}
