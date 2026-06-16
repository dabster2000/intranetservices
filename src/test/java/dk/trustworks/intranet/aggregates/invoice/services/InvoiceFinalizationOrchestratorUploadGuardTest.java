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
