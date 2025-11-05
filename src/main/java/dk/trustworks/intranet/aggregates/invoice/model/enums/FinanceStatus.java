package dk.trustworks.intranet.aggregates.invoice.model.enums;

/**
 * ERP/e-conomics integration status for an invoice.
 * Tracks the financial system sync state independently from business lifecycle.
 *
 * Progression (happy path):
 * NONE → UPLOADED → BOOKED → PAID
 *               ↘ ERROR (retry possible)
 *
 * @see <a href="/docs/new-features/invoice-status-design/backend-developer_guide.md">Backend Developer Guide</a>
 */
public enum FinanceStatus {
    /**
     * Not yet uploaded to e-conomics, or not applicable (e.g., PHANTOM invoices).
     */
    NONE,

    /**
     * Successfully uploaded to e-conomics voucher system.
     * Voucher created, awaiting accountant booking.
     */
    UPLOADED,

    /**
     * Accountant has booked the voucher in e-conomics.
     * Invoice is in accounts receivable.
     */
    BOOKED,

    /**
     * Payment received and reconciled in e-conomics (remainder = 0).
     * Financial closure achieved.
     */
    PAID,

    /**
     * Upload or sync error occurred.
     * Requires manual intervention or retry.
     */
    ERROR
}
