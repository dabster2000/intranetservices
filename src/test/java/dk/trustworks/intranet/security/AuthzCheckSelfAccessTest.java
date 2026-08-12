package dk.trustworks.intranet.security;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of the second door onto the same decision: the BFF's
 * {@code POST /authz/check}, which {@code checkEmployeeDataAccess} calls before every
 * per-employee compensation route — {@code /users/{uuid}/salarylumpsums} and
 * {@code /users/{uuid}/salarysupplements} among them. Those two backend resources carry
 * no row-level check of their own, so this endpoint's verdict is the whole gate, and a
 * denial here surfaces to the user as the BFF's own 403 rather than as
 * SalaryResource's "outside your reach".
 *
 * <p>The wiring is the real {@link AuthorizationServiceImpl} over the production grant
 * state that caused the regression: {@code effectiveScopes} returns nothing, because a
 * plain employee holds no {@code roles} row for V470's
 * {@code USER → salaries:read @ OWN} grant to resolve from.
 */
class AuthzCheckSelfAccessTest {

    private static final String EMPLOYEE = "dae02077-5419-4a28-aacb-1f7d64e21f6b";
    private static final String COLLEAGUE = "6432b881-0af6-4704-96c9-5ab54b14930a";

    private AuthzAdminResource resource;
    private EffectivePermissionService permissions;
    private AccessScopeResolver scopeResolver;

    @BeforeEach
    void setUp() {
        permissions = mock(EffectivePermissionService.class);
        scopeResolver = mock(AccessScopeResolver.class);
        when(permissions.effectiveScopes(anyString())).thenReturn(Map.of()); // no grant resolves

        AuthorizationServiceImpl authorizationService = new AuthorizationServiceImpl();
        authorizationService.effectivePermissionService = permissions;
        authorizationService.accessScopeResolver = scopeResolver;

        RequestHeaderHolder headers = mock(RequestHeaderHolder.class);
        when(headers.getUserUuid()).thenReturn(EMPLOYEE); // X-Requested-By

        resource = new AuthzAdminResource();
        resource.authorizationService = authorizationService;
        resource.requestHeaderHolder = headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> check(String subject, Boolean allowSelf) {
        Response response = resource.check(new AuthzAdminResource.AccessCheckRequest(
                "salaries:read", subject, allowSelf, true));
        assertEquals(200, response.getStatus());
        return (Map<String, Object>) response.getEntity();
    }

    @Test
    void selfReadIsAllowedWithNoGrantAtAll() {
        Map<String, Object> decision = check(EMPLOYEE, true);

        assertTrue((Boolean) decision.get("allowed"),
                "an employee asking about themselves must be allowed, grant or no grant");
        assertEquals("OWN", decision.get("widestScope"));
        assertFalse((Boolean) decision.get("unbounded"), "self-access is the narrowest tier");
    }

    @Test
    void foreignReadIsStillDenied() {
        Map<String, Object> decision = check(COLLEAGUE, true);

        assertFalse((Boolean) decision.get("allowed"),
                "the self bypass must not reach a colleague's compensation data");
    }

    /**
     * {@code allowSelf: false} is how the mutation routes keep self-service read-only.
     * It maps to disabling the OWN tier, and must keep excluding the actor themselves.
     */
    @Test
    void allowSelfFalseStillDeniesTheActorTheirOwnRecord() {
        Map<String, Object> decision = check(EMPLOYEE, false);

        assertFalse((Boolean) decision.get("allowed"),
                "allowSelf:false must keep meaning allowSelf:false");
    }

    /** A resolvable grant is unaffected: HR/ADMIN keep salaries:read @ ALL. */
    @Test
    void unboundedGrantStillReachesEveryone() {
        when(permissions.effectiveScopes(EMPLOYEE))
                .thenReturn(Map.of("salaries:read", Set.of(DataScope.ALL)));

        Map<String, Object> decision = check(COLLEAGUE, true);

        assertTrue((Boolean) decision.get("allowed"));
        assertEquals("ALL", decision.get("widestScope"));
    }

    /** No {@code X-Requested-By}: not "self", and not a decision the endpoint will make. */
    @Test
    void missingActorHeaderIsABadRequestNotASelfMatch() {
        RequestHeaderHolder anonymous = mock(RequestHeaderHolder.class);
        when(anonymous.getUserUuid()).thenReturn(null);
        resource.requestHeaderHolder = anonymous;

        Response response = resource.check(new AuthzAdminResource.AccessCheckRequest(
                "salaries:read", EMPLOYEE, true, true));

        assertEquals(400, response.getStatus());
    }
}
