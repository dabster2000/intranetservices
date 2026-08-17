package dk.trustworks.intranet.security;

import jakarta.enterprise.context.ContextNotActiveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of {@link ScopeGuard}'s actor detection. Unlike the
 * request filter, guard methods run in resource code — after
 * {@link HeaderInterceptor} has populated {@link RequestHeaderHolder} — so a
 * machine caller does not appear as an absent value: the holder carries the JWT
 * client id or {@code "anonymous"}. The {@link HumanActor} UUID shape is the
 * human/machine discriminator; treating those back-fills as humans would
 * resolve to no reach and deny every batch and system call (findings,
 * 2026-08-17).
 */
class ScopeGuardTest {

    private static final String ACTOR = "aaaaaaaa-0000-0000-0000-000000000001";

    private ScopeGuard guard;
    private AuthorizationService authorizationService;
    private RequestHeaderHolder holder;

    @BeforeEach
    void setUp() {
        guard = new ScopeGuard();
        authorizationService = mock(AuthorizationService.class);
        holder = mock(RequestHeaderHolder.class);
        guard.authorizationService = authorizationService;
        guard.requestHeaderHolder = holder;
    }

    @Test
    void humanUuidIsTheActor() {
        when(holder.getUserUuid()).thenReturn(ACTOR);
        assertEquals(ACTOR, guard.actorOrNull());
    }

    @Test
    void headerInterceptorBackfillsAreNotActors() {
        for (String machine : new String[]{"tw-nextjs-bff", "system:autofix-worker", "anonymous", "", null}) {
            when(holder.getUserUuid()).thenReturn(machine);
            assertNull(guard.actorOrNull(), String.valueOf(machine));
        }
    }

    @Test
    void noActiveRequestContextMeansNoActor() {
        when(holder.getUserUuid()).thenThrow(new ContextNotActiveException());
        assertNull(guard.actorOrNull());
    }

    @Test
    void machineCallersPassSubjectChecksUntouched() {
        // Phase 12 territory: the deliberate, findings-recorded fail-open.
        when(holder.getUserUuid()).thenReturn("anonymous");

        assertDoesNotThrow(() -> guard.requireSubjectWhenActor(
                "expenses:read", "some-subject-uuid", "denied"));
        assertNull(guard.reachOrNull("expenses:read"));
        assertFalse(guard.actorHasUnbounded("expenses:read"));
        verifyNoInteractions(authorizationService);
    }
}
