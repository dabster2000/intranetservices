package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class InvoiceFinalizationOrchestratorUploadGuardTest {

    @InjectMocks InvoiceFinalizationOrchestrator orchestrator;
    @Mock InvoiceRepository invoices;
    @Mock EconomicsDraftInvoiceApiClient draftApi;
    @Mock EconomicsBookingApiClient bookApi;
    @Mock dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptWriter attempts;
    @Mock dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptRepository attemptRepo;
    @Mock dk.trustworks.intranet.perf.PerfMetrics perfMetrics;

    /**
     * requireEditableInvoice now loads the row under a PESSIMISTIC_WRITE lock (S4), so the
     * finalize path calls findByUuidForUpdate. The lock variant returns the same row, so mirror
     * every findByUuid stub onto it rather than restating ~40 stubs. lenient() because the
     * booking and cancel paths do not take the lock.
     */
    @org.junit.jupiter.api.BeforeEach
    void mirrorLockingLookupOntoPlainLookup() {
        org.mockito.Mockito.lenient()
            .when(invoices.findByUuidForUpdate(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(a -> invoices.findByUuid(a.getArgument(0)));
    }

    /**
     * The booking outbox reserves a durable attempt row before the e-conomic POST (S1). Return a
     * realistic PENDING attempt echoing the caller's arguments, so bookDraft proceeds to the POST
     * exactly as it does in production. The frozen payload is stubbed to a constant whose hash
     * matches, so the pre-POST "payload has not drifted" assertion passes.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubBookingAttemptReservation() {
        final String frozenJson = "{}";
        org.mockito.Mockito.lenient()
            .when(attempts.serialise(org.mockito.ArgumentMatchers.any())).thenReturn(frozenJson);
        org.mockito.Mockito.lenient().when(attempts.reserve(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
            .thenAnswer(a -> {
                var att = new dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttempt();
                att.setUuid("attempt-1");
                att.setInvoiceUuid(a.getArgument(0));
                att.setCompanyUuid(a.getArgument(1));
                att.setEconomicsDraftNumber(a.getArgument(2));
                att.setDraftInvoiceNumber(a.getArgument(3));
                att.setSendBy(a.getArgument(4));
                att.setIdempotencyKey(a.getArgument(5));
                att.setPayloadJson(frozenJson);
                att.setPayloadHash(dk.trustworks.intranet.aggregates.invoice.economics.book
                        .InvoiceBookingAttemptWriter.sha256(frozenJson));
                att.setState(dk.trustworks.intranet.aggregates.invoice.economics.book
                        .InvoiceBookingAttempt.State.PENDING);
                return att;
            });
    }



    @Test
    void createDraft_throws_and_calls_nothing_when_upload_disabled() {
        orchestrator.invoiceUploadEnabled = false;
        assertThrows(BadRequestException.class, () -> orchestrator.createDraft("any"));
        verifyNoInteractions(invoices, draftApi, bookApi);
    }

    @Test
    void bookDraft_throws_and_calls_nothing_when_upload_disabled() {
        orchestrator.invoiceUploadEnabled = false;
        assertThrows(BadRequestException.class, () -> orchestrator.bookDraft("any", null));
        verifyNoInteractions(invoices, draftApi, bookApi);
    }
}
