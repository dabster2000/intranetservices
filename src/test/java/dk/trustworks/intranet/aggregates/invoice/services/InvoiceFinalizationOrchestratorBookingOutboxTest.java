package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingRequest;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttempt;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptWriter;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.model.Company;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Behaviour of the booking outbox (S1/S2), which exists because of the 2026-08-07 incident:
 * e-conomic booked invoice 77ee648a as 28214, the local transaction died on a MariaDB 1213
 * deadlock at commit, and four retries re-resolved {@code draftInvoiceNumber} live — changing the
 * payload under an unchanged Idempotency-Key — and were refused with 400 PayloadChanged. When the
 * vendor's one-hour window lapsed, a further attempt booked a SECOND invoice, 28218.
 *
 * <p>Plain Mockito, no DB, fast tier.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceFinalizationOrchestratorBookingOutboxTest {

    @InjectMocks InvoiceFinalizationOrchestrator orchestrator;

    @Mock InvoiceRepository                  invoices;
    @Mock EconomicsDraftInvoiceApiClient     draftApi;
    @Mock EconomicsBookingApiClient          bookApi;
    @Mock EconomicsAgreementResolver         agreements;
    @Mock jakarta.enterprise.event.Event<InvoiceBookedEvent> invoiceBooked;
    @Mock InvoiceBookingAttemptWriter        attempts;
    @Mock InvoiceBookingAttemptRepository    attemptRepo;
    @Mock dk.trustworks.intranet.perf.PerfMetrics    perfMetrics;

    private static final String FROZEN_JSON = "{\"draftInvoice\":{\"draftInvoiceNumber\":4525}}";

    @BeforeEach
    void enableUpload() {
        orchestrator.invoiceUploadEnabled = true;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Invoice pendingReview(String uuid, String companyUuid, Integer q2cDraftNumber) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INVOICE);
        inv.setStatus(InvoiceStatus.PENDING_REVIEW);
        inv.setEconomicsDraftNumber(q2cDraftNumber);
        Company c = new Company();
        c.setUuid(companyUuid);
        inv.setCompany(c);
        return inv;
    }

    private InvoiceBookingAttempt attempt(InvoiceBookingAttempt.State state, LocalDateTime postedAt) {
        InvoiceBookingAttempt a = new InvoiceBookingAttempt();
        a.setUuid("attempt-1");
        a.setInvoiceUuid("i1");
        a.setCompanyUuid("co-1");
        a.setEconomicsDraftNumber(4521);
        a.setDraftInvoiceNumber(4525);
        a.setIdempotencyKey("book-i1");
        a.setPayloadJson(FROZEN_JSON);
        a.setPayloadHash(InvoiceBookingAttemptWriter.sha256(FROZEN_JSON));
        a.setState(state);
        a.setPostedAt(postedAt);
        return a;
    }

    /** Arranges the happy path up to (but not including) the vendor's response. */
    private Invoice arrangeReserved(InvoiceBookingAttempt reserved) {
        Invoice inv = pendingReview("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(new EconomicsAgreementResolver.Tokens("APP", "GRANT"));
        lenient().when(attemptRepo.findBookedByInvoice("i1")).thenReturn(Optional.empty());
        EconomicsDraftInvoice fetched = new EconomicsDraftInvoice();
        fetched.setDraftInvoiceNumber(4525);
        lenient().when(draftApi.getByNumber("APP", "GRANT", 4521)).thenReturn(fetched);
        lenient().when(attempts.reserve(anyString(), anyString(), any(), anyInt(), any(), anyString(), any()))
                .thenReturn(reserved);
        lenient().when(attempts.serialise(any())).thenReturn(FROZEN_JSON);
        return inv;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void reserve_freezes_the_draft_number_and_the_invariant_key_before_posting() {
        arrangeReserved(attempt(InvoiceBookingAttempt.State.PENDING, null));
        EconomicsBookedInvoice booked = new EconomicsBookedInvoice();
        booked.setBookedInvoiceNumber(28214);
        when(bookApi.book(eq("APP"), eq("GRANT"), anyString(), any())).thenReturn(booked);

        orchestrator.bookDraft("i1", null);

        // The key must stay invariant. During a blue/green rollout old tasks still send
        // "book-{uuid}"; a per-attempt suffix would stop the two colliding at the vendor and
        // would silently produce two booked invoices instead of one loud 400.
        verify(bookApi).book(eq("APP"), eq("GRANT"), eq("book-i1"),
                argThat(r -> r.getDraftInvoice().getDraftInvoiceNumber() == 4525));
        // posted_at must be stamped BEFORE the call, so a task death cannot make a posted
        // attempt look un-posted and get replayed as new.
        InOrder order = inOrder(attempts, bookApi);
        order.verify(attempts).markPosted("attempt-1");
        order.verify(bookApi).book(any(), any(), any(), any());
    }

    @Test
    void retry_replays_the_frozen_draft_number_without_re_resolving_it() {
        // The incident's exact shape: an attempt already exists, posted 5 minutes ago, and the
        // local commit failed. Re-resolving draftInvoiceNumber here is what changed the payload
        // and produced 400 PayloadChanged.
        InvoiceBookingAttempt existing = attempt(InvoiceBookingAttempt.State.PENDING,
                LocalDateTime.now().minusMinutes(5));
        arrangeReserved(existing);
        EconomicsBookedInvoice booked = new EconomicsBookedInvoice();
        booked.setBookedInvoiceNumber(28214);
        when(bookApi.book(eq("APP"), eq("GRANT"), eq("book-i1"), any())).thenReturn(booked);

        orchestrator.bookDraft("i1", null);

        verify(bookApi).book(eq("APP"), eq("GRANT"), eq("book-i1"),
                argThat(r -> r.getDraftInvoice().getDraftInvoiceNumber() == 4525));
        verify(attempts).markBooked("attempt-1", "i1", 28214);
    }

    @Test
    void an_attempt_past_the_idempotency_window_is_never_posted() {
        // This is how 28218 happened: past one hour e-conomic stops deduplicating, so replaying
        // the same key and body creates a SECOND booked invoice rather than returning the cached one.
        InvoiceBookingAttempt stale = attempt(InvoiceBookingAttempt.State.PENDING,
                LocalDateTime.now().minusMinutes(61));
        arrangeReserved(stale);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> orchestrator.bookDraft("i1", null));

        assertEquals(Response.Status.CONFLICT.getStatusCode(), e.getResponse().getStatus());
        verify(bookApi, never()).book(any(), any(), any(), any());
        verify(attempts).markNeedsReconciliation(eq("attempt-1"), contains("Idempotency window expired"));
    }

    @Test
    void an_already_booked_attempt_short_circuits_without_a_second_post() {
        Invoice inv = pendingReview("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(new EconomicsAgreementResolver.Tokens("APP", "GRANT"));
        InvoiceBookingAttempt booked = attempt(InvoiceBookingAttempt.State.BOOKED,
                LocalDateTime.now().minusHours(3));
        booked.setBookedNumber(28214);
        when(attemptRepo.findBookedByInvoice("i1")).thenReturn(Optional.of(booked));

        Invoice out = orchestrator.bookDraft("i1", null);

        verify(bookApi, never()).book(any(), any(), any(), any());
        verify(draftApi, never()).getByNumber(any(), any(), anyInt());
        assertEquals(InvoiceStatus.CREATED, out.getStatus());
        assertEquals(Integer.valueOf(28214), out.getEconomicsBookedNumber());
        assertEquals(EconomicsInvoiceStatus.BOOKED, out.getEconomicsStatus());
    }

    @Test
    void a_needs_reconciliation_attempt_refuses_to_post() {
        arrangeReserved(attempt(InvoiceBookingAttempt.State.NEEDS_RECONCILIATION,
                LocalDateTime.now().minusMinutes(2)));

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> orchestrator.bookDraft("i1", null));

        assertEquals(Response.Status.CONFLICT.getStatusCode(), e.getResponse().getStatus());
        verify(bookApi, never()).book(any(), any(), any(), any());
    }

    @Test
    void a_payload_that_no_longer_matches_the_frozen_body_refuses_to_post() {
        InvoiceBookingAttempt drifted = attempt(InvoiceBookingAttempt.State.PENDING, null);
        drifted.setPayloadHash(InvoiceBookingAttemptWriter.sha256("{\"something\":\"else\"}"));
        arrangeReserved(drifted);

        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> orchestrator.bookDraft("i1", null));

        assertEquals(Response.Status.CONFLICT.getStatusCode(), e.getResponse().getStatus());
        verify(bookApi, never()).book(any(), any(), any(), any());
        verify(attempts).markNeedsReconciliation(eq("attempt-1"), contains("diverged"));
    }

    @Test
    void a_transport_failure_with_no_response_becomes_a_reconciliation_case() {
        // No HTTP response means we cannot know whether e-conomic booked it. Fail closed (P3):
        // a blind retry is exactly what turned one booking into two.
        arrangeReserved(attempt(InvoiceBookingAttempt.State.PENDING, null));
        when(bookApi.book(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Read timed out"));

        assertThrows(RuntimeException.class, () -> orchestrator.bookDraft("i1", null));

        verify(attempts).markNeedsReconciliation(eq("attempt-1"), contains("No response"));
        verify(attempts, never()).markBooked(any(), any(), anyInt());
    }

    // ── S3.5 — the bounded Tx2 retry ──────────────────────────────────────────
    //
    // Losing Tx2 IS the split-brain: e-conomic has booked the invoice and the local record of it
    // is gone. That segment has no external side effect, so replaying it is safe in a way that
    // replaying the POST is not.

    /** The production shape: a checked RollbackException that Arc rewraps, wrapping a 1213. */
    private static RuntimeException commitDeadlock() {
        jakarta.transaction.RollbackException rollback =
                new jakarta.transaction.RollbackException("ARJUNA016053: Could not commit transaction.");
        rollback.initCause(new jakarta.persistence.PersistenceException("could not execute statement",
                new org.hibernate.exception.LockAcquisitionException("could not execute statement",
                        new java.sql.SQLException(
                                "Deadlock found when trying to get lock; try restarting transaction",
                                "40001", 1213))));
        return new io.quarkus.arc.ArcUndeclaredThrowableException(rollback);
    }

    private EconomicsBookedInvoice arrangeAcceptedBooking() {
        arrangeReserved(attempt(InvoiceBookingAttempt.State.PENDING, null));
        EconomicsBookedInvoice booked = new EconomicsBookedInvoice();
        booked.setBookedInvoiceNumber(28214);
        when(bookApi.book(eq("APP"), eq("GRANT"), anyString(), any())).thenReturn(booked);
        return booked;
    }

    @Test
    void a_deadlock_recording_tx2_is_retried_once_and_never_reposts_to_the_vendor() {
        arrangeAcceptedBooking();
        doThrow(commitDeadlock()).doNothing().when(attempts).markBooked("attempt-1", "i1", 28214);

        Invoice out = orchestrator.bookDraft("i1", null);

        verify(attempts, times(2)).markBooked("attempt-1", "i1", 28214);
        // The whole point: the irreversible call happens exactly once regardless of the retry.
        verify(bookApi, times(1)).book(any(), any(), any(), any());
        assertEquals(InvoiceStatus.CREATED, out.getStatus());
        assertEquals(Integer.valueOf(28214), out.getEconomicsBookedNumber());
    }

    @Test
    void the_tx2_retry_is_bounded_to_exactly_one_extra_attempt() {
        arrangeAcceptedBooking();
        doThrow(commitDeadlock()).when(attempts).markBooked("attempt-1", "i1", 28214);

        assertThrows(RuntimeException.class, () -> orchestrator.bookDraft("i1", null));

        verify(attempts, times(2)).markBooked("attempt-1", "i1", 28214);
        verify(bookApi, times(1)).book(any(), any(), any(), any());
        // The attempt is deliberately left PENDING with posted_at set, so the next call replays the
        // same key and frozen body inside the TTL and e-conomic returns its cached 201.
        verify(attempts, never()).markNeedsReconciliation(any(), any());
    }

    @Test
    void a_non_lock_failure_in_tx2_is_not_retried() {
        arrangeAcceptedBooking();
        doThrow(new IllegalStateException("attempt row vanished"))
                .when(attempts).markBooked("attempt-1", "i1", 28214);

        assertThrows(IllegalStateException.class, () -> orchestrator.bookDraft("i1", null));

        verify(attempts, times(1)).markBooked("attempt-1", "i1", 28214);
    }

    // ── S6.5 — the counters that make the split-brain visible ──────────────────

    @Test
    void vendor_acceptance_is_counted_before_tx2_and_the_commit_after_it() {
        arrangeAcceptedBooking();

        orchestrator.bookDraft("i1", null);

        // Ordering is the whole signal: if the task dies between the two, accepted > committed and
        // the metric-math alarm fires. Emitting both after the commit would hide exactly that gap.
        InOrder order = inOrder(perfMetrics, attempts);
        order.verify(perfMetrics).emitCount(eq("InvoiceBookVendorAccepted"), eq(1d), any(), any());
        order.verify(attempts).markBooked("attempt-1", "i1", 28214);
        order.verify(perfMetrics).emitCount(eq("InvoiceBookCommitted"), eq(1d), any(), any());
    }

    @Test
    void a_lost_tx2_leaves_vendor_accepted_counted_but_never_committed() {
        arrangeAcceptedBooking();
        doThrow(commitDeadlock()).when(attempts).markBooked("attempt-1", "i1", 28214);

        assertThrows(RuntimeException.class, () -> orchestrator.bookDraft("i1", null));

        verify(perfMetrics).emitCount(eq("InvoiceBookVendorAccepted"), eq(1d), any(), any());
        verify(perfMetrics, never()).emitCount(eq("InvoiceBookCommitted"), anyDouble(), any(), any());
    }

    @Test
    void the_booking_counters_carry_identifiers_as_fields_not_as_dimensions() {
        arrangeAcceptedBooking();

        orchestrator.bookDraft("i1", null);

        // PerfMetrics:14-18 — dimensions must stay bounded or every invoice becomes its own
        // CloudWatch time series. Identifiers belong in the fields map.
        verify(perfMetrics).emitCount(eq("InvoiceBookVendorAccepted"), eq(1d),
                argThat(java.util.Map::isEmpty),
                argThat(fields -> "i1".equals(fields.get("invoiceUuid"))
                        && "book-i1".equals(fields.get("idempotencyKey"))
                        && "28214".equals(fields.get("bookedNumber"))));
    }

    @Test
    void the_frozen_body_serialises_deterministically() throws Exception {
        // The replay guarantee rests on this: the same primitives must always produce the same
        // bytes, or e-conomic sees a changed payload under an unchanged key. Asserted against
        // Jackson and the DTO directly — the writer just delegates to the application mapper.
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        String a = om.writeValueAsString(EconomicsBookingRequest.of(4525, null));
        String b = om.writeValueAsString(EconomicsBookingRequest.of(4525, null));
        assertEquals(a, b);
        assertEquals(InvoiceBookingAttemptWriter.sha256(a), InvoiceBookingAttemptWriter.sha256(b));
        assertFalse(a.contains("sendBy"), "null sendBy must be omitted, not serialised as null");
        // A different draft number must produce a different hash — otherwise the drift check
        // that guards the POST would be blind to the very change that broke the incident.
        assertNotEquals(InvoiceBookingAttemptWriter.sha256(a),
                InvoiceBookingAttemptWriter.sha256(
                        om.writeValueAsString(EconomicsBookingRequest.of(4526, null))));
    }
}
