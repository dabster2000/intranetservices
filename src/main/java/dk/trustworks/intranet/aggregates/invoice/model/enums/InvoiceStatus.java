package dk.trustworks.intranet.aggregates.invoice.model.enums;

/**
 * DEPRECATED: Legacy invoice status enum that conflated multiple concerns.
 *
 * <p>This enum mixed invoice type, lifecycle state, and operational flags into a single field.
 * It has been replaced by separate enums for each concern:</p>
 *
 * <ul>
 *   <li>DRAFT, CREATED, SUBMITTED → Use {@link LifecycleStatus}</li>
 *   <li>QUEUED → Use {@link ProcessingState#QUEUED}</li>
 *   <li>CREDIT_NOTE → Use {@link InvoiceType#CREDIT_NOTE}</li>
 * </ul>
 *
 * <p>See the invoice status refactoring documentation for migration details.</p>
 *
 * @deprecated Use {@link LifecycleStatus}, {@link ProcessingState}, and {@link InvoiceType} instead
 * Created by hans on 11/07/2017.
 */
@Deprecated(since = "Phase 1 consolidation", forRemoval = true)
public enum InvoiceStatus {

    DRAFT, QUEUED, CREATED, SUBMITTED, CREDIT_NOTE

}
