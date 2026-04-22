package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.CreatedResult;
import dk.trustworks.intranet.aggregates.invoice.economics.DraftContext;
import dk.trustworks.intranet.aggregates.invoice.economics.InvoiceToEconomicsDraftMapper;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsBookingApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftLine;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link InvoiceFinalizationOrchestrator} INTERNAL-specific behavior.
 *
 * <p>SPEC: internal-invoice-billing-client-fix § FR-3 + FR-6, AC-9, AC-10, AC-14.
 *
 * <p>The null-guard at createDraft line ~192 must not overwrite a pre-stamped
 * {@code billingClientUuid}, so the {@link InvoiceToEconomicsDraftMapper}
 * receives a {@link DraftContext} carrying the intercompany Client — not the
 * contract's external client.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceFinalizationOrchestratorInternalTest {

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
    @Mock EanPrerequisiteChecker            eanChecker;

    // ── AC-14: INTERNAL finalize → mapper receives intercompany billingClient ─

    @Test
    void createDraft_internalInvoice_passesStampedBillingClientToMapper() {
        // Given: an INTERNAL invoice stamped at creation with the intercompany Client UUID.
        Invoice inv = internalInvoice("inv-1", "issuer-co", "intercompany-client-uuid");
        Contract contract = contract("contract-1", "external-client-uuid");
        Client intercompanyClient = client("intercompany-client-uuid", "Trustworks A/S");

        // The billing resolver returns the INTERCOMPANY Client (FR-3 branch 1).
        when(invoices.findByUuid("inv-1")).thenReturn(Optional.of(inv));
        when(billingResolver.resolve(inv)).thenReturn(new BillingContext(inv, contract, intercompanyClient));
        when(agreements.tokens("issuer-co")).thenReturn(new EconomicsAgreementResolver.Tokens("APP", "GRANT"));
        when(agreements.layoutNumber("issuer-co")).thenReturn(22);
        when(agreements.paymentTermFor(any())).thenReturn(5);
        when(agreements.vatZoneFor(any(), any())).thenReturn(1);
        when(agreements.productNumber("issuer-co")).thenReturn("1");

        EconomicsDraftInvoice draft = new EconomicsDraftInvoice();
        draft.setDraftInvoiceNumber(4521);
        when(mapper.toDraft(any())).thenReturn(draft);
        when(mapper.toLines(any())).thenReturn(List.of(new EconomicsDraftLine()));
        CreatedResult createResult = new CreatedResult();
        createResult.setNumber(4521);
        when(draftApi.create(any(), any(), anyString(), any())).thenReturn(createResult);

        // When: createDraft runs
        Invoice out = orchestrator.createDraft("inv-1");

        // Then: the DraftContext passed to the mapper carries the INTERCOMPANY client.
        ArgumentCaptor<DraftContext> ctxCap = ArgumentCaptor.forClass(DraftContext.class);
        verify(mapper).toDraft(ctxCap.capture());
        assertSame(intercompanyClient, ctxCap.getValue().billingClient(),
                "Mapper must receive the intercompany Client (not the contract's external client)");

        // AC-9: the null-guard did NOT overwrite the pre-stamped billingClientUuid.
        assertEquals("intercompany-client-uuid", out.getBillingClientUuid(),
                "Pre-stamped billingClientUuid must be preserved — not overwritten with contract client");
        assertEquals(InvoiceStatus.PENDING_REVIEW, out.getStatus());
    }

    // ── AC-9: regular INVOICE → createDraft stamps billingClientUuid for first time ─

    @Test
    void createDraft_regularInvoice_whenBillingClientUuidIsNull_stampsItFromBillingContext() {
        Invoice inv = regularInvoice("inv-2", "issuer-co");
        assertNull(inv.getBillingClientUuid(), "Precondition: regular INVOICE starts without a stamp");
        Contract contract = contract("contract-2", "external-client-uuid");
        Client contractBillingClient = client("external-client-uuid", "Acme A/S");

        when(invoices.findByUuid("inv-2")).thenReturn(Optional.of(inv));
        when(billingResolver.resolve(inv)).thenReturn(new BillingContext(inv, contract, contractBillingClient));
        when(agreements.tokens("issuer-co")).thenReturn(new EconomicsAgreementResolver.Tokens("APP", "GRANT"));
        when(agreements.layoutNumber("issuer-co")).thenReturn(22);
        when(agreements.paymentTermFor(any())).thenReturn(5);
        when(agreements.vatZoneFor(any(), any())).thenReturn(1);
        when(agreements.productNumber("issuer-co")).thenReturn("1");
        when(mapper.toDraft(any())).thenReturn(new EconomicsDraftInvoice());
        when(mapper.toLines(any())).thenReturn(List.of(new EconomicsDraftLine()));
        CreatedResult createResult = new CreatedResult();
        createResult.setNumber(4521);
        when(draftApi.create(any(), any(), anyString(), any())).thenReturn(createResult);

        Invoice out = orchestrator.createDraft("inv-2");

        assertEquals("external-client-uuid", out.getBillingClientUuid(),
                "Regular INVOICE must get stamped at createDraft (existing behavior)");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Invoice regularInvoice(String uuid, String companyUuid) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INVOICE);
        inv.setStatus(InvoiceStatus.DRAFT);
        inv.setBillingClientUuid(null);
        Company company = new Company();
        company.setUuid(companyUuid);
        inv.setCompany(company);
        inv.setContractuuid("contract-2");
        inv.setInvoiceitems(List.of());
        return inv;
    }

    private Invoice internalInvoice(String uuid, String companyUuid, String stampedBillingClientUuid) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INTERNAL);
        inv.setStatus(InvoiceStatus.DRAFT);
        inv.setBillingClientUuid(stampedBillingClientUuid);
        Company company = new Company();
        company.setUuid(companyUuid);
        inv.setCompany(company);
        inv.setContractuuid("contract-1");
        inv.setInvoiceitems(List.of());
        return inv;
    }

    private Contract contract(String uuid, String clientUuid) {
        Contract c = new Contract();
        c.setUuid(uuid);
        c.setClientuuid(clientUuid);
        return c;
    }

    private Client client(String uuid, String name) {
        Client c = new Client();
        c.setUuid(uuid);
        c.setName(name);
        return c;
    }
}
