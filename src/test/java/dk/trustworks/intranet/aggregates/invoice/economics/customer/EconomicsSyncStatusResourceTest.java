package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reflection-based contract tests for {@link EconomicsSyncStatusResource}.
 * Verifies the path, scope, and query parameter without booting Quarkus
 * (local dev env lacks cvtool.* secrets). Runtime behaviour is covered in
 * {@link EconomicsCustomerSyncServiceStatusForTest}.
 *
 * SPEC-INV-001 §7.1 Phase G2, §8.6.
 */
class EconomicsSyncStatusResourceTest {

    @Test
    void resource_is_mounted_at_economics_sync_status() {
        Path path = EconomicsSyncStatusResource.class.getAnnotation(Path.class);
        assertNotNull(path, "EconomicsSyncStatusResource must be @Path-annotated");
        assertEquals("/economics/sync-status", path.value());
    }

    @Test
    void status_endpoint_uses_invoices_scopes_not_accountants() {
        Method status = findMethod("status");
        assertNotNull(status, "EconomicsSyncStatusResource must expose a status() method");

        RolesAllowed roles = status.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, "status() must be @RolesAllowed — never expose an unguarded endpoint");

        List<String> declared = Arrays.asList(roles.value());
        assertTrue(declared.contains("invoices:read"),
                "status() must allow invoices:read — matches EconomicsCustomerPairingResource");
        assertTrue(declared.contains("invoices:write"),
                "status() must also allow invoices:write");
        for (String scope : declared) {
            assertTrue(scope.startsWith("invoices:"),
                    "Do NOT reference accountants:* scopes — they are not in AdminScopeAugmentor.ALL_SCOPES " +
                    "and would make admin JWTs fail authorization. Scope: " + scope);
        }
    }

    @Test
    void status_endpoint_is_GET() {
        Method status = findMethod("status");
        assertNotNull(status.getAnnotation(GET.class),
                "status() must be a GET (read-only lookup)");
    }

    @Test
    void status_endpoint_requires_clientUuid_query_param() {
        Method status = findMethod("status");
        Parameter clientUuid = findParameter(status, "clientUuid");
        assertNotNull(clientUuid, "status() must accept a clientUuid query parameter");
        assertEquals(String.class, clientUuid.getType(),
                "clientUuid must be typed as String to tolerate both UUID and non-UUID identifiers");
    }

    private static Method findMethod(String name) {
        for (Method m : EconomicsSyncStatusResource.class.getDeclaredMethods()) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        return null;
    }

    private static Parameter findParameter(Method method, String queryParamName) {
        for (Parameter p : method.getParameters()) {
            QueryParam qp = p.getAnnotation(QueryParam.class);
            if (qp != null && queryParamName.equals(qp.value())) {
                return p;
            }
        }
        return null;
    }
}
