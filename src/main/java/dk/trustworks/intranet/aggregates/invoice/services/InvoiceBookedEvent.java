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
 *
 * <p>{@code bookedNumber} and {@code idempotencyKey} are carried for correlation, not for control
 * flow. Every consumer runs after the vendor call, so when one of them logs a failure these are the
 * two values that identify the e-conomic document and the booking attempt behind it — the pair that
 * was missing from the logs on 2026-08-07 and made the incident hard to reconstruct
 * ({@code EconomicsBookingErrorMapper} is a {@code ResponseExceptionMapper} and has no invoice in
 * scope, so it can never supply them).
 *
 * <p>{@code bookedNumber} is boxed and may be null: {@code applyBookedState} is also reached on the
 * reconciliation short-circuit, where an earlier attempt's number is replayed and may not be known.
 */
public record InvoiceBookedEvent(
        String invoiceUuid,
        String contractuuid,
        String projectuuid,
        int month,
        int year,
        Integer bookedNumber,
        String idempotencyKey) {
}
