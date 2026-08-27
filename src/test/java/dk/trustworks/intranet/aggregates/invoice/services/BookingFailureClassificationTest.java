package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.model.Company;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InvoiceFinalizationOrchestrator#isProvablyUnbooked}.
 *
 * <p>The predicate decides two irreversible things at once: whether the attempt is recorded FAILED
 * (retryable) or NEEDS_RECONCILIATION (terminal, human-handled), and whether the e-conomic draft
 * behind a rolled-back auto-finalize gets deleted. A false positive deletes a draft belonging to a
 * booking that really happened; a false negative strands drafts forever. Neither is recoverable by
 * a retry, so the boundary is pinned here rather than inferred from the call sites.
 */
class BookingFailureClassificationTest {

    /** The 2026-08-27 TWC shape: e-conomic refused the document outright. Nothing was booked. */
    @Test
    void barred_period_400_is_provably_unbooked() {
        WebApplicationException barred = economicError(400,
                "e-conomic booking API HTTP 400: {\"message\":\"Cannot book the current invoice: "
                        + "The invoice date 27.08.2026 lies within a barred period. (a booking of "
                        + "draft invoice 73 failed.)\",\"errorCode\":\"E04870\"}");

        assertTrue(InvoiceFinalizationOrchestrator.isProvablyUnbooked(barred));
    }

    /**
     * PayloadChanged is the one 400 that must NOT count as proof: it says the vendor is already
     * holding something under this key. Deleting the draft, or minting a fresh attempt, on the
     * strength of it is how 2026-08-07 produced the duplicate 28218.
     */
    @Test
    void payload_changed_400_is_not_proof_of_anything() {
        WebApplicationException payloadChanged = economicError(400,
                "e-conomic booking API HTTP 400: {\"title\":\"Content must be the same when using "
                        + "the same Idempotency key.\",\"errorCode\":\"PayloadChanged\"}");

        assertFalse(InvoiceFinalizationOrchestrator.isProvablyUnbooked(payloadChanged));
    }

    @Test
    void conflict_and_server_errors_leave_the_outcome_unknown() {
        assertFalse(InvoiceFinalizationOrchestrator.isProvablyUnbooked(economicError(409, "conflict")));
        assertFalse(InvoiceFinalizationOrchestrator.isProvablyUnbooked(economicError(500, "boom")));
        assertFalse(InvoiceFinalizationOrchestrator.isProvablyUnbooked(economicError(503, "unavailable")));
    }

    /** No response at all — the POST may or may not have landed. */
    @Test
    void missing_response_leaves_the_outcome_unknown() {
        assertFalse(InvoiceFinalizationOrchestrator.isProvablyUnbooked(
                new WebApplicationException("connection reset")));
    }

    @Test
    void other_client_errors_are_definitive_refusals() {
        assertTrue(InvoiceFinalizationOrchestrator.isProvablyUnbooked(economicError(404, "no such draft")));
        assertTrue(InvoiceFinalizationOrchestrator.isProvablyUnbooked(economicError(422, "validation")));
    }

    /**
     * Our own pre-flight guard fires before this draft's body ever leaves the process, so the
     * evidence it is protecting belongs to an EARLIER draft. This one is certainly unbooked — which
     * is what stops the nightly batchlet stranding one fresh draft per run behind a stuck invoice.
     */
    @Test
    void our_own_unresolved_sibling_409_is_provably_unbooked_for_this_draft() {
        WebApplicationException guard =
                new InvoiceFinalizationOrchestrator.UnresolvedBookingAttemptException(
                        "Invoice inv-1 has an earlier booking attempt whose outcome is unresolved.");

        assertEquals(409, guard.getResponse().getStatus(), "must still be a 409 on the wire");
        assertTrue(InvoiceFinalizationOrchestrator.isProvablyUnbooked(guard));
    }

    /** Mirrors what the rest-client error mapper throws: the vendor body carried in the message. */
    private WebApplicationException economicError(int status, String message) {
        return new WebApplicationException(message, Response.status(status).build());
    }

    // ── barred-period message rewriting ─────────────────────────────────────────────────────

    private static final String BARRED_BODY =
            "e-conomic booking API HTTP 400: {\"message\":\"Cannot book the current invoice: The "
                    + "invoice date 30.06.2026 lies within a barred period. (a booking of draft "
                    + "invoice 73 failed.)\",\"errorCode\":\"E04870\"}";

    /**
     * The operator saw only vendor JSON, which names the date and the draft but never the
     * agreement — and the agreement is the fact that explains everything, since the identical date
     * had booked four invoices minutes earlier in a different company.
     */
    @Test
    void barred_period_message_names_the_company_the_date_and_both_remedies() {
        RuntimeException rewritten = InvoiceFinalizationOrchestrator.asBarredPeriodError(
                internalInvoice("Trustworks Cyber Security ApS", LocalDate.of(2026, 6, 30)),
                BARRED_BODY);

        assertNotNull(rewritten);
        String msg = rewritten.getMessage();
        assertTrue(msg.contains("Trustworks Cyber Security ApS"), msg);
        assertTrue(msg.contains("2026-06-30"), msg);
        assertTrue(msg.contains("open period"), "should offer picking another date: " + msg);
        assertTrue(msg.contains("unbarred"), "should offer opening the period: " + msg);
        assertTrue(msg.contains("Nothing was booked"),
                "must say the ledger is untouched, or the operator dare not retry: " + msg);
        assertEquals(400, ((WebApplicationException) rewritten).getResponse().getStatus());
    }

    /** Recognised by the message text too — e-conomic has not always carried the code. */
    @Test
    void barred_period_is_recognised_without_the_error_code() {
        assertNotNull(InvoiceFinalizationOrchestrator.asBarredPeriodError(
                internalInvoice("Trustworks A/S", LocalDate.of(2026, 6, 30)),
                "e-conomic booking API HTTP 400: the invoice date lies within a barred period"));
    }

    /** Any other failure keeps its raw vendor text — rewriting would destroy the only evidence. */
    @Test
    void unrelated_failures_are_left_alone() {
        assertNull(InvoiceFinalizationOrchestrator.asBarredPeriodError(
                internalInvoice("Trustworks A/S", LocalDate.of(2026, 6, 30)),
                "e-conomic booking API HTTP 400: {\"errorCode\":\"E00999\",\"message\":\"Layout not found\"}"));
    }

    /** Never let a missing company relation turn a bad-date message into a NullPointerException. */
    @Test
    void survives_an_invoice_with_no_company_relation() {
        Invoice inv = new Invoice();
        inv.setInvoicedate(LocalDate.of(2026, 6, 30));

        RuntimeException rewritten =
                InvoiceFinalizationOrchestrator.asBarredPeriodError(inv, BARRED_BODY);

        assertNotNull(rewritten);
        assertTrue(rewritten.getMessage().contains("the issuing company"), rewritten.getMessage());
    }

    private Invoice internalInvoice(String companyName, LocalDate invoicedate) {
        Company company = new Company();
        company.setName(companyName);
        Invoice inv = new Invoice();
        inv.setCompany(company);
        inv.setInvoicedate(invoicedate);
        return inv;
    }

    /** A vendor 400 arrives as BadRequestException in production — same classification. */
    @Test
    void bad_request_subclass_is_classified_the_same() {
        assertTrue(InvoiceFinalizationOrchestrator.isProvablyUnbooked(
                new BadRequestException("e-conomic booking API HTTP 400: barred period")));
    }
}
