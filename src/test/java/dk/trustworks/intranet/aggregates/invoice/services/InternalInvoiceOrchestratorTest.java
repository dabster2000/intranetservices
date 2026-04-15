package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.InvoiceToEconomicsDraftMapper;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.model.Company;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for dual-side (ISSUER + DEBTOR) orchestration of INTERNAL invoices.
 *
 * <p>These tests verify the behaviour added to {@link InvoiceFinalizationOrchestrator#bookDraft}
 * in H11: after booking the issuer side, a supplier-invoice voucher is posted to the debtor
 * company's e-conomic. Two outcomes are verified:
 * <ol>
 *   <li>Both sides succeed → economics_status remains {@code BOOKED}.</li>
 *   <li>Debtor side throws → economics_status is demoted to {@code PARTIALLY_UPLOADED}.</li>
 *   <li>Regular INVOICE type → debtor side is never called.</li>
 * </ol>
 *
 * Uses plain Mockito — no Quarkus container or running DB required.
 * {@link DebtorCompanyLookup} and {@link EconomicsAgreementResolver#internalJournalNumber}
 * are mocked to avoid Panache static calls.
 *
 * SPEC-INV-001 §4.5, §4.7, §10.
 */
@ExtendWith(MockitoExtension.class)
class InternalInvoiceOrchestratorTest {

    // ── collaborators of InvoiceFinalizationOrchestrator ─────────────────────

    @InjectMocks
    InvoiceFinalizationOrchestrator orchestrator;

    @Mock InvoiceRepository                  invoices;
    @Mock EconomicsDraftInvoiceApiClient     draftApi;
    @Mock EconomicsBookingApiClient          bookApi;
    @Mock InvoiceToEconomicsDraftMapper      mapper;
    @Mock BillingContextResolver             billingResolver;
    @Mock EconomicsAgreementResolver         agreements;
    @Mock InvoiceItemRecalculator            recalc;
    @Mock BonusService                       bonus;
    @Mock InvoiceWorkService                 work;
    @Mock EconomicsInvoiceService            economicsInvoiceService;
    @Mock DebtorCompanyLookup               debtorCompanyLookup;

    // ── shared fixtures ───────────────────────────────────────────────────────

    private static final String INV_UUID     = "int-inv-001";
    private static final String CO_UUID      = "co-issuer";
    private static final String DEBTOR_UUID  = "co-debtor";
    private static final int    DRAFT_NUMBER = 4521;
    private static final int    INTERNAL_JOURNAL = 77;

    // ── Test 1: happy path — both sides succeed ───────────────────────────────

    /**
     * For an INTERNAL invoice with a debtorCompanyuuid set:
     * - issuer side: bookDraft flow executes normally (draft booked via bookApi)
     * - debtor side: EconomicsInvoiceService.sendVoucherToCompany is called once
     * - final economics_status = BOOKED (set by the issuer booking step, debtor success leaves it unchanged)
     */
    @Test
    void bookDraft_internal_invoice_posts_debtor_side_voucher_and_status_stays_BOOKED()
            throws Exception {
        // Arrange
        Company debtorCompany = company(DEBTOR_UUID, "TW Debtor A/S");
        Invoice inv = internalPendingReview(INV_UUID, CO_UUID, DEBTOR_UUID, DRAFT_NUMBER);
        when(invoices.findByUuid(INV_UUID)).thenReturn(Optional.of(inv));
        when(agreements.tokens(CO_UUID)).thenReturn(tokens("APP", "GRANT"));

        EconomicsBookedInvoice booked = new EconomicsBookedInvoice();
        booked.setBookedInvoiceNumber(90001);
        booked.setDate(LocalDate.of(2026, 4, 15));
        when(bookApi.book(eq("APP"), eq("GRANT"), anyString(), any())).thenReturn(booked);

        // Debtor company lookup succeeds
        when(debtorCompanyLookup.findByUuid(DEBTOR_UUID)).thenReturn(Optional.of(debtorCompany));
        when(agreements.internalJournalNumber(DEBTOR_UUID)).thenReturn(INTERNAL_JOURNAL);

        // Debtor side e-conomic call succeeds (HTTP 200)
        Response okResponse = Response.ok().build();
        when(economicsInvoiceService.sendVoucherToCompany(eq(inv), eq(debtorCompany), eq(INTERNAL_JOURNAL)))
                .thenReturn(okResponse);

        // Act
        Invoice result = orchestrator.bookDraft(INV_UUID, null);

        // Assert — issuer side booked
        assertEquals(InvoiceStatus.CREATED, result.getStatus());
        assertEquals(EconomicsInvoiceStatus.BOOKED, result.getEconomicsStatus());
        assertEquals(90001, result.getInvoicenumber());

        // Assert — debtor side called exactly once with the right journal number
        verify(economicsInvoiceService, times(1))
                .sendVoucherToCompany(eq(inv), eq(debtorCompany), eq(INTERNAL_JOURNAL));

        // Assert — work registered as paid out (issuer side)
        verify(work).registerAsPaidout(inv);
    }

    // ── Test 2: debtor side throws → PARTIALLY_UPLOADED ──────────────────────

    /**
     * When EconomicsInvoiceService.sendVoucherToCompany throws, the exception must be
     * swallowed, the invoice economics_status must be set to PARTIALLY_UPLOADED, and
     * the issuer-side booking must still have completed (status = CREATED).
     */
    @Test
    void bookDraft_internal_invoice_sets_PARTIALLY_UPLOADED_when_debtor_side_throws()
            throws Exception {
        // Arrange
        Company debtorCompany = company(DEBTOR_UUID, "TW Debtor A/S");
        Invoice inv = internalPendingReview(INV_UUID, CO_UUID, DEBTOR_UUID, DRAFT_NUMBER);
        when(invoices.findByUuid(INV_UUID)).thenReturn(Optional.of(inv));
        when(agreements.tokens(CO_UUID)).thenReturn(tokens("APP", "GRANT"));

        EconomicsBookedInvoice booked = new EconomicsBookedInvoice();
        booked.setBookedInvoiceNumber(90002);
        booked.setDate(LocalDate.of(2026, 4, 15));
        when(bookApi.book(eq("APP"), eq("GRANT"), anyString(), any())).thenReturn(booked);

        when(debtorCompanyLookup.findByUuid(DEBTOR_UUID)).thenReturn(Optional.of(debtorCompany));
        when(agreements.internalJournalNumber(DEBTOR_UUID)).thenReturn(INTERNAL_JOURNAL);

        // Debtor side throws
        when(economicsInvoiceService.sendVoucherToCompany(any(Invoice.class), any(Company.class), anyInt()))
                .thenThrow(new RuntimeException("e-conomic connection refused"));

        // Act — must NOT propagate the exception
        Invoice result = orchestrator.bookDraft(INV_UUID, null);

        // Assert — issuer side still completed (CREATED status set before debtor call)
        assertEquals(InvoiceStatus.CREATED, result.getStatus());

        // Assert — economics_status demoted to PARTIALLY_UPLOADED
        assertEquals(EconomicsInvoiceStatus.PARTIALLY_UPLOADED, result.getEconomicsStatus());

        // Assert — persist was called to save the PARTIALLY_UPLOADED status
        verify(invoices, atLeast(1)).persist(inv);
    }

    // ── Test 3: regular INVOICE does NOT trigger debtor-side call ─────────────

    /**
     * A regular INVOICE type must never reach the DEBTOR-side voucher path.
     * This verifies the InvoiceType guard in bookDraft.
     */
    @Test
    void bookDraft_regular_INVOICE_does_not_call_debtor_side() {
        // Arrange — standard INVOICE (not internal)
        Invoice inv = regularPendingReview("reg-001", CO_UUID, 5000);
        when(invoices.findByUuid("reg-001")).thenReturn(Optional.of(inv));
        when(agreements.tokens(CO_UUID)).thenReturn(tokens("APP", "GRANT"));

        EconomicsBookedInvoice booked = new EconomicsBookedInvoice();
        booked.setBookedInvoiceNumber(70001);
        booked.setDate(LocalDate.of(2026, 4, 15));
        when(bookApi.book(eq("APP"), eq("GRANT"), anyString(), any())).thenReturn(booked);

        // Act
        Invoice result = orchestrator.bookDraft("reg-001", null);

        // Assert — no debtor side interaction at all
        verifyNoInteractions(economicsInvoiceService);
        verifyNoInteractions(debtorCompanyLookup);
        assertEquals(EconomicsInvoiceStatus.BOOKED, result.getEconomicsStatus());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Invoice internalPendingReview(String uuid, String companyUuid,
                                          String debtorUuid, int draftNumber) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INTERNAL);
        inv.setStatus(InvoiceStatus.PENDING_REVIEW);
        inv.setEconomicsDraftNumber(draftNumber);
        inv.setDebtorCompanyuuid(debtorUuid);
        inv.setContractuuid("contract-uuid");
        inv.setProjectuuid("project-uuid");
        inv.setMonth(4);
        inv.setYear(2026);
        inv.setInvoiceitems(List.of());
        inv.setGrandTotal(50_000.0);

        Company issuerCompany = new Company();
        issuerCompany.setUuid(companyUuid);
        inv.setCompany(issuerCompany);
        return inv;
    }

    private Invoice regularPendingReview(String uuid, String companyUuid, int draftNumber) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INVOICE);
        inv.setStatus(InvoiceStatus.PENDING_REVIEW);
        inv.setEconomicsDraftNumber(draftNumber);
        inv.setContractuuid("contract-uuid");
        inv.setProjectuuid("project-uuid");
        inv.setMonth(4);
        inv.setYear(2026);
        inv.setInvoiceitems(List.of());

        Company company = new Company();
        company.setUuid(companyUuid);
        inv.setCompany(company);
        return inv;
    }

    private Company company(String uuid, String name) {
        Company c = new Company();
        c.setUuid(uuid);
        c.setName(name);
        return c;
    }

    private EconomicsAgreementResolver.Tokens tokens(String appSecret, String agreementGrant) {
        return new EconomicsAgreementResolver.Tokens(appSecret, agreementGrant);
    }
}
