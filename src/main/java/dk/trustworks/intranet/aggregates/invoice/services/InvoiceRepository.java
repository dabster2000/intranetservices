package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Thin repository wrapper over Invoice's static Panache methods.
 * Exists so the orchestrator can be tested with a mock — Panache static
 * methods cannot be mocked directly with Mockito.
 *
 * SPEC-INV-001 §7.1.
 */
@ApplicationScoped
public class InvoiceRepository {

    public Optional<Invoice> findByUuid(String uuid) {
        return Optional.ofNullable(Invoice.findById(uuid));
    }

    @Transactional
    public void persist(Invoice invoice) {
        Invoice.getEntityManager().merge(invoice);
    }

    /**
     * Returns invoices in PENDING_REVIEW whose {@code invoicedate} predates the given
     * cutoff date.
     *
     * <p>The Invoice entity carries no generic {@code updatedAt} timestamp field.
     * {@code invoicedate} is set when the invoice is built (before draft creation) and
     * therefore serves as a conservative lower bound: any invoice in PENDING_REVIEW with
     * an invoicedate older than 7 days has certainly been in that state too long.
     *
     * <p>SPEC-INV-001 §9.5.
     *
     * @param cutoff invoices with invoicedate strictly before this date are considered stale
     * @return list of stale PENDING_REVIEW invoices, never null
     */
    public List<Invoice> listPendingReviewOlderThan(LocalDate cutoff) {
        return Invoice.list("status = ?1 AND invoicedate < ?2",
                InvoiceStatus.PENDING_REVIEW, cutoff);
    }
}
