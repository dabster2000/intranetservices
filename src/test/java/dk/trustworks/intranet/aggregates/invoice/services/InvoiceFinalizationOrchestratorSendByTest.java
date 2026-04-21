package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.EanPrerequisiteErrorDto;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingRequest;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.InvoiceToEconomicsDraftMapper;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for sendBy validation and EAN prerequisite gating in
 * {@link InvoiceFinalizationOrchestrator#bookDraft(String, String)}.
 *
 * <p>Uses plain Mockito — no running DB or Quarkus container needed.
 * SPEC-INV-001 §4.2, §4.3, §6.6.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceFinalizationOrchestratorSendByTest {

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
    @Mock dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService economicsInvoiceService;
    @Mock DebtorCompanyLookup               debtorCompanyLookup;
    @Mock EanPrerequisiteChecker            eanChecker;

    /**
     * bookDraft() now resolves Q2C {@code number} → legacy {@code draftInvoiceNumber}
     * via {@code draftApi.getByNumber()} before calling the legacy booking endpoint.
     * Stubbed leniently so tests that exit early (prereq failures, unsupported sendBy)
     * don't trip on unused-stub strict checks.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubDraftInvoiceNumberResolution() {
        EconomicsDraftInvoice fetched = new EconomicsDraftInvoice();
        fetched.setDraftInvoiceNumber(4521);
        lenient().when(draftApi.getByNumber(anyString(), anyString(), anyInt()))
                .thenReturn(fetched);
    }

    // ── test 1: sendBy="ean" invokes prerequisite check ──────────────────────

    @Test
    void bookDraft_ean_invokes_prerequisite_check() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(billingResolver.resolve(inv)).thenReturn(billingContext(inv));
        when(eanChecker.check(any())).thenReturn(null); // all checks pass
        when(agreements.tokens("co-1")).thenReturn(tokens());
        when(bookApi.book(anyString(), anyString(), anyString(), any())).thenReturn(bookedInvoice(80123));

        orchestrator.bookDraft("i1", "ean");

        verify(eanChecker).check(any(BillingContext.class));
        verify(bookApi).book(anyString(), anyString(), anyString(), any());
    }

    // ── test 1b: sendBy="ean" persists on the invoice entity ──────────────────

    @Test
    void bookDraft_ean_persists_sendBy_on_invoice() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(billingResolver.resolve(inv)).thenReturn(billingContext(inv));
        when(eanChecker.check(any())).thenReturn(null);
        when(agreements.tokens("co-1")).thenReturn(tokens());
        when(bookApi.book(anyString(), anyString(), anyString(), any())).thenReturn(bookedInvoice(80123));

        Invoice out = orchestrator.bookDraft("i1", "ean");

        assertEquals("ean", out.getSendBy());
    }

    // ── test 1c: sendBy="Email" persists on the invoice entity ──────────────

    @Test
    void bookDraft_email_persists_sendBy_on_invoice() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(tokens());
        when(bookApi.book(anyString(), anyString(), anyString(), any())).thenReturn(bookedInvoice(80123));

        Invoice out = orchestrator.bookDraft("i1", "email");

        assertEquals("Email", out.getSendBy());
    }

    // ── test 1d: sendBy=null persists null on the invoice entity ─────────────

    @Test
    void bookDraft_null_sendBy_persists_null_on_invoice() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(tokens());
        when(bookApi.book(anyString(), anyString(), anyString(), any())).thenReturn(bookedInvoice(80123));

        Invoice out = orchestrator.bookDraft("i1", null);

        assertNull(out.getSendBy());
    }

    // ── test 2: sendBy="ean" throws when prereqs fail ────────────────────────

    @Test
    void bookDraft_ean_throws_when_prereqs_fail() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(billingResolver.resolve(inv)).thenReturn(billingContext(inv));

        EanPrerequisiteErrorDto errorDto = new EanPrerequisiteErrorDto(
                "EAN prerequisites not satisfied",
                Map.of("EAN_MISSING", "Billing client has no EAN configured"));
        when(eanChecker.check(any())).thenReturn(errorDto);

        EanPrerequisitesNotMet thrown = assertThrows(
                EanPrerequisitesNotMet.class,
                () -> orchestrator.bookDraft("i1", "ean"));

        assertSame(errorDto, thrown.getErrorDto());
        assertEquals("EAN prerequisites not satisfied", thrown.getMessage());
        // booking API must NOT be called when prereqs fail
        verifyNoInteractions(bookApi);
    }

    // ── test 3: sendBy="Email" sends capitalised to API ──────────────────────

    @Test
    void bookDraft_email_sends_capitalised_to_api() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(tokens());
        when(bookApi.book(anyString(), anyString(), anyString(), any())).thenReturn(bookedInvoice(80123));

        orchestrator.bookDraft("i1", "Email");

        ArgumentCaptor<EconomicsBookingRequest> reqCaptor =
                ArgumentCaptor.forClass(EconomicsBookingRequest.class);
        verify(bookApi).book(anyString(), anyString(), anyString(), reqCaptor.capture());
        assertEquals("Email", reqCaptor.getValue().getSendBy());
        // EAN checker must NOT be invoked for email
        verifyNoInteractions(eanChecker);
    }

    // ── test 3b: sendBy="email" (lowercase) also normalises to "Email" ───────

    @Test
    void bookDraft_email_lowercase_normalises_to_capitalised() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(tokens());
        when(bookApi.book(anyString(), anyString(), anyString(), any())).thenReturn(bookedInvoice(80123));

        orchestrator.bookDraft("i1", "email");

        ArgumentCaptor<EconomicsBookingRequest> reqCaptor =
                ArgumentCaptor.forClass(EconomicsBookingRequest.class);
        verify(bookApi).book(anyString(), anyString(), anyString(), reqCaptor.capture());
        assertEquals("Email", reqCaptor.getValue().getSendBy());
    }

    // ── test 4: sendBy=null omits the field ──────────────────────────────────

    @Test
    void bookDraft_null_sendBy_passes_null_to_api() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(tokens());
        when(bookApi.book(anyString(), anyString(), anyString(), any())).thenReturn(bookedInvoice(80123));

        orchestrator.bookDraft("i1", null);

        ArgumentCaptor<EconomicsBookingRequest> reqCaptor =
                ArgumentCaptor.forClass(EconomicsBookingRequest.class);
        verify(bookApi).book(anyString(), anyString(), anyString(), reqCaptor.capture());
        assertNull(reqCaptor.getValue().getSendBy());
        verifyNoInteractions(eanChecker);
    }

    // ── test 4b: sendBy="" (blank) normalised to null ────────────────────────

    @Test
    void bookDraft_blank_sendBy_normalised_to_null() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(tokens());
        when(bookApi.book(anyString(), anyString(), anyString(), any())).thenReturn(bookedInvoice(80123));

        orchestrator.bookDraft("i1", "  ");

        ArgumentCaptor<EconomicsBookingRequest> reqCaptor =
                ArgumentCaptor.forClass(EconomicsBookingRequest.class);
        verify(bookApi).book(anyString(), anyString(), anyString(), reqCaptor.capture());
        assertNull(reqCaptor.getValue().getSendBy());
    }

    // ── test 5: unsupported sendBy throws ────────────────────────────────────

    @Test
    void bookDraft_unsupported_sendBy_throws_BadRequest() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));

        jakarta.ws.rs.BadRequestException thrown = assertThrows(
                jakarta.ws.rs.BadRequestException.class,
                () -> orchestrator.bookDraft("i1", "fax"));

        assertEquals(400, thrown.getResponse().getStatus());
        assertTrue(thrown.getMessage().contains("fax"));
        verifyNoInteractions(bookApi);
        verifyNoInteractions(eanChecker);
    }

    // ── test 6: sendBy="EAN" (uppercase) also routes through checker ─────────

    @Test
    void bookDraft_ean_uppercase_routes_through_checker_and_normalises() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(billingResolver.resolve(inv)).thenReturn(billingContext(inv));
        when(eanChecker.check(any())).thenReturn(null);
        when(agreements.tokens("co-1")).thenReturn(tokens());
        when(bookApi.book(anyString(), anyString(), anyString(), any())).thenReturn(bookedInvoice(80123));

        orchestrator.bookDraft("i1", "EAN");

        verify(eanChecker).check(any(BillingContext.class));
        ArgumentCaptor<EconomicsBookingRequest> reqCaptor =
                ArgumentCaptor.forClass(EconomicsBookingRequest.class);
        verify(bookApi).book(anyString(), anyString(), anyString(), reqCaptor.capture());
        assertEquals("ean", reqCaptor.getValue().getSendBy());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Invoice pendingReviewInvoice(String uuid, String companyUuid, int draftNumber) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INVOICE);
        inv.setStatus(InvoiceStatus.PENDING_REVIEW);
        inv.setEconomicsDraftNumber(draftNumber);
        Company company = new Company();
        company.setUuid(companyUuid);
        inv.setCompany(company);
        inv.setContractuuid("contract-uuid");
        inv.setProjectuuid("project-uuid");
        inv.setMonth(4);
        inv.setYear(2026);
        inv.setInvoiceitems(List.of());
        return inv;
    }

    private BillingContext billingContext(Invoice inv) {
        Contract c = new Contract();
        c.setUuid("contract-uuid");
        c.setClientuuid("client-uuid");
        Client cl = new Client();
        cl.setUuid("client-uuid");
        cl.setName("Acme A/S");
        return new BillingContext(inv, c, cl);
    }

    private EconomicsAgreementResolver.Tokens tokens() {
        return new EconomicsAgreementResolver.Tokens("APP", "GRANT");
    }

    private EconomicsBookedInvoice bookedInvoice(int number) {
        EconomicsBookedInvoice booked = new EconomicsBookedInvoice();
        booked.setBookedInvoiceNumber(number);
        booked.setDate(LocalDate.of(2026, 4, 15));
        return booked;
    }
}
