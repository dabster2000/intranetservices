package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.DataScope;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeResolution;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of Phase 8 enforcement on the salary read endpoints
 * (task 8.6): scoped when the caller identifies a human actor
 * ({@code X-Requested-By} — every BFF call), untouched when it does not (batch
 * jobs and API clients — the deliberate Phase 12 deferral, recorded in
 * findings). The scoped branch binds the subject set into the query, never a
 * post-filter (8.6), so the response filter has nothing to strip (8.7).
 */
class SalaryResourceScopeTest {

    private static final String ACTOR = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String TARGET = "bbbbbbbb-0000-0000-0000-000000000002";

    private SalaryResource resource;
    private SalaryService salaryService;
    private AuthorizationService authorizationService;
    private RequestHeaderHolder headers;
    private AvailabilityService availabilityService;

    @BeforeEach
    void setUp() {
        resource = new SalaryResource();
        salaryService = mock(SalaryService.class);
        authorizationService = mock(AuthorizationService.class);
        headers = mock(RequestHeaderHolder.class);
        availabilityService = mock(AvailabilityService.class);
        resource.salaryService = salaryService;
        resource.authorizationService = authorizationService;
        resource.requestHeaderHolder = headers;
    }

    @Test
    void noActorHeaderKeepsPrePhase8Behaviour() {
        // Batch jobs and API clients carry no X-Requested-By — Phase 12 territory.
        when(headers.getUserUuid()).thenReturn(null);
        List<Salary> rows = List.of(new Salary(LocalDate.now(), 1000, TARGET));
        when(salaryService.findByUseruuid(TARGET)).thenReturn(rows);

        assertEquals(rows, resource.listAll(TARGET));
        verifyNoInteractions(authorizationService);
        verify(salaryService, never()).findByUseruuid(anyString(), anySet());
    }

    @Test
    void unboundedActorReadsUnscoped() {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.resolveReach(eq(ACTOR), eq("salaries:read"), any(), anySet()))
                .thenReturn(ScopeResolution.unboundedAll());
        List<Salary> rows = List.of(new Salary(LocalDate.now(), 1000, TARGET));
        when(salaryService.findByUseruuid(TARGET)).thenReturn(rows);

