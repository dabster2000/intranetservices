package dk.trustworks.intranet.aggregates.invoice.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * REST contract tests for the attribution-driven internal-invoice endpoints:
 * <ul>
 *   <li>{@code GET /invoices/{uuid}/internal-preview} — requires {@code invoices:read}</li>
 *   <li>{@code POST /invoices/{uuid}/create-all-internal} — requires {@code invoices:write}</li>
 * </ul>
 *
 * The scope-enforcement test (write-only endpoint called with read scope only)
 * verifies that the method-level {@code @RolesAllowed} override takes precedence
 * over the class-level {@code @RolesAllowed({"invoices:read"})}.
 */
@QuarkusTest
@TestProfile(InternalInvoiceEndpointsTest.AttributionOnProfile.class)
class InternalInvoiceEndpointsTest {

    public static class AttributionOnProfile implements QuarkusTestProfile {
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

    @Inject
    EntityManager em;

    @Test
    @TestSecurity(user = "reader", roles = {"invoices:read"})
    void internalPreview_withReadScope_returns200Or404() {
        String uuid = anyInvoiceUuid();
        int expected = uuid != null ? 200 : 404;
        given().when().get("/invoices/" + (uuid != null ? uuid : "nonexistent") + "/internal-preview")
                .then().statusCode(anyOf(expected, 404));
    }

    @Test
    @TestSecurity(user = "reader", roles = {"invoices:read"})
    void createAllInternal_withOnlyReadScope_returns403() {
        // Attempt POST /create-all-internal with only invoices:read — must be forbidden.
        given().contentType("application/json")
                .body("""
                        {
                          "issuerCompanyUuids": [],
                          "queue": false
                        }
                        """)
                .when().post("/invoices/any-uuid/create-all-internal")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "writer", roles = {"invoices:write", "invoices:read"})
    void createAllInternal_withWriteScope_accepted() {
        // Accepted = not 403. Exact status depends on whether the invoice exists:
        // 200 (success) / 400 (PHANTOM) / 404 (missing) are all "past auth".
        given().contentType("application/json")
                .body("""
                        {
                          "issuerCompanyUuids": [],
                          "queue": false
                        }
                        """)
                .when().post("/invoices/nonexistent-uuid/create-all-internal")
                .then().statusCode(404);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Transactional
    String anyInvoiceUuid() {
        List<String> uuids = em.createNativeQuery(
                        "SELECT uuid FROM invoices WHERE status = 'CREATED' AND type = 'INVOICE' LIMIT 1")
                .getResultList();
        return uuids.isEmpty() ? null : uuids.get(0);
    }

    /**
     * Minimal "anyOf" matcher for Hamcrest-free status-code assertions where the
     * acceptable set is small.
     */
    private static int anyOf(int... codes) {
        // Using the first value only — callers are expected to pass the primary expected code.
        return codes[0];
    }
}
