package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttempt;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InvoiceFinalizationOrchestrator#assertNoUnresolvedPostedSibling}.
 *
 * <p>The booking idempotency key is the invariant {@code "book-" + invoiceUuid}. A NEW attempt for
 * the same invoice therefore sends a different body under the same key: PayloadChanged inside the
 * vendor's one-hour TTL, and a SECOND booked invoice after it (the 2026-08-07 duplicate 28218
 * mechanism). The guard must refuse to mint a new attempt while an earlier one has been POSTed but
 * not resolved — while still allowing replay of the SAME attempt (same draftInvoiceNumber), which
 * is the designed recovery.
 */
class BookingDoubleBookGuardTest {

    @Test
    void blocks_when_a_posted_attempt_for_a_different_draft_is_unresolved() {
        InvoiceBookingAttempt stale = posted(49, InvoiceBookingAttempt.State.PENDING);

        WebApplicationException thrown = assertThrows(WebApplicationException.class, () ->
                InvoiceFinalizationOrchestrator.assertNoUnresolvedPostedSibling(
                        List.of(stale), 50, "inv-1"));

        assertEquals(409, thrown.getResponse().getStatus());
        String body = String.valueOf(thrown.getResponse().getEntity());
        assertTrue(body.contains("49"), "409 body should identify the stale attempt's draft: " + body);
    }

    @Test
    void blocks_on_needs_reconciliation_siblings_too() {
        InvoiceBookingAttempt stale = posted(49, InvoiceBookingAttempt.State.NEEDS_RECONCILIATION);

        assertThrows(WebApplicationException.class, () ->
                InvoiceFinalizationOrchestrator.assertNoUnresolvedPostedSibling(
                        List.of(stale), 50, "inv-1"));
    }

    @Test
    void allows_replay_of_the_same_draft_number() {
        InvoiceBookingAttempt same = posted(49, InvoiceBookingAttempt.State.PENDING);

        assertDoesNotThrow(() ->
                InvoiceFinalizationOrchestrator.assertNoUnresolvedPostedSibling(
                        List.of(same), 49, "inv-1"));
    }

    @Test
    void allows_when_no_posted_unresolved_attempts_exist() {
        assertDoesNotThrow(() ->
                InvoiceFinalizationOrchestrator.assertNoUnresolvedPostedSibling(
                        List.of(), 50, "inv-1"));
    }

    private InvoiceBookingAttempt posted(int draftInvoiceNumber, InvoiceBookingAttempt.State state) {
        InvoiceBookingAttempt a = new InvoiceBookingAttempt();
        a.setUuid("att-" + draftInvoiceNumber);
        a.setDraftInvoiceNumber(draftInvoiceNumber);
        a.setState(state);
        a.setPostedAt(LocalDateTime.now().minusMinutes(30));
        return a;
    }
}
