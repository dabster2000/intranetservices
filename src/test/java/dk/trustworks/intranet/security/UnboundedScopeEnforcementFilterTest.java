package dk.trustworks.intranet.security;

import dk.trustworks.intranet.aggregates.invoice.resources.InvoiceResource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of the Phase 9 unbounded-only enforcement
 * ({@link ScopeEnforced} + {@link UnboundedScopeEnforcementFilter}): surfaces
 * with no per-person subject dimension refuse an acting human whose grant of
 * the gating permission is bounded — and pass machine callers untouched
 * (Phase 12 territory, same deliberate fail-open as Phase 8's salary reads).
 */
class UnboundedScopeEnforcementFilterTest {

    private static final String ACTOR = "aaaaaaaa-0000-0000-0000-000000000001";

    private UnboundedScopeEnforcementFilter filter;
    private AuthorizationService authorizationService;
    private RequestHeaderHolder headers;

    @RolesAllowed({"invoices:read"})
    static class ClassGatedResource {
        public void read() {}

        @RolesAllowed({"invoices:write"})
        public void write() {}

        @RolesAllowed({"expenses:read", "expenses:review"})
        public void anyOf() {}

        @RolesAllowed({"SYSTEM"})
        public void nonScopeGate() {}
    }

    static class UngatedResource {
        public void read() {}
    }

    @BeforeEach
    void setUp() {
        filter = new UnboundedScopeEnforcementFilter();
        authorizationService = mock(AuthorizationService.class);
        headers = mock(RequestHeaderHolder.class);
        filter.authorizationService = authorizationService;
        filter.requestHeaderHolder = headers;
    }

    private void matched(Class<?> clazz, String methodName) throws Exception {
        Method method = clazz.getMethod(methodName);
        filter.resourceInfo = new ResourceInfo() {
            @Override public Method getResourceMethod() { return method; }
            @Override public Class<?> getResourceClass() { return clazz; }
        };
    }

    @Test
    void headerlessCallerPassesUntouched() throws Exception {
        when(headers.getUserUuid()).thenReturn(null);
        matched(ClassGatedResource.class, "read");

        assertDoesNotThrow(() -> filter.filter(null));
        verifyNoInteractions(authorizationService);
    }

    @Test
    void unboundedActorPassesOnTheClassGate() throws Exception {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.resolveReach(eq(ACTOR), eq("invoices:read"), any(), anySet()))
                .thenReturn(ScopeResolution.unboundedAll());
        matched(ClassGatedResource.class, "read");

        assertDoesNotThrow(() -> filter.filter(null));
    }

    @Test
    void boundedActorIs403() throws Exception {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.resolveReach(eq(ACTOR), eq("invoices:read"), any(), anySet()))
                .thenReturn(ScopeResolution.bounded(DataScope.TEAM, Set.of(ACTOR)));
        matched(ClassGatedResource.class, "read");

        assertThrows(ForbiddenException.class, () -> filter.filter(null));
    }

    @Test
    void actorWithNoGrantIs403() throws Exception {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.resolveReach(eq(ACTOR), eq("invoices:read"), any(), anySet()))
                .thenReturn(ScopeResolution.none());
        matched(ClassGatedResource.class, "read");

        assertThrows(ForbiddenException.class, () -> filter.filter(null));
    }

    @Test
    void methodGateOverridesTheClassGate() throws Exception {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.resolveReach(eq(ACTOR), eq("invoices:write"), any(), anySet()))
                .thenReturn(ScopeResolution.unboundedAll());
        matched(ClassGatedResource.class, "write");

        // invoices:read is never consulted — the method's own gate decides.
        assertDoesNotThrow(() -> filter.filter(null));
    }

    @Test
    void anyOfSemanticsOneUnboundedAmongTheGateValuesAdmits() throws Exception {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.resolveReach(eq(ACTOR), eq("expenses:read"), any(), anySet()))
                .thenReturn(ScopeResolution.bounded(DataScope.OWN, Set.of(ACTOR)));
        when(authorizationService.resolveReach(eq(ACTOR), eq("expenses:review"), any(), anySet()))
                .thenReturn(ScopeResolution.unboundedAll());
        matched(ClassGatedResource.class, "anyOf");

        assertDoesNotThrow(() -> filter.filter(null));
    }

    @Test
    void nonScopeShapedGateValuesAreIgnoredAndDeny() throws Exception {
        // A legacy role-name gate offers no permission to resolve — fail closed.
        when(headers.getUserUuid()).thenReturn(ACTOR);
        matched(ClassGatedResource.class, "nonScopeGate");

        assertThrows(ForbiddenException.class, () -> filter.filter(null));
        verifyNoInteractions(authorizationService);
    }

    @Test
    void annotatedSurfaceWithoutAnyGateFailsClosed() throws Exception {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        matched(UngatedResource.class, "read");

        assertThrows(ForbiddenException.class, () -> filter.filter(null));
    }

    // ------------------------------------------------------------------
    // Placement pins: the annotation cannot be dropped silently
    // ------------------------------------------------------------------

    @Test
    void invoiceResourceIsScopeEnforced() {
        assertTrue(InvoiceResource.class.isAnnotationPresent(ScopeEnforced.class),
                "Phase 9.1: InvoiceResource must stay @ScopeEnforced — removing it lets a "
                        + "console scope-edit silently serve company-wide invoice rows");
        RolesAllowed gate = InvoiceResource.class.getAnnotation(RolesAllowed.class);
        assertNotNull(gate, "the filter derives its permission from @RolesAllowed");
        assertTrue(Set.of(gate.value()).contains("invoices:read"));
    }
}
