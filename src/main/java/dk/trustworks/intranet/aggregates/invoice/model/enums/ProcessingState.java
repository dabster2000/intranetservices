package dk.trustworks.intranet.aggregates.invoice.model.enums;

/**
 * Operational processing state for invoice workflow management.
 * Used to hold invoices in queue pending specific conditions.
 *
 * @see QueueReason for details on why an invoice is queued
 * @see <a href="/docs/new-features/invoice-status-design/backend-developer_guide.md">Backend Developer Guide</a>
 */
public enum ProcessingState {
    /**
     * Invoice is not held in any queue.
     * Normal processing according to lifecycle status.
     */
    IDLE,

    /**
     * Invoice is queued awaiting specific condition.
     * See queue_reason field for details.
     * Common use: Internal invoices awaiting source invoice payment.
     */
    QUEUED
}
