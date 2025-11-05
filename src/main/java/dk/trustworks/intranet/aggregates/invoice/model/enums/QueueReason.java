package dk.trustworks.intranet.aggregates.invoice.model.enums;

/**
 * Reason why an invoice is in QUEUED processing state.
 * Only relevant when ProcessingState = QUEUED.
 *
 * @see ProcessingState
 * @see <a href="/docs/new-features/invoice-status-design/backend-developer_guide.md">Backend Developer Guide</a>
 */
public enum QueueReason {
    /**
     * Internal invoice waiting for source invoice to be PAID.
     * Used for inter-company billing dependencies.
     * Reference: source_invoice_uuid field.
     */
    AWAIT_SOURCE_PAID,

    /**
     * Invoice flagged for manual review before processing.
     * Requires admin intervention to release.
     */
    MANUAL_REVIEW,

    /**
     * Invoice batched for later export/processing.
     * Part of scheduled bulk operation.
     */
    EXPORT_BATCH
}