        assertEquals(rows, resource.listAll(TARGET));
    }

    @Test
    void boundedActorReadsThroughTheScopedQuery() {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        Set<String> subjects = Set.of(ACTOR, TARGET);
        when(authorizationService.resolveReach(eq(ACTOR), eq("salaries:read"), any(), anySet()))
                .thenReturn(ScopeResolution.bounded(DataScope.TEAM, subjects));
        List<Salary> rows = List.of(new Salary(LocalDate.now(), 1000, TARGET));
        when(salaryService.findByUseruuid(TARGET, subjects)).thenReturn(rows);

        assertEquals(rows, resource.listAll(TARGET));
        // 8.6: the subject set reached the query — the unscoped path was never used.
        verify(salaryService, never()).findByUseruuid(TARGET);
    }

    @Test
    void targetOutsideTheBoundedReachIs403() {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.resolveReach(eq(ACTOR), eq("salaries:read"), any(), anySet()))
                .thenReturn(ScopeResolution.bounded(DataScope.TEAM, Set.of(ACTOR, "someone-else")));

        assertThrows(ForbiddenException.class, () -> resource.listAll(TARGET));
        verifyNoInteractions(salaryService);
    }

    @Test
    void actorWithNoGrantIs403() {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.resolveReach(eq(ACTOR), eq("salaries:read"), any(), anySet()))
                .thenReturn(ScopeResolution.none());

        assertThrows(ForbiddenException.class, () -> resource.listAll(TARGET));
        verifyNoInteractions(salaryService);
    }

    @Test
    void paymentsAndPaycheckOverviewDenyOutsideReach() {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.decideSubjectAccess(eq(ACTOR), eq("salaries:read"), eq(TARGET), any(), anySet()))
                .thenReturn(new AuthorizationService.AccessDecision(false, DataScope.TEAM, false, 3,
                        "subject is not among them"));

        assertThrows(ForbiddenException.class, () -> resource.listPayments(TARGET, "2026-08-01"));
        assertThrows(ForbiddenException.class, () -> resource.getPaycheckOverview(TARGET));
    }

    // ------------------------------------------------------------------
    // Self-reads — the prod regression of 2026-08-09..12. The rule itself lives in
    // AuthorizationServiceImpl (see AuthorizationServiceImplTest); what these pin is
    // that each of the three /salaries URL families honours it.
    // ------------------------------------------------------------------

    /**
     * {@code GET /users/{self}/salaries} decides self in the resource, because it needs
     * a subject set for the scoped query rather than a yes/no. A reach of
     * {@link ScopeResolution#none()} is the exact production state — it must not 403 the
     * owner, and the query must stay bounded to that one person.
     */
    @Test
    void selfReadOfOwnSalaryHistoryIsAllowedWithNoGrantAtAll() {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.resolveReach(eq(ACTOR), eq("salaries:read"), any(), anySet()))
                .thenReturn(ScopeResolution.none());
        List<Salary> rows = List.of(new Salary(LocalDate.now(), 1000, ACTOR));
        when(salaryService.findByUseruuid(ACTOR)).thenReturn(rows);

        assertEquals(rows, resource.listAll(ACTOR));
        verify(salaryService, never()).findByUseruuid(anyString(), anySet());
    }

    /** …and the same empty reach still refuses a colleague's salary history. */
    @Test
    void foreignReadWithNoGrantIsStill403_alongsideTheSelfBypass() {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        when(authorizationService.resolveReach(eq(ACTOR), eq("salaries:read"), any(), anySet()))
                .thenReturn(ScopeResolution.none());

        assertThrows(ForbiddenException.class, () -> resource.listAll(TARGET));
        verifyNoInteractions(salaryService);
    }

    /**
     * {@code GET /users/{self}/salaries/payments/{month}} — the gate lets the caller
     * through and the method body runs (no salary on record for the month, so it
     * returns empty rather than throwing).
     */
    @Test
    void selfReadOfOwnPaymentsRunsTheMethodBody() {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        allowSelf();
        resource.availabilityService = availabilityService;
        when(availabilityService.getEmployeeDataPerMonth(eq(ACTOR), any(), any())).thenReturn(List.of());

        assertEquals(List.of(), resource.listPayments(ACTOR, "2026-08-01"));
    }

    /**
     * {@code GET /users/{self}/salaries/paycheck-overview} — the endpoint from the prod
     * log lines. Its body needs Panache, which a database-free test has none of, so the
     * assertion is precisely the one that matters: whatever stops it, it is not the
     * salaries:read gate.
     */
    @Test
    void selfReadOfOwnPaycheckOverviewIsNotDeniedAtTheGate() {
        when(headers.getUserUuid()).thenReturn(ACTOR);
        allowSelf();
        resource.userService = mock(UserService.class);

        ForbiddenException denied = null;
        try {
            resource.getPaycheckOverview(ACTOR);
        } catch (ForbiddenException e) {
            denied = e;
        } catch (Throwable reachedTheDataLayer) {
            // Past the gate and into Panache — which is as far as a DB-free test goes.
        }

        assertNull(denied, "an employee's own paycheck overview must never be refused by the gate");
    }

    /** The seam's verdict for a self-read, as AuthorizationServiceImpl now returns it. */
    private void allowSelf() {
        when(authorizationService.decideSubjectAccess(eq(ACTOR), eq("salaries:read"), eq(ACTOR), any(), anySet()))
                .thenReturn(new AuthorizationService.AccessDecision(true, DataScope.OWN, false, 1,
                        "Subject is the actor — OWN access, which no missing grant can withhold"));
    }
}
