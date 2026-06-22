package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.InvoiceToEconomicsDraftMapper;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.perf.PerfMetrics;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies the DEBTOR-side gate in {@link InvoiceFinalizationOrchestrator#bookDraft(String, String)}
 * fires for an internal CREDIT_NOTE (carrying a debtor) and stays skipped for a client
 * CREDIT_NOTE (no debtor). Plain Mockito — no DB.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceFinalizationOrchestratorCreditNoteTest {

    @InjectMocks InvoiceFinalizationOrchestrator orchestrator;

    @Mock InvoiceRepository                 invoices;
    @Mock EconomicsDraftInvoiceApiClient    draftApi;
    @Mock EconomicsBookingApiClient         bookApi;
    @Mock InvoiceToEconomicsDraftMapper     mapper;
    @Mock BillingContextResolver            billingResolver;
    @Mock EconomicsAgreementResolver        agreements;
    @Mock InvoiceItemRecalculator           recalc;
    @Mock BonusService                      bonus;
    @Mock InvoiceWorkService                work;
    @Mock EconomicsInvoiceService            economicsInvoiceService;
    @Mock DebtorCompanyLookup               debtorCompanyLookup;
    @Mock EanPrerequisiteChecker            eanChecker;
    @Mock PerfMetrics                       perfMetrics;

    @BeforeEach
    void stub() {
        orchestrator.invoiceUploadEnabled = true;
        EconomicsDraftInvoice fetched = new EconomicsDraftInvoice();
        fetched.setDraftInvoiceNumber(4521);
        lenient().when(draftApi.getByNumber(anyString(), anyString(), anyInt())).thenReturn(fetched);
        EconomicsBookedInvoice booked = new EconomicsBookedInvoice();
        booked.setBookedInvoiceNumber(80123);
        booked.setDate(LocalDate.of(2026, 6, 22));
        lenient().when(bookApi.book(anyString(), anyString(), anyString(), any())).thenReturn(booked);
        lenient().when(agreements.tokens(anyString()))
                .thenReturn(new EconomicsAgreementResolver.Tokens("APP", "GRANT"));
    }

    private Invoice creditNote(String uuid, String issuerUuid, String debtorUuid) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.CREDIT_NOTE);
        inv.setStatus(InvoiceStatus.PENDING_REVIEW);
        inv.setEconomicsDraftNumber(4521);
        inv.setDebtorCompanyuuid(debtorUuid); // null = client CN; set = internal CN
        Company issuer = new Company();
        issuer.setUuid(issuerUuid);
        inv.setCompany(issuer);
        inv.setInvoiceitems(List.of());
        return inv;
    }

    @Test
    void bookDraft_internalCreditNote_posts_debtor_voucher() throws Exception {
        Invoice cn = creditNote("cn-1", "issuer-co", "debtor-co");
        when(invoices.findByUuid("cn-1")).thenReturn(Optional.of(cn));
        Company debtor = new Company();
        debtor.setUuid("debtor-co");
        debtor.setName("Trustworks A/S");
        when(debtorCompanyLookup.findByUuid("debtor-co")).thenReturn(Optional.of(debtor));
        when(agreements.internalJournalNumber("debtor-co")).thenReturn(77);
        when(economicsInvoiceService.sendVoucherToCompany(any(), any(), anyInt()))
                .thenReturn(Response.ok().build());

        orchestrator.bookDraft("cn-1", null);

        verify(economicsInvoiceService).sendVoucherToCompany(any(Invoice.class), any(Company.class), eq(77));
    }

    @Test
    void bookDraft_clientCreditNote_no_debtor_skips_debtor_voucher() throws Exception {
        Invoice cn = creditNote("cn-2", "issuer-co", null); // client CN: no debtor
        when(invoices.findByUuid("cn-2")).thenReturn(Optional.of(cn));

        orchestrator.bookDraft("cn-2", null);

        verify(economicsInvoiceService, never()).sendVoucherToCompany(any(), any(), anyInt());
    }
}
