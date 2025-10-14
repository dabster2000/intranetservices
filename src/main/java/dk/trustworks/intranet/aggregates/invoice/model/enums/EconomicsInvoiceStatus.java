package dk.trustworks.intranet.aggregates.invoice.model.enums;

/**
 * E-conomics lifecycle status for an invoice.
 * <ul>
 *   <li>NA: not uploaded to e-conomics yet</li>
 *   <li>PENDING: upload queued but not yet attempted</li>
 *   <li>PARTIALLY_UPLOADED: at least one company received upload, but not all</li>
 *   <li>UPLOADED: voucher and attachment uploaded to all required e-conomics journals</li>
 *   <li>BOOKED: accountant has booked the voucher/invoice</li>
 *   <li>PAID: invoice has been fully paid (remainder == 0)</li>
 * </ul>
 */
public enum EconomicsInvoiceStatus {
    NA,
    PENDING,
    PARTIALLY_UPLOADED,
    UPLOADED,
    BOOKED,
    PAID
}
