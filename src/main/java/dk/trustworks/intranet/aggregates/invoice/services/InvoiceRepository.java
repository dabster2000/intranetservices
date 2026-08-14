package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.LockModeType;
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

    /**
     * Loads an invoice under a {@code PESSIMISTIC_WRITE} row lock, so a concurrent finalize
     * blocks here instead of racing past a status check.
     *
     * <p>Without this, {@code requireEditableInvoice} is a check-then-act: under REPEATABLE READ
     * two overlapping finalizes both read DRAFT/QUEUED on their own snapshots, both proceed, and
     * both create a real e-conomic draft — the second silently orphaning the first. That happened
     * on 2026-08-07 (invoice 77ee648a) and again the same morning (9b931d71), and it is what
     * produced the deadlock that split the booking.
     *
     * <p>Deliberately a lock rather than {@code @Version}. There is no {@code @Version} anywhere in
     * this codebase today, so during a blue/green rollout old tasks have no version column in their
     * mapping: their bulk {@code Invoice.update(… WHERE uuid like ?)} neither checks nor increments
     * it. A new task would read v=5, an old task would commit leaving v=5, and the new task's
     * {@code WHERE version = 5} would still match and silently clobber — zero protection during
     * exactly the window it is needed, plus a schema change a code revert cannot undo.
     *
     * <p>Callers must already be in a transaction; a lock outside one is meaningless. Contention
     * surfaces as a lock-wait timeout, which now maps to a retriable 409 rather than a 500
     * (see {@code ArcUndeclaredThrowableExceptionMapper}).
     */
    public Optional<Invoice> findByUuidForUpdate(String uuid) {
        return Optional.ofNullable(
                Invoice.getEntityManager().find(Invoice.class, uuid, LockModeType.PESSIMISTIC_WRITE));
    }

    @Transactional
    public void persist(Invoice invoice) {
        Invoice.getEntityManager().merge(invoice);
    }

    /**
     * Forces Hibernate to issue the pending SQL now, inside the caller's method frame.
     *
     * <p>Exists purely so a lock conflict surfaces where it can still be classified. MariaDB raises
     * 1213 at the statement that closes the lock cycle; by default that statement is issued during
     * the auto-flush in {@code beforeCompletion}, after the caller has returned, where the failure
     * becomes a checked {@code RollbackException} that Arc rewraps and
     * {@code GenericExceptionMapper} turns into a 500 (invoice 77ee648a, 2026-08-07). Flushed
     * in-method it is an unchecked {@code PersistenceException} on the caller's frame instead.
     *
     * <p>Goes through the repository rather than calling {@code Invoice.getEntityManager()} at the
     * call site for the reason stated on this class: Panache statics cannot be mocked, and the
     * orchestrator's fast-tier tests run without a persistence context.
     *
     * <p>No {@code @Transactional} — a flush is only meaningful inside the caller's existing
     * transaction, and {@code REQUIRED} would silently start one of its own if there were none.
     */
    public void flush() {
        Invoice.getEntityManager().flush();
    }

    /**
     * Re-reads the entity's row from the database, replacing any stale first-level-cache state.
     *
     * <p>Exists for reconciliation flows where a {@code REQUIRES_NEW} bulk update (e.g.
     * {@code InvoiceBookingAttemptWriter.markBooked}) has just rewritten the row underneath a
     * loaded entity: without the refresh, a later {@code persist} of that stale instance would
     * silently overwrite the reconciled state with the pre-update snapshot.
     *
     * <p>{@code @Transactional} is REQUIRED here, not optional: JPA specifies
     * {@code EntityManager.refresh} throws {@code TransactionRequiredException} outside a
     * transaction (proven in prod, 2026-08-14, first reconcile of invoice 603bdb0d). Because the
     * transaction starts at this call, its REPEATABLE READ snapshot begins now and therefore sees
     * the just-committed {@code REQUIRES_NEW} update — a caller-owned transaction opened before
     * that update would not.
     */
    @Transactional
    public void refresh(Invoice invoice) {
        Invoice.getEntityManager().refresh(invoice);
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
