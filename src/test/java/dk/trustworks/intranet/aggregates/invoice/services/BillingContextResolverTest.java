package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link BillingContextResolver}'s two-branch resolution logic.
 *
 * <p>SPEC: internal-invoice-billing-client-fix § FR-3, AC-12.
 */
@ExtendWith(MockitoExtension.class)
class BillingContextResolverTest {

    @InjectMocks BillingContextResolver resolver;

    @Mock ContractService contractService;
    @Mock ClientService   clientService;
    @Mock IntercompanyClientResolver intercompanyClientResolver;

    // ── branch 1: invoice-stamped billingClientUuid takes precedence ──────────

    @Test
    void resolve_whenInvoiceHasBillingClientUuid_returnsThatClient_neverConsultsContractClients() {
        Invoice inv = internalInvoice("inv-1", "contract-1", "stamped-client-uuid");
        Contract contract = contract("contract-1", "external-client-uuid", "external-billing-client-uuid");
        Client stamped = client("stamped-client-uuid", "Trustworks A/S");

        when(contractService.findByUuid("contract-1")).thenReturn(contract);
        when(clientService.findByUuid("stamped-client-uuid")).thenReturn(stamped);

        BillingContext result = resolver.resolve(inv);

        assertSame(stamped, result.billingClient(),
                "Resolver must return the Client pointed at by invoice.billingClientUuid");
        assertSame(inv, result.invoice());
        assertSame(contract, result.contract());
        // Contract's client UUIDs must NOT be looked up in this branch.
        verify(clientService, never()).findByUuid("external-client-uuid");
        verify(clientService, never()).findByUuid("external-billing-client-uuid");
    }

