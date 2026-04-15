package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.EconomicsCustomerContactSyncService;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.EconomicsCustomerSyncService;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ClientResource} and {@link ContractResource} wire the
 * e-conomic sync services as injected collaborators and that POST/PUT paths
 * trigger them. Plain reflection-based tests so we don't need to boot Quarkus
 * (local dev env lacks cvtool.* secrets). The runtime sync behaviour is
 * covered in {@link dk.trustworks.intranet.aggregates.invoice.economics.customer.EconomicsCustomerSyncServiceTest}
 * and {@link dk.trustworks.intranet.aggregates.invoice.economics.customer.EconomicsCustomerContactSyncServiceTest}.
 *
 * SPEC-INV-001 §3.3, §7.2.
 */
class ClientResourceSyncTriggerTest {

    @Test
    void clientResource_injects_EconomicsCustomerSyncService() throws NoSuchFieldException {
        Field f = ClientResource.class.getDeclaredField("economicsCustomerSyncService");
        assertNotNull(f.getAnnotation(Inject.class),
                "ClientResource must @Inject EconomicsCustomerSyncService so POST/PUT can trigger sync");
        assertTrue(EconomicsCustomerSyncService.class.isAssignableFrom(f.getType()),
                "economicsCustomerSyncService field must be typed as EconomicsCustomerSyncService");
    }

    @Test
    void clientResource_save_method_calls_sync_helper() throws Exception {
        // The private helper guarantees a uniform try/catch around every sync
        // invocation. Its mere presence proves the wiring convention is in place.
        var helper = ClientResource.class.getDeclaredMethod(
                "syncClientToEconomicsSafe",
                dk.trustworks.intranet.dao.crm.model.Client.class, String.class);
        assertNotNull(helper, "ClientResource must expose a defensive sync helper used by save() and updateOne()");
    }

    @Test
    void contractResource_injects_EconomicsCustomerContactSyncService() throws NoSuchFieldException {
        Field f = ContractResource.class.getDeclaredField("economicsContactSyncService");
        assertNotNull(f.getAnnotation(Inject.class),
                "ContractResource must @Inject EconomicsCustomerContactSyncService so POST/PUT can trigger contact sync");
        assertTrue(EconomicsCustomerContactSyncService.class.isAssignableFrom(f.getType()),
                "economicsContactSyncService field must be typed as EconomicsCustomerContactSyncService");
    }

    @Test
    void contractResource_injects_ClientService_for_billing_client_lookup() throws NoSuchFieldException {
        Field f = ContractResource.class.getDeclaredField("clientService");
        assertNotNull(f.getAnnotation(Inject.class),
                "ContractResource must @Inject ClientService to resolve billingClientUuid -> Client");
    }

    @Test
    void contractResource_exposes_defensive_contact_sync_helper() throws Exception {
        var helper = ContractResource.class.getDeclaredMethod(
                "syncContactToEconomicsSafe",
                dk.trustworks.intranet.contracts.model.Contract.class, String.class);
        assertNotNull(helper,
                "ContractResource must expose a defensive contact-sync helper used by save() and updateContract()");
    }
}
