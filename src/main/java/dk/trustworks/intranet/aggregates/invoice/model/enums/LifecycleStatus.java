package dk.trustworks.intranet.aggregates.invoice.model.enums;

/**
 * Business lifecycle status of an invoice.
 * Separates business workflow from type classification and ERP sync status.
 *
 * State Machine:
 * DRAFT → CREATED → SUBMITTED → PAID
 *           ↘               ↘
 *            CANCELLED       CANCELLED
 *
 * @see <a href="/docs/new-features/invoice-status-design/backend-developer_guide.md">Backend Developer Guide</a>
 */
public enum LifecycleStatus {
    /**
     * Invoice is being prepared, no invoice number assigned yet.
     * Can be edited freely. Not visible to client or sent to ERP.
     */
    DRAFT,

    /**
     * Invoice has been finalized with assigned number and PDF generated.
     * Ready to be submitted to client. May be uploaded to ERP.
     */
    CREATED,

    /**
     * Invoice has been sent/delivered to the client.
     * Awaiting payment.
     */
    SUBMITTED,

    /**
     * Invoice has been fully paid by the client.
     * Terminal state for successful invoices.
     */
    PAID,

    /**
     * Invoice has been voided/cancelled.
     * Terminal state. Use credit notes for corrections.
     */
    CANCELLED
}