    @Test
    void resolve_whenStampedClientDoesNotExist_throwsBadRequest() {
        Invoice inv = internalInvoice("inv-1", "contract-1", "stale-uuid");
        Contract contract = contract("contract-1", "external-client-uuid", null);

        when(contractService.findByUuid("contract-1")).thenReturn(contract);
        when(clientService.findByUuid("stale-uuid")).thenReturn(null);

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> resolver.resolve(inv));
        assertTrue(thrown.getMessage().contains("stale-uuid"),
                "Error should mention the stale UUID, got: " + thrown.getMessage());
    }

    // ── branch 2: regression — null billingClientUuid falls back to contract ──

    @Test
    void resolve_whenInvoiceBillingClientUuidIsNull_usesContractBillingClient() {
        Invoice inv = regularInvoice("inv-1", "contract-1");
        Contract contract = contract("contract-1", "external-client-uuid", "contract-billing-uuid");
        Client contractBilling = client("contract-billing-uuid", "Client B");

        when(contractService.findByUuid("contract-1")).thenReturn(contract);
        when(clientService.findByUuid("contract-billing-uuid")).thenReturn(contractBilling);

        BillingContext result = resolver.resolve(inv);

        assertSame(contractBilling, result.billingClient());
    }

    @Test
    void resolve_whenInvoiceAndContractBillingAreNull_fallsBackToContractClientuuid() {
        Invoice inv = regularInvoice("inv-1", "contract-1");
        Contract contract = contract("contract-1", "external-client-uuid", null);
        Client external = client("external-client-uuid", "Client A");

        when(contractService.findByUuid("contract-1")).thenReturn(contract);
        when(clientService.findByUuid("external-client-uuid")).thenReturn(external);

        BillingContext result = resolver.resolve(inv);

        assertSame(external, result.billingClient());
    }

    @Test
    void resolve_whenInvoiceBillingClientUuidIsBlank_fallsBackToContractPath() {
        Invoice inv = regularInvoice("inv-1", "contract-1");
        inv.setBillingClientUuid("   ");
        Contract contract = contract("contract-1", "external-client-uuid", null);
        Client external = client("external-client-uuid", "Client A");

        when(contractService.findByUuid("contract-1")).thenReturn(contract);
        when(clientService.findByUuid("external-client-uuid")).thenReturn(external);

        BillingContext result = resolver.resolve(inv);

        assertSame(external, result.billingClient());
    }

    @Test
    void resolve_whenContractMissing_throwsBadRequest() {
        Invoice inv = regularInvoice("inv-1", "contract-1");
        when(contractService.findByUuid("contract-1")).thenReturn(null);

        assertThrows(BadRequestException.class, () -> resolver.resolve(inv));
    }

    @Test
    void resolve_whenContractReferenceIsNull_throwsBadRequest_withoutLookup() {
        // Settlement internals from phantom representatives must have the contract stamped at
        // creation; a null reference must 400 cleanly, never reach Panache findById(null) (500).
        Invoice inv = regularInvoice("inv-1", null);

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> resolver.resolve(inv));
        assertTrue(thrown.getMessage().contains("no contract reference"),
                "Error should explain the missing contract, got: " + thrown.getMessage());
        verify(contractService, never()).findByUuid(any());
    }

    @Test
    void resolve_whenContractReferenceIsBlank_throwsBadRequest_withoutLookup() {
        Invoice inv = regularInvoice("inv-1", "   ");

        assertThrows(BadRequestException.class, () -> resolver.resolve(inv));
        verify(contractService, never()).findByUuid(any());
    }

    // ── branch 1b: INTERNAL invoice with no stamp resolves the intercompany client ──
    // Regression guard for the legacy-QUEUED bug: INTERNAL / INTERNAL_SERVICE invoices
    // created before billing_client_uuid stamping have a null stamp. They must resolve to
    // the intercompany client for the debtor company (by CVR) — NEVER fall through to the
    // contract's external client, which is the wrong entity and is usually unpaired in the
    // issuer's e-conomic (surfaces as "Client X is not paired …" at force-create time).

    @Test
    void resolve_internalInvoiceWithNullStamp_resolvesIntercompanyClientByDebtor_notContractExternalClient() {
        Invoice inv = internalInvoice("inv-1", "contract-1", null);
        inv.setDebtorCompanyuuid("debtor-company-uuid");
        Contract contract = contract("contract-1", "external-client-uuid", "external-billing-client-uuid");
        Client intercompany = client("intercompany-client-uuid", "TRUSTWORKS A/S");

        when(contractService.findByUuid("contract-1")).thenReturn(contract);
        when(intercompanyClientResolver.resolveByDebtorCompanyUuid("debtor-company-uuid"))
                .thenReturn(Optional.of(intercompany));

        BillingContext result = resolver.resolve(inv);

        assertSame(intercompany, result.billingClient(),
                "INTERNAL invoice with null stamp must bill the intercompany (debtor-CVR) client, "
                + "never the contract's external client");
        assertSame(contract, result.contract());
        verify(clientService, never()).findByUuid("external-client-uuid");
        verify(clientService, never()).findByUuid("external-billing-client-uuid");
    }

    @Test
    void resolve_internalServiceInvoiceWithNullStamp_alsoResolvesIntercompanyClient() {
        Invoice inv = internalInvoice("inv-1", "contract-1", null);
        inv.setType(InvoiceType.INTERNAL_SERVICE);
        inv.setDebtorCompanyuuid("debtor-company-uuid");
        Contract contract = contract("contract-1", "external-client-uuid", null);
        Client intercompany = client("intercompany-client-uuid", "TRUSTWORKS A/S");

        when(contractService.findByUuid("contract-1")).thenReturn(contract);
        when(intercompanyClientResolver.resolveByDebtorCompanyUuid("debtor-company-uuid"))
                .thenReturn(Optional.of(intercompany));

        BillingContext result = resolver.resolve(inv);

        assertSame(intercompany, result.billingClient());
    }

    @Test
    void resolve_internalInvoiceWithNullStamp_whenNoIntercompanyClientMatches_throwsBadRequest_withoutContractClientLookup() {
        Invoice inv = internalInvoice("inv-1", "contract-1", null);
        inv.setDebtorCompanyuuid("debtor-company-uuid");
        Contract contract = contract("contract-1", "external-client-uuid", null);

        when(contractService.findByUuid("contract-1")).thenReturn(contract);
        when(intercompanyClientResolver.resolveByDebtorCompanyUuid("debtor-company-uuid"))
                .thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> resolver.resolve(inv));
        verify(clientService, never()).findByUuid(any());
    }

    // ── branch 0: intercompany invoices without a contract reference ──────────
    // INTERNAL_SERVICE settlement drafts (distribution page) bill no contract work and are
    // created with a blank contractuuid. They must resolve with a null contract instead of
    // 400'ing — the billing entity comes from the stamp / debtor CVR, payment terms use
    // immediatePaymentTermFor, and every BillingContext.contract() consumer null-guards.

    @Test
    void resolve_internalServiceWithBlankContract_andStamp_resolvesWithNullContract() {
        Invoice inv = internalInvoice("inv-1", "", "stamped-client-uuid");
        inv.setType(InvoiceType.INTERNAL_SERVICE);
        Client stamped = client("stamped-client-uuid", "TRUSTWORKS A/S");

        when(clientService.findByUuid("stamped-client-uuid")).thenReturn(stamped);

        BillingContext result = resolver.resolve(inv);

        assertSame(stamped, result.billingClient());
        assertNull(result.contract(), "Settlement drafts carry no contract — context must hold null");
        verify(contractService, never()).findByUuid(any());
    }

    @Test
    void resolve_internalServiceWithBlankContract_andNullStamp_resolvesIntercompanyClient() {
        Invoice inv = internalInvoice("inv-1", "", null);
        inv.setType(InvoiceType.INTERNAL_SERVICE);
        inv.setDebtorCompanyuuid("debtor-company-uuid");
        Client intercompany = client("intercompany-client-uuid", "TRUSTWORKS A/S");

        when(intercompanyClientResolver.resolveByDebtorCompanyUuid("debtor-company-uuid"))
                .thenReturn(Optional.of(intercompany));

        BillingContext result = resolver.resolve(inv);

        assertSame(intercompany, result.billingClient());
        assertNull(result.contract());
        verify(contractService, never()).findByUuid(any());
    }

    @Test
    void resolve_internalWithBlankContract_andStamp_resolvesWithNullContract() {
        Invoice inv = internalInvoice("inv-1", null, "stamped-client-uuid");
        Client stamped = client("stamped-client-uuid", "TRUSTWORKS A/S");

        when(clientService.findByUuid("stamped-client-uuid")).thenReturn(stamped);

        BillingContext result = resolver.resolve(inv);

        assertSame(stamped, result.billingClient());
        assertNull(result.contract());
    }

    @Test
    void resolve_internalCreditNoteWithBlankContract_andStamp_resolvesWithNullContract() {
        Invoice inv = internalInvoice("inv-1", "", "stamped-client-uuid");
        inv.setType(InvoiceType.CREDIT_NOTE);
        inv.setDebtorCompanyuuid("debtor-company-uuid"); // makes isInternalCreditNote() true
        Client stamped = client("stamped-client-uuid", "TRUSTWORKS A/S");

        when(clientService.findByUuid("stamped-client-uuid")).thenReturn(stamped);

        BillingContext result = resolver.resolve(inv);

        assertSame(stamped, result.billingClient());
        assertNull(result.contract());
    }

    @Test
    void resolve_internalCreditNoteWithBlankContract_andNoStamp_throwsBadRequest_neverNpes() {
        // CREDIT_NOTE does not match the INTERNAL/INTERNAL_SERVICE debtor-CVR branch; with no
        // stamp and no contract the resolver must fail actionably instead of NPE'ing on the
        // contract-based fallback.
        Invoice inv = internalInvoice("inv-1", "", null);
        inv.setType(InvoiceType.CREDIT_NOTE);
        inv.setDebtorCompanyuuid("debtor-company-uuid");

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> resolver.resolve(inv));
        assertTrue(thrown.getMessage().contains("no billing entity"),
                "Error should explain the unresolvable billing entity, got: " + thrown.getMessage());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Invoice regularInvoice(String uuid, String contractUuid) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INVOICE);
        inv.setContractuuid(contractUuid);
        inv.setBillingClientUuid(null);
        return inv;
    }

    private Invoice internalInvoice(String uuid, String contractUuid, String billingClientUuid) {
        Invoice inv = new Invoice();
        inv.setUuid(uuid);
        inv.setType(InvoiceType.INTERNAL);
        inv.setContractuuid(contractUuid);
        inv.setBillingClientUuid(billingClientUuid);
        return inv;
    }

    private Contract contract(String uuid, String clientUuid, String billingClientUuid) {
        Contract c = new Contract();
        c.setUuid(uuid);
        c.setClientuuid(clientUuid);
        c.setBillingClientUuid(billingClientUuid);
        c.setName("Test Contract");
        return c;
    }

    private Client client(String uuid, String name) {
        Client c = new Client();
        c.setUuid(uuid);
        c.setName(name);
        return c;
    }
}
