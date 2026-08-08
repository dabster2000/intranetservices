package dk.trustworks.intranet.aggregates.invoice.economics.book;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link InvoiceBookingAttempt}.
 *
 * <p>Shape follows {@code ClientEconomicsSyncFailureRepository}. Note that {@code InvoiceRepository}
 * in this package tree is a hand-written wrapper over Panache statics rather than a
 * {@code PanacheRepositoryBase} — the two do not share a shape, so do not generalise from it.
 */
@ApplicationScoped
public class InvoiceBookingAttemptRepository
        implements PanacheRepositoryBase<InvoiceBookingAttempt, String> {

    /** Lookup on the natural key: the invoice and the draft number actually sent to e-conomic. */
    public Optional<InvoiceBookingAttempt> findByInvoiceAndDraft(String invoiceUuid, int draftInvoiceNumber) {
        return find("invoiceUuid = ?1 AND draftInvoiceNumber = ?2", invoiceUuid, draftInvoiceNumber)
                .firstResultOptional();
    }

    public Optional<InvoiceBookingAttempt> findLatestByInvoice(String invoiceUuid) {
        return find("invoiceUuid = ?1 ORDER BY createdAt DESC", invoiceUuid).firstResultOptional();
    }

    /**
     * Any attempt for this invoice that already reached {@code BOOKED}. Used to short-circuit a
     * repeat booking before any outbound call is made.
     */
    public Optional<InvoiceBookingAttempt> findBookedByInvoice(String invoiceUuid) {
        return find("invoiceUuid = ?1 AND state = ?2", invoiceUuid, InvoiceBookingAttempt.State.BOOKED)
                .firstResultOptional();
    }

    /** Terminal rows awaiting a human. Feeds the S6 alarm and any operator view. */
    public List<InvoiceBookingAttempt> listNeedingReconciliation() {
        return find("state = ?1 ORDER BY createdAt", InvoiceBookingAttempt.State.NEEDS_RECONCILIATION)
                .list();
    }

    /** Live attempts for an invoice — anything not yet in a terminal, superseded state. */
    public List<InvoiceBookingAttempt> listOpenByInvoice(String invoiceUuid) {
        return find("invoiceUuid = ?1 AND state IN (?2, ?3)",
                invoiceUuid,
                InvoiceBookingAttempt.State.PENDING,
                InvoiceBookingAttempt.State.FAILED).list();
    }
}
