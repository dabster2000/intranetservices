package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure (no-DB, no-CDI) unit test that the legacy {@code createInternalInvoiceDraft} entry
 * point still rejects PHANTOM sources after Phase 5 lifted the PHANTOM guard from the shared
 * {@code createAllInternalFromAttribution}. Phantom internal invoicing must go through the
 * dedicated internal-invoice preview / create-all flow, not this legacy endpoint.
 *
 * <p>Constructs {@link InvoiceService} directly and sets the (package-private) feature flag to
 * the production default so the attribution-driven branch — the one that previously 400'd on
 * PHANTOM via the now-lifted guard — is taken. The re-asserted guard throws before any injected
 * collaborator or DB access, so no Quarkus context / database is required: this is a deterministic
 * gate that runs under {@code ./mvnw test} everywhere (unlike the DB-backed
 * {@link InvoiceServicePhantomGuardTest}). A regression that drops the guard would fail here.
 */
class InvoiceServiceLegacyPhantomGuardTest {

    private InvoiceService attributionDrivenService() {
        InvoiceService service = new InvoiceService();
        service.attributionDrivenInternalInvoices = true; // production default; selects the guarded branch
        return service;
    }

    @Test
    void createInternalInvoiceDraft_onPhantom_throws400() {
        InvoiceService service = attributionDrivenService();
        Invoice phantom = new Invoice();
        phantom.type = InvoiceType.PHANTOM;

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> service.createInternalInvoiceDraft("any-company-uuid", phantom));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getResponse().getStatus(),
                "legacy createInternalInvoiceDraft must reject a PHANTOM source with 400");
    }
}
