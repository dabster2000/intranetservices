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
