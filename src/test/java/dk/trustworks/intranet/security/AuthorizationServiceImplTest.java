package dk.trustworks.intranet.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of the Phase 8 authorization seam (task 8.4): union
 * across roles' scoped grants, the ALL sentinel, tier exclusions (the retired
 * BFF guard's allowSelf/allowTeamLead options), and fail-closed on every error
 * path (plan P8 — a failure must never widen reach).
 */
class AuthorizationServiceImplTest {

    private static final String ANNA = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String BO = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 6);
    private static final String SALARIES_READ = "salaries:read";

    private EffectivePermissionService permissions;
    private AccessScopeResolver resolver;
    private AuthorizationServiceImpl service;

    @BeforeEach
    void setUp() {
        permissions = mock(EffectivePermissionService.class);
        resolver = mock(AccessScopeResolver.class);
        service = new AuthorizationServiceImpl();
        service.effectivePermissionService = permissions;
        service.accessScopeResolver = resolver;
    }

    private void grant(DataScope... scopes) {
        when(permissions.effectiveScopes(ANNA))
                .thenReturn(Map.of(SALARIES_READ, Set.of(scopes)));
    }

    // ------------------------------------------------------------------
    // Reach resolution
    // ------------------------------------------------------------------

    @Test
    void noGrantResolvesToNothing() {
        when(permissions.effectiveScopes(ANNA)).thenReturn(Map.of());
        ScopeResolution reach = service.resolveReach(ANNA, SALARIES_READ, AS_OF, Set.of());
        assertTrue(reach.isNone());
        verifyNoInteractions(resolver);
    }

    @Test
    void allScopeIsUnbounded() {
        grant(DataScope.ALL);
        ScopeResolution reach = service.resolveReach(ANNA, SALARIES_READ, AS_OF, Set.of());
        assertTrue(reach.unbounded());
        assertTrue(reach.permits(BO));
        verifyNoInteractions(resolver);
    }

    @Test
    void ownAndTeamGrantsUnion_theWorkedExample() {
        // Anna holds USER → salaries:read @ OWN and TEAMLEAD → salaries:read @ TEAM.
        grant(DataScope.OWN, DataScope.TEAM);
        when(resolver.resolveOwn(ANNA)).thenReturn(Set.of(ANNA));
        when(resolver.resolveTeam(ANNA, AS_OF)).thenReturn(Set.of(ANNA, "member-1", "member-2"));

        ScopeResolution reach = service.resolveReach(ANNA, SALARIES_READ, AS_OF, Set.of());

        assertFalse(reach.unbounded());
        assertEquals(DataScope.TEAM, reach.widestScope());
        assertEquals(Set.of(ANNA, "member-1", "member-2"), reach.subjects());
        assertTrue(reach.permits("member-1"));
        assertFalse(reach.permits(BO), "Bo is not among them");
    }

    @Test
    void disabledScopesAreExcluded() {
        grant(DataScope.OWN, DataScope.TEAM);
        when(resolver.resolveOwn(ANNA)).thenReturn(Set.of(ANNA));
        when(resolver.resolveTeam(ANNA, AS_OF)).thenReturn(Set.of(ANNA, "member-1"));

        // allowSelf: false — the OWN tier must not grant (mutations).
        ScopeResolution reach = service.resolveReach(ANNA, SALARIES_READ, AS_OF, Set.of(DataScope.OWN));
        assertEquals(Set.of(ANNA, "member-1"), reach.subjects());

        // allowTeamLead: false — relationship tiers must not grant (HR/ADMIN only).
        ScopeResolution restricted = service.resolveReach(ANNA, SALARIES_READ, AS_OF,
                Set.of(DataScope.TEAM, DataScope.PRACTICE, DataScope.COMPANY));
        assertEquals(Set.of(ANNA), restricted.subjects(), "only OWN remains");
    }

    @Test
    void disablingEveryGrantedScopeDenies() {
        grant(DataScope.OWN);
        ScopeResolution reach = service.resolveReach(ANNA, SALARIES_READ, AS_OF, Set.of(DataScope.OWN));
        assertTrue(reach.isNone());
    }

    // ------------------------------------------------------------------
    // Fail-closed (plan P8): errors never widen
    // ------------------------------------------------------------------

    @Test
    void blankActorUnknownPermissionAndNullDateAllDeny() {
        assertTrue(service.resolveReach(null, SALARIES_READ, AS_OF, Set.of()).isNone());
        assertTrue(service.resolveReach("  ", SALARIES_READ, AS_OF, Set.of()).isNone());
        assertTrue(service.resolveReach(ANNA, null, AS_OF, Set.of()).isNone());
        assertTrue(service.resolveReach(ANNA, SALARIES_READ, null, Set.of()).isNone());
    }

    @Test
    void scopeLookupFailureDenies() {
        when(permissions.effectiveScopes(anyString())).thenThrow(new IllegalStateException("db down"));
        assertTrue(service.resolveReach(ANNA, SALARIES_READ, AS_OF, Set.of()).isNone());
    }

    @Test
    void subjectResolutionFailureDeniesEntirely_neverPartially() {
        grant(DataScope.OWN, DataScope.TEAM);
        when(resolver.resolveOwn(ANNA)).thenReturn(Set.of(ANNA));
        when(resolver.resolveTeam(any(), any())).thenThrow(new IllegalStateException("lookup failed"));

        ScopeResolution reach = service.resolveReach(ANNA, SALARIES_READ, AS_OF, Set.of());
        assertTrue(reach.isNone(),
                "phase file 8.2: any resolution failure returns the empty set — not the surviving tiers");
    }

    // ------------------------------------------------------------------
    // Subject decisions — the simulator's canonical Anna example
    // ------------------------------------------------------------------

    @Test
    void annaHoldsTeamScope_boIsNotAmongThem() {
        grant(DataScope.TEAM);
        when(resolver.resolveTeam(ANNA, AS_OF))
                .thenReturn(Set.of(ANNA, "m1", "m2", "m3", "m4", "m5", "m6",
                        "m7", "m8", "m9", "m10", "m11"));

        AuthorizationService.AccessDecision decision =
                service.decideSubjectAccess(ANNA, SALARIES_READ, BO, AS_OF, Set.of());

        assertFalse(decision.allowed());
        assertEquals(DataScope.TEAM, decision.widestScope());
        assertEquals(12, decision.subjectCount());
        assertTrue(decision.reason().contains("TEAM"), decision.reason());
    }

    @Test
    void unboundedDecisionAllowsAnySubject() {
        grant(DataScope.ALL);
        AuthorizationService.AccessDecision decision =
                service.decideSubjectAccess(ANNA, SALARIES_READ, BO, AS_OF, Set.of());
        assertTrue(decision.allowed());
        assertTrue(decision.unbounded());
        assertEquals(DataScope.ALL, decision.widestScope());
    }

    @Test
    void missingSubjectDenies() {
        AuthorizationService.AccessDecision decision =
                service.decideSubjectAccess(ANNA, SALARIES_READ, " ", AS_OF, Set.of());
        assertFalse(decision.allowed());
        assertNull(decision.widestScope());
    }
}
