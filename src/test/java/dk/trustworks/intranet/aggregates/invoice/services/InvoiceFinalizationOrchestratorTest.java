package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.CreatedResult;
import dk.trustworks.intranet.aggregates.invoice.economics.DraftContext;
import dk.trustworks.intranet.aggregates.invoice.economics.InvoiceToEconomicsDraftMapper;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookedInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.DraftInvoiceLineBatchRequest;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftLine;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
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
 * TDD failing tests for {@link InvoiceFinalizationOrchestrator} (H8).
 * Uses plain Mockito — no running DB or Quarkus container needed.
 * SPEC-INV-001 §4.1, §7.1, §7.2.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceFinalizationOrchestratorTest {

    @InjectMocks InvoiceFinalizationOrchestrator orchestrator;

    @Mock InvoiceRepository                 invoices;
    @Mock EconomicsDraftInvoiceApiClient    draftApi;
    @Mock EconomicsBookingApiClient         bookApi;
    @Mock InvoiceToEconomicsDraftMapper     mapper;
    @Mock BillingContextResolver            billingResolver;
    @Mock EconomicsAgreementResolver        agreements;
    @Mock InvoiceItemRecalculator           recalc;
    @Mock InvoiceAttributionService         attributionService;
    @Mock BonusService                      bonus;
    @Mock InvoiceWorkService                work;
    @Mock dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService economicsInvoiceService;
    @Mock DebtorCompanyLookup               debtorCompanyLookup;

    // ── test 1: createDraft happy path ────────────────────────────────────────

    @Test
    void createDraft_calls_post_drafts_then_bulk_lines_and_sets_PENDING_REVIEW() {
        Invoice inv = draftInvoice("i1", "co-1");
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(billingResolver.resolve(inv)).thenReturn(new BillingContext(inv, contract(), billingClient()));
        when(agreements.tokens("co-1")).thenReturn(tokens("APP", "GRANT"));
        when(agreements.productNumber("co-1")).thenReturn("1");
        when(agreements.layoutNumber("co-1")).thenReturn(22);
        when(agreements.paymentTermFor(any())).thenReturn(5);
        when(agreements.vatZoneFor(any(), any())).thenReturn(1);

        EconomicsDraftInvoice draft = new EconomicsDraftInvoice();
        draft.setDraftInvoiceNumber(4521);
        when(mapper.toDraft(any())).thenReturn(draft);
        when(mapper.toLines(any())).thenReturn(List.of(new EconomicsDraftLine()));
        CreatedResult createResult = new CreatedResult();
        createResult.setNumber(4521);
        when(draftApi.create(eq("APP"), eq("GRANT"), anyString(), any())).thenReturn(createResult);

        Invoice out = orchestrator.createDraft("i1");

        verify(draftApi).create(eq("APP"), eq("GRANT"), startsWith("draft-i1"), any());
        verify(draftApi).createLinesBulk(eq("APP"), eq("GRANT"), eq(4521), any(DraftInvoiceLineBatchRequest.class));
        assertEquals(InvoiceStatus.PENDING_REVIEW, out.getStatus());
        assertEquals(Integer.valueOf(4521), out.getEconomicsDraftNumber());
        verify(recalc).recalculateInvoiceItems(inv);
        verify(bonus).recalcForInvoice(inv);
        // workService.registerAsPaidout is NOT called on step 1
        verify(work, never()).registerAsPaidout(any());
    }

    // ── regression: each createDraft call uses a fresh Idempotency-Key ─────────
    // Without this, e-conomic replays the deleted draft's response after a
    // cancelFinalization, and createLinesBulk 404s on the recycled draft number.

    @Test
    void createDraft_uses_a_fresh_idempotency_key_per_call() {
        Invoice inv = draftInvoice("i1", "co-1");
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(billingResolver.resolve(inv)).thenReturn(new BillingContext(inv, contract(), billingClient()));
        when(agreements.tokens("co-1")).thenReturn(tokens("APP", "GRANT"));
        when(agreements.productNumber("co-1")).thenReturn("1");
        when(agreements.layoutNumber("co-1")).thenReturn(22);
        when(agreements.paymentTermFor(any())).thenReturn(5);
        when(agreements.vatZoneFor(any(), any())).thenReturn(1);

        EconomicsDraftInvoice draft = new EconomicsDraftInvoice();
        draft.setDraftInvoiceNumber(4521);
        when(mapper.toDraft(any())).thenReturn(draft);
        when(mapper.toLines(any())).thenReturn(List.of(new EconomicsDraftLine()));
        CreatedResult createResult = new CreatedResult();
        createResult.setNumber(4521);
        when(draftApi.create(eq("APP"), eq("GRANT"), anyString(), any())).thenReturn(createResult);

        orchestrator.createDraft("i1");
        // Simulate a cancelFinalization between the two attempts: revert the in-memory
        // invoice back to DRAFT so the second createDraft is allowed.
        inv.setStatus(InvoiceStatus.DRAFT);
        inv.setEconomicsDraftNumber(null);
        orchestrator.createDraft("i1");

        org.mockito.ArgumentCaptor<String> keyCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(draftApi, times(2)).create(eq("APP"), eq("GRANT"), keyCap.capture(), any());
        List<String> keys = keyCap.getAllValues();
        assertEquals(2, keys.size());
        assertNotEquals(keys.get(0), keys.get(1),
                "Two consecutive createDraft calls must use distinct Idempotency-Keys "
                + "so e-conomic does not replay a deleted draft's response.");
        keys.forEach(k -> assertTrue(k.startsWith("draft-i1-"),
                "Key should remain prefixed with 'draft-{invoiceUuid}-' for support traceability"));
    }

    // ── test 2: bookDraft happy path ──────────────────────────────────────────

    @Test
    void bookDraft_calls_post_booked_and_sets_CREATED_and_registers_paidout() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(tokens("APP", "GRANT"));
        // Q2C number 4521 resolves to draftInvoiceNumber 4525 (legacy/UI number).
        EconomicsDraftInvoice fetched = new EconomicsDraftInvoice();
        fetched.setDraftInvoiceNumber(4525);
        when(draftApi.getByNumber("APP", "GRANT", 4521)).thenReturn(fetched);
        EconomicsBookedInvoice booked = new EconomicsBookedInvoice();
        booked.setBookedInvoiceNumber(80123);
        booked.setDate(LocalDate.of(2026, 4, 15));
        when(bookApi.book(eq("APP"), eq("GRANT"), anyString(), any())).thenReturn(booked);

        Invoice out = orchestrator.bookDraft("i1", /*sendBy*/ null);

        verify(bookApi).book(eq("APP"), eq("GRANT"), startsWith("book-i1"),
                argThat(req -> req.getDraftInvoice().getDraftInvoiceNumber() == 4525
                        && req.getSendBy() == null));
        assertEquals(InvoiceStatus.CREATED, out.getStatus());
        assertEquals(Integer.valueOf(80123), out.getEconomicsBookedNumber());
        assertEquals(80123, out.getInvoicenumber());
        assertEquals(EconomicsInvoiceStatus.BOOKED, out.getEconomicsStatus());
        verify(work).registerAsPaidout(inv);
    }

    // ── test 2b: bookDraft translates Q2C number → legacy draftInvoiceNumber ──
    // Regression guard: the Q2C POST /invoices/drafts response (number) and the legacy
    // /invoices/booked draftInvoiceNumber are NOT the same integer for a given draft.
    // bookDraft() must resolve number → draftInvoiceNumber via GET /invoices/drafts/{n}
    // before calling the legacy endpoint, otherwise e-conomic replies
    // "DraftInvoice 'N' not found".

    @Test
    void bookDraft_resolves_q2c_number_to_legacy_draftInvoiceNumber_before_booking() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", /*q2cNumber*/ 1);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(tokens("APP", "GRANT"));
        EconomicsDraftInvoice fetched = new EconomicsDraftInvoice();
        fetched.setDraftInvoiceNumber(5); // legacy number differs from Q2C number
        when(draftApi.getByNumber("APP", "GRANT", 1)).thenReturn(fetched);
        EconomicsBookedInvoice booked = new EconomicsBookedInvoice();
        booked.setBookedInvoiceNumber(80123);
        booked.setDate(LocalDate.of(2026, 4, 15));
        when(bookApi.book(eq("APP"), eq("GRANT"), anyString(), any())).thenReturn(booked);

        orchestrator.bookDraft("i1", /*sendBy*/ null);

        verify(bookApi).book(eq("APP"), eq("GRANT"), startsWith("book-i1"),
                argThat(req -> req.getDraftInvoice().getDraftInvoiceNumber() == 5));
    }

    @Test
    void bookDraft_throws_BadRequest_when_q2c_returns_no_draftInvoiceNumber() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 1);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(tokens("APP", "GRANT"));
        when(draftApi.getByNumber("APP", "GRANT", 1)).thenReturn(null);

        jakarta.ws.rs.BadRequestException thrown = assertThrows(
                jakarta.ws.rs.BadRequestException.class,
                () -> orchestrator.bookDraft("i1", null));

        assertEquals(400, thrown.getResponse().getStatus());
        verifyNoInteractions(bookApi);
    }

    // ── test 3: cancelFinalization reverts to DRAFT ───────────────────────────

    @Test
    void cancelFinalization_deletes_draft_and_reverts_to_DRAFT() {
        Invoice inv = pendingReviewInvoice("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(agreements.tokens("co-1")).thenReturn(tokens("APP", "GRANT"));

        Invoice out = orchestrator.cancelFinalization("i1");

        verify(draftApi).delete("APP", "GRANT", 4521);
        assertEquals(InvoiceStatus.DRAFT, out.getStatus());
        assertNull(out.getEconomicsDraftNumber());
        verify(work, never()).registerAsPaidout(any());
    }

    // ── test 4: PHANTOM invoice rejected ────────────────────────────────────

    @Test
    void createDraft_for_PHANTOM_invoice_is_rejected() {
        Invoice inv = phantomInvoice("i1", "co-1");
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        jakarta.ws.rs.BadRequestException thrown = assertThrows(
                jakarta.ws.rs.BadRequestException.class,
                () -> orchestrator.createDraft("i1"));
        assertEquals(400, thrown.getResponse().getStatus());
        verifyNoInteractions(draftApi);
    }

    // ── test 5: unpaired billing client propagates exception ─────────────────

    @Test
    void createDraft_fails_loudly_when_customer_not_paired() {
        Invoice inv = draftInvoice("i1", "co-1");
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(inv));
        when(billingResolver.resolve(inv)).thenReturn(new BillingContext(inv, contract(), billingClient()));
        when(agreements.tokens("co-1")).thenReturn(tokens("APP", "GRANT"));
        when(agreements.productNumber("co-1")).thenReturn("1");
        when(agreements.layoutNumber("co-1")).thenReturn(22);
        when(agreements.paymentTermFor(any())).thenReturn(5);
        when(agreements.vatZoneFor(any(), any())).thenReturn(1);
        when(mapper.toDraft(any())).thenThrow(new IllegalStateException("Billing client not paired"));
        assertThrows(IllegalStateException.class, () -> orchestrator.createDraft("i1"));
    }

    // ── test 6: cancelFinalization restores bonus fields for credit note ──────

    @Test
    void cancelFinalization_reverts_bonus_fields_for_credit_note_parents() {
        Invoice cn = creditNotePendingReview("i1", "co-1", 4521);
        when(invoices.findByUuid("i1")).thenReturn(Optional.of(cn));
        when(agreements.tokens("co-1")).thenReturn(tokens("APP", "GRANT"));

        orchestrator.cancelFinalization("i1");

        verify(bonus).restoreParentBonusFields(cn);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Invoice draftInvoice(String uuid, String companyUuid) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INVOICE);
        inv.setStatus(InvoiceStatus.DRAFT);
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

    private Invoice pendingReviewInvoice(String uuid, String companyUuid, int draftNumber) {
        Invoice inv = draftInvoice(uuid, companyUuid);
        inv.setStatus(InvoiceStatus.PENDING_REVIEW);
        inv.setEconomicsDraftNumber(draftNumber);
        return inv;
    }

    private Invoice phantomInvoice(String uuid, String companyUuid) {
        Invoice inv = draftInvoice(uuid, companyUuid);
        inv.setType(InvoiceType.PHANTOM);
        return inv;
    }

    private Invoice creditNotePendingReview(String uuid, String companyUuid, int draftNumber) {
        Invoice inv = pendingReviewInvoice(uuid, companyUuid, draftNumber);
        inv.setType(InvoiceType.CREDIT_NOTE);
        inv.setCreditnoteForUuid("parent-invoice-uuid");
        return inv;
    }

    private Contract contract() {
        Contract c = new Contract();
        c.setUuid("contract-uuid");
        c.setClientuuid("client-uuid");
        return c;
    }

    private Client billingClient() {
        Client c = new Client();
        c.setUuid("client-uuid");
        c.setName("Acme A/S");
        return c;
    }

    private EconomicsAgreementResolver.Tokens tokens(String appSecret, String agreementGrant) {
        return new EconomicsAgreementResolver.Tokens(appSecret, agreementGrant);
    }
}
