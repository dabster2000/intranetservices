package dk.trustworks.intranet.aggregates.invoice.model.enums;

/**
 * DEPRECATED: Legacy e-conomics integration status enum.
 *
 * <p>This enum tracked e-conomics upload and payment status.
 * It has been replaced by {@link FinanceStatus} which provides clearer semantics:</p>
 *
 * <ul>
 *   <li>NA → {@link FinanceStatus#NONE} or {@link FinanceStatus#ERROR}</li>
 *   <li>PENDING → (use ProcessingState for queue management instead)</li>
 *   <li>PARTIALLY_UPLOADED → {@link FinanceStatus#ERROR} (with retry logic)</li>
 *   <li>UPLOADED → {@link FinanceStatus#UPLOADED}</li>
 *   <li>BOOKED → {@link FinanceStatus#BOOKED}</li>
 *   <li>PAID → {@link FinanceStatus#PAID}</li>
 * </ul>
 *
 * <p>See the invoice status refactoring documentation for migration details.</p>
 *
 * @deprecated Use {@link FinanceStatus} instead
 */
@Deprecated(since = "Phase 1 consolidation", forRemoval = true)
public enum EconomicsInvoiceStatus {
    NA,
    PENDING,
    PARTIALLY_UPLOADED,
    UPLOADED,
    BOOKED,
    PAID
}
