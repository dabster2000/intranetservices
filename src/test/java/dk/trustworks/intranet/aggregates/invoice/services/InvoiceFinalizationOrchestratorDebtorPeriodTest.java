package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.CreatedResult;
import dk.trustworks.intranet.aggregates.invoice.economics.InvoiceToEconomicsDraftMapper;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.book.InvoiceBookingAttemptWriter;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftLine;
import dk.trustworks.intranet.aggregates.invoice.economics.period.AccountingPeriodPreflight;
import dk.trustworks.intranet.aggregates.invoice.economics.period.EconomicsAccountingPeriod;
import dk.trustworks.intranet.aggregates.invoice.economics.period.EconomicsAccountingYearsApiClient;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.perf.PerfMetrics;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The 2026-09-09 half-booking, replayed end to end through the nightly path with the REAL
 * {@link AccountingPeriodPreflight} in front of the orchestrator.
 *
 * <p>Production sequence for internal invoice 7803f536 (issuer Trustworks Technology ApS, debtor
 * Trustworks A/S, dated 2026-07-31): the pre-flight asked only the issuer's e-conomic, where July
 * 2026 was open; the issuer side booked irreversibly as 70479; the debtor-side supplier voucher was
 * then refused by Trustworks A/S with {@code E04041 "Perioden er spærret."}; the row was left at
 * PARTIALLY_UPLOADED with nothing able to complete or unwind it.
 *
 * <p>The other orchestrator tests mock the pre-flight, which proves the wiring but not the
 * decision. Here the decision is real and the vendor is mocked one agreement at a time, so the
 * assertion is the one that matters: with the debtor's July barred, <em>no</em> call reaches the
 * draft API, the booking API, the outbox or the debtor voucher — on either side — and the invoice is
 * still QUEUED.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceFinalizationOrchestratorDebtorPeriodTest {

    private static final String INV = "7803f536-ecc1-4bcf-8f47-b8574795155b";
    private static final String TWT = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3";
    private static final String TW_AS = "d8894494-2fb4-4f72-9e05-e6032e6dd691";
    private static final LocalDate JUL_31 = LocalDate.of(2026, 7, 31);
    private static final EconomicsAgreementResolver.Tokens TWT_TOKENS =
            new EconomicsAgreementResolver.Tokens("twt-secret", "twt-grant");
    private static final EconomicsAgreementResolver.Tokens TW_AS_TOKENS =
            new EconomicsAgreementResolver.Tokens("as-secret", "as-grant");

    /** The real guard — its own collaborators are mocked, its decision is not. */
    @InjectMocks AccountingPeriodPreflight preflight;
    @InjectMocks InvoiceFinalizationOrchestrator orchestrator;
    @InjectMocks InternalInvoiceOrchestrator internal;

    // pre-flight collaborators
    @Mock EconomicsAccountingYearsApiClient periodsApi;
    @Mock DebtorCompanyLookup debtorCompanyLookup;
    // shared
    @Mock EconomicsAgreementResolver agreements;
    @Mock InvoiceRepository invoices;
    // orchestrator collaborators
    @Mock EconomicsDraftInvoiceApiClient draftApi;
    @Mock EconomicsBookingApiClient bookApi;
    @Mock InvoiceToEconomicsDraftMapper mapper;
    @Mock BillingContextResolver billingResolver;
    @Mock InvoiceItemRecalculator recalc;
    @Mock InvoiceExchangeRateService exchangeRates;
    @Mock InvoiceAttributionService attributionService;
    @Mock BonusService bonus;
    @Mock jakarta.enterprise.event.Event<InvoiceBookedEvent> invoiceBooked;
    @Mock EconomicsInvoiceService economicsInvoiceService;
    @Mock EanPrerequisiteChecker eanChecker;
    @Mock CreditNoteCoverageService creditCoverage;
    @Mock PerfMetrics perfMetrics;
    @Mock InvoiceBookingAttemptWriter attempts;
    @Mock InvoiceBookingAttemptRepository attemptRepo;
    @Mock jakarta.transaction.TransactionManager transactionManager;

    @BeforeEach
    void wireTheRealGuardThroughTheNightlyPath() throws Exception {
        orchestrator.periodPreflight = preflight;
        orchestrator.invoiceUploadEnabled = true;
        internal.issuerSide = orchestrator;

        // @ConfigProperty defaults to true in the container; the bare field defaults to false.
        Field enabled = AccountingPeriodPreflight.class.getDeclaredField("preflightEnabled");
        enabled.setAccessible(true);
        enabled.setBoolean(preflight, true);

        // requireEditableInvoice takes the row under PESSIMISTIC_WRITE; same row, same stub.
        lenient().when(invoices.findByUuidForUpdate(anyString()))
                .thenAnswer(a -> invoices.findByUuid(a.getArgument(0)));
    }

    @Test
    void the_nightly_path_books_nothing_on_either_side_when_the_debtor_period_is_barred() {
        Invoice inv = queuedInternal();
        EconomicsInvoiceStatus statusBefore = inv.getEconomicsStatus();
        when(invoices.findByUuid(INV)).thenReturn(Optional.of(inv));
        when(agreements.tokens(TWT)).thenReturn(TWT_TOKENS);
        when(agreements.tokens(TW_AS)).thenReturn(TW_AS_TOKENS);
        when(periodsApi.listPeriods("twt-secret", "twt-grant", 100, 0))
                .thenReturn(List.of(july2026(false)));          // open at the issuer
        when(periodsApi.listPeriods("as-secret", "as-grant", 100, 0))
                .thenReturn(List.of(july2026(true)));           // barred at the debtor
        when(debtorCompanyLookup.findByUuid(TW_AS))
                .thenReturn(Optional.of(company(TW_AS, "Trustworks A/S")));

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> internal.finalizeAutomatically(INV));

        String msg = thrown.getMessage();
        assertTrue(msg.contains("Trustworks A/S"), msg);
        assertTrue(msg.contains("2026-07-31"), msg);
        assertTrue(msg.contains("barred"), msg);
        assertTrue(msg.contains("Nothing was sent to e-conomic"), msg);

        // Nothing on the ISSUER side: no draft, no booking POST, no outbox row.
        verifyNoInteractions(draftApi, bookApi, attempts);
        // Nothing on the DEBTOR side either.
        verifyNoInteractions(economicsInvoiceService);
        // And the row is exactly as the nightly job found it.
        assertEquals(InvoiceStatus.QUEUED, inv.getStatus());
        assertNull(inv.getEconomicsDraftNumber());
        assertNull(inv.getEconomicsBookedNumber());
        assertEquals(statusBefore, inv.getEconomicsStatus());

        // Both agreements were actually asked — the debtor one is the whole fix.
        verify(periodsApi).listPeriods("twt-secret", "twt-grant", 100, 0);
        verify(periodsApi).listPeriods("as-secret", "as-grant", 100, 0);
    }

    /** The same invoice, once the debtor's July is open too, must reach e-conomic as before. */
    @Test
    void the_same_invoice_proceeds_to_the_issuer_draft_once_both_periods_are_open() {
        Invoice inv = queuedInternal();
        when(invoices.findByUuid(INV)).thenReturn(Optional.of(inv));
        when(agreements.tokens(TWT)).thenReturn(TWT_TOKENS);
        when(agreements.tokens(TW_AS)).thenReturn(TW_AS_TOKENS);
        when(periodsApi.listPeriods("twt-secret", "twt-grant", 100, 0))
                .thenReturn(List.of(july2026(false)));
        when(periodsApi.listPeriods("as-secret", "as-grant", 100, 0))
                .thenReturn(List.of(july2026(false)));

        Contract contract = new Contract();
        contract.setUuid("contract-1");
        contract.setClientuuid("intercompany-client");
        Client intercompany = new Client();
        intercompany.setUuid("intercompany-client");
        intercompany.setName("Trustworks A/S");
        when(billingResolver.resolve(inv)).thenReturn(new BillingContext(inv, contract, intercompany));
        when(agreements.layoutNumber(TWT)).thenReturn(22);
        when(agreements.immediatePaymentTermFor(TWT)).thenReturn(3);
        when(agreements.vatZoneFor(any(), any())).thenReturn(1);
        when(agreements.productNumber(TWT)).thenReturn("1");
        EconomicsDraftInvoice draft = new EconomicsDraftInvoice();
        draft.setDraftInvoiceNumber(179);
        when(mapper.toDraft(any())).thenReturn(draft);
        when(mapper.toLines(any())).thenReturn(List.of(new EconomicsDraftLine()));
        CreatedResult created = new CreatedResult();
        created.setNumber(13);
        when(draftApi.create(any(), any(), anyString(), any())).thenReturn(created);

        Invoice out = orchestrator.createDraft(INV);

        verify(draftApi).create(eq("twt-secret"), eq("twt-grant"), anyString(), any());
        assertEquals(InvoiceStatus.PENDING_REVIEW, out.getStatus());
        assertEquals(13, out.getEconomicsDraftNumber());
        verify(periodsApi).listPeriods("as-secret", "as-grant", 100, 0);
        verifyNoInteractions(debtorCompanyLookup);   // no refusal, so no name to look up
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /** 7803f536 as the nightly job found it: QUEUED, dated into the client invoice's period. */
    private static Invoice queuedInternal() {
        Invoice inv = new Invoice();
        inv.setUuid(INV);
        inv.setType(InvoiceType.INTERNAL);
        inv.setStatus(InvoiceStatus.QUEUED);
        inv.setCompany(company(TWT, "Trustworks Technology ApS"));
        inv.setDebtorCompanyuuid(TW_AS);
        inv.setBillingClientUuid("intercompany-client");
        inv.setContractuuid("contract-1");
        inv.setInvoicedate(JUL_31);
        inv.setDuedate(JUL_31.plusDays(1));
        inv.setInvoiceRefUuid("293a1303-4e64-4cd4-a491-4621ebb69975");
        return inv;
    }

    private static EconomicsAccountingPeriod july2026(boolean isBarred) {
        EconomicsAccountingPeriod p = new EconomicsAccountingPeriod();
        p.setYear("2026/2027");
        p.setPeriodNumber(isBarred ? 133 : 37);   // the real period numbers at A/S and at TWT
        p.setDateFrom("2026-07-01");
        p.setDateTo("2026-07-31");
        p.setIsClosed(false);
        p.setIsBarred(isBarred);
        return p;
    }

    private static Company company(String uuid, String name) {
        Company c = new Company();
        c.setUuid(uuid);
        c.setName(name);
        return c;
    }
}
