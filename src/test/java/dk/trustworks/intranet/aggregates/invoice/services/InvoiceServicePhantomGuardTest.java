package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.InternalInvoicePreview;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration smoke test for the Phase 5 guard lift: a {@code PHANTOM} source invoice must
 * no longer return {@code 400} from the internal-preview / create-all-internal flow.
 *
 * <p>This is a {@link QuarkusTest}, so it boots against the configured MariaDB and only
 * exercises real behavior when a {@code CREATED} phantom exists in that database. When none
 * is present (e.g. no local DB) the tests are {@code assumeTrue}-skipped — never silently
 * reported as a green PASS. The real end-to-end gate is the staging probe in the plan's
 * Task 4 (call {@code internal-preview} on a real phantom and confirm {@code 200}, and for a
 * mapped phantom that the FIRST call already returns non-empty issuers — proving the
 * REQUIRES_NEW re-read fix).
 */
@QuarkusTest
@TestProfile(InvoiceServicePhantomGuardTest.BootProfile.class)
class InvoiceServicePhantomGuardTest {

    /**
     * Minimal overrides so the CDI context boots in tests (S3 dev services off, placeholder
     * cvtool credentials). {@code feature.invoicing.internal.attribution-driven=true} is set so
     * {@code autoCreateAndQueueInternal} takes the attribution-driven branch — the one whose
     * re-asserted PHANTOM guard this test exercises. The {@code internal-preview} /
     * {@code create-all-internal} methods do not read that flag; it is only relevant to the
     * legacy auto-create path.
     */
    public static class BootProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder",
                    "feature.invoicing.internal.attribution-driven", "true"
            );
        }
    }

    @Inject InvoiceService invoiceService;
    @Inject EntityManager em;

    private String findCreatedPhantomUuid() {
        @SuppressWarnings("unchecked")
        List<String> uuids = em.createNativeQuery(
                "SELECT uuid FROM invoices WHERE type = 'PHANTOM' AND status = 'CREATED' LIMIT 1")
                .getResultList();
        return uuids.isEmpty() ? null : uuids.get(0);
    }

    @Test
    void previewInternal_onPhantom_doesNotThrow400() {
        String uuid = findCreatedPhantomUuid();
        assumeTrue(uuid != null, "no CREATED phantom in this DB — skipping (exercise via the staging probe)");

        InternalInvoicePreview preview =
                assertDoesNotThrow(() -> invoiceService.previewInternal(uuid, Set.of()));
        assertNotNull(preview);
        assertEquals(uuid, preview.sourceInvoiceUuid());
        // issuers is present but may be empty (unmapped / no-work / excluded phantom). When it
        // is non-empty, this single call also demonstrates that freshly-derived attribution is
        // visible on the FIRST invocation (the REQUIRES_NEW re-read fix).
        assertNotNull(preview.issuers(), "issuers list present (possibly empty)");
    }

    @Test
    void createAll_onPhantomWithEmptyPreview_createsNothing_andDoesNotThrow() {
        String uuid = findCreatedPhantomUuid();
        assumeTrue(uuid != null, "no CREATED phantom in this DB — skipping (exercise via the staging probe)");

        // NOTE: previewInternal may itself idempotently derive + commit AUTO attribution rows
        // (and stamp billing_client_uuid) for a phantom that resolves to a client with work —
        // this mirrors the non-phantom computeAttributions lazy-compute and is intended, not a
        // leak. We only proceed to createAll when the preview yields NO issuers, so the create
        // path materializes nothing in the shared DB.
        InternalInvoicePreview preview = invoiceService.previewInternal(uuid, Set.of());
        if (!preview.issuers().isEmpty()) {
            // Phantom resolves to issuers — skip to avoid creating real internal invoices.
            return;
        }
        List<String> created =
                assertDoesNotThrow(() -> invoiceService.createAllInternalFromAttribution(uuid, null, false, Set.of()));
        assertTrue(created.isEmpty(), "no issuers -> no internal invoices created");
    }

    @Test
    void autoCreateAndQueueInternal_onPhantom_throws400() {
        String uuid = findCreatedPhantomUuid();
        assumeTrue(uuid != null, "no CREATED phantom in this DB — skipping (exercise via the staging probe)");

        // The legacy auto-create endpoint must keep REJECTING PHANTOM (Phase 5 re-asserted the
        // guard in its attribution-driven branch, since the shared createAllInternalFromAttribution
        // no longer 400s on phantoms). The guard throws right after loading the phantom, before any
        // derive/create — so this asserts the 400 with no side effects on the shared DB.
        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> invoiceService.autoCreateAndQueueInternal(uuid, "any-issuer-company-uuid"));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getResponse().getStatus(),
                "legacy autoCreateAndQueueInternal must reject a PHANTOM source with 400");
    }
}
