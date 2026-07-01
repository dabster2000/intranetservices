package dk.trustworks.intranet.aggregates.invoice.services;

/**
 * Fired by {@link InvoiceFinalizationOrchestrator#bookDraft} once the invoice has been
 * booked in e-conomic and the booked state persisted, and consumed by an
 * {@code AFTER_SUCCESS} transactional observer ({@link InvoiceBookedPayoutObserver}).
 *
 * <p>Purpose: run the irreversible, lock-contention-prone work-item payout ONLY after
 * the booking transaction has durably committed. Previously the payout ran inline via a
 * {@code REQUIRES_NEW} facade, but that shares the Hibernate session with the booking
 * transaction, so a {@code Lock wait timeout} on the {@code work} table auto-flushed and
 * rolled back the just-persisted booked state — reverting the invoice to
 * {@code PENDING_REVIEW} while e-conomic kept the booking (split-brain incident:
 * invoice {@code dba892b4} / bookedNumber 28084, 2026-06-30).
 *
 * <p>Carries only scalar identifiers so no managed entity crosses the transaction boundary.
 */
public record InvoiceBookedEvent(
        String invoiceUuid,
        String contractuuid,
        String projectuuid,
        int month,
        int year) {
}
