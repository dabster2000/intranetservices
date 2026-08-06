package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.dao.workservice.services.MonthSubmissionService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.DataScope;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeEnforced;
import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.security.TestScopeGuards;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of Phase 10.1 enforcement on the time-registration
 * endpoints. Under access-intent Decision 14 every {@code timeregistration:*}
 * grant is scope ALL, so every guard here passes for every real actor today —
 * these tests pin (a) that today's shared-client-hours behaviour is untouched,
 * and (b) that the deny paths engage the moment a future console narrowing
 * produces a bounded reach. Without (b), the "narrowable later with one click"
 * promise of Decisions 6/14 would be a silent no-op.
 */
class WorkResourceScopeTest {

    private static final String ACTOR = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OTHER = "bbbbbbbb-0000-0000-0000-000000000002";

    private WorkResource resource;
    private WorkService workService;
    private AuthorizationService authorizationService;
    private RequestHeaderHolder headers;

    @BeforeEach
    void setUp() {
        resource = new WorkResource();
        workService = mock(WorkService.class);
        authorizationService = mock(AuthorizationService.class);
        headers = mock(RequestHeaderHolder.class);

        ScopeGuard guard = TestScopeGuards.wired(authorizationService, headers);

        resource.workAPI = workService;
        resource.sender = mock(AggregateEventSender.class);
        resource.requestHeaderHolder = headers;
        resource.monthSubmissionService = mock(MonthSubmissionService.class);
        resource.scope = guard;
    }

    private void actorIs(String actor) {
        when(headers.getUserUuid()).thenReturn(actor);
    }

    private void subjectDecision(String permission, String subject, boolean allowed) {
        when(authorizationService.decideSubjectAccess(eq(ACTOR), eq(permission), eq(subject), any(), anySet()))
                .thenReturn(new AuthorizationService.AccessDecision(
                        allowed, allowed ? DataScope.ALL : DataScope.OWN, allowed, 0,
                        allowed ? "ALL grant" : "subject outside reach"));
    }

    // ---- Today's world: every grant is ALL (Decision 14) --------------------

    @Test
    void crossUserReadPassesWhenReachIsUnbounded() {
        actorIs(ACTOR);
        subjectDecision(WorkResource.READ_SCOPE, OTHER, true);
        resource.findByPeriodAndUserAndTasks("2026-07-01", "2026-08-01", OTHER, "t1");
        verify(workService).findByPeriodAndUserAndTasks(any(), any(), eq(OTHER), eq("t1"));
    }

    @Test
    void headerlessCallerIsUntouched() {
        actorIs(null);
        resource.findByPeriodAndUserAndTasks("2026-07-01", "2026-08-01", OTHER, "t1");
        verify(workService).findByPeriodAndUserAndTasks(any(), any(), eq(OTHER), eq("t1"));
        verify(authorizationService, never()).decideSubjectAccess(any(), any(), any(), any(), anySet());
    }

    // ---- The future-narrowing world: deny paths must actually deny ----------

    @Test
    void crossUserReadIs403ForABoundedActor() {
        actorIs(ACTOR);
        subjectDecision(WorkResource.READ_SCOPE, OTHER, false);
        assertThrows(ForbiddenException.class,
                () -> resource.findByUserAndTasks(OTHER, "t1"));
        verify(workService, never()).findWorkFullByUserAndTasks(any(), any());
    }

    @Test
    void ownReadAlwaysPassesForABoundedActor() {
        actorIs(ACTOR);
        subjectDecision(WorkResource.READ_SCOPE, ACTOR, true);
        resource.sumBillableByUserAndTasks(ACTOR, "2026-07-01");
        verify(workService).sumBillableByUserAndTasks(eq(ACTOR), any());
    }

    @Test
    void crossUserWriteIs403ForABoundedActor() {
        actorIs(ACTOR);
        subjectDecision(WorkResource.WRITE_SCOPE, OTHER, false);
        var work = new dk.trustworks.intranet.dao.workservice.model.Work();
        work.setUseruuid(OTHER);
        assertThrows(ForbiddenException.class, () -> resource.save(work));
        verify(workService, never()).persistOrUpdate(any());
    }

    // ---- Placement pins: the company-wide surfaces carry @ScopeEnforced -----

    @Test
    void companyWideSurfacesAreScopeEnforced() {
        List<String> expected = List.of(
                "listAll", "findByTasks", "findByPeriod", "findByPeriodPaged",
                "findByPeriodLightweight", "countByPeriod",
                "findByPeriodGroupedByUser", "getWorkSummaryByPeriod");
        for (String name : expected) {
            Method m = Arrays.stream(WorkResource.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertTrue(m.isAnnotationPresent(ScopeEnforced.class),
                    name + " must be @ScopeEnforced — it returns company-wide rows with no subject dimension");
        }
    }

    @Test
    void perSubjectSurfacesAreNotScopeEnforced() {
        // These filter by subject via the guard instead — @ScopeEnforced on them
        // would 403 a future OWN-scoped user reading their own hours.
        for (String name : List.of("findByPeriodAndUserAndTasks", "findByUserAndTasks",
                "sumWorkdurationByUserAndTasks", "sumBillableByUserAndTasks", "save")) {
            Method m = Arrays.stream(WorkResource.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertFalse(m.isAnnotationPresent(ScopeEnforced.class),
                    name + " must NOT be @ScopeEnforced — it has a subject dimension");
        }
    }

    // ---- Decision 11: submission authority stays outside the scope model ----

    @Test
    void monthSubmissionSubmitAndUnlockCarryNoScopeGuard() throws Exception {
        // The authority list (app_settings, BFF-enforced) governs on-behalf
        // submits and unlocks. A reach check here would re-key that authority
        // on data scope — pinned absent by owner Decision 11.
        MonthSubmissionResource ms = new MonthSubmissionResource();
        ms.monthSubmissionService = mock(MonthSubmissionService.class);
        ms.requestHeaderHolder = headers;
        ms.scope = TestScopeGuards.wired(authorizationService, headers);
        when(ms.monthSubmissionService.submit(any(), eq(2026), eq(7)))
                .thenReturn(null);

        actorIs(ACTOR);
        // Bounded actor, someone else's month — submit must still reach the service.
        var request = new MonthSubmissionResource.SubmitRequest();
        request.useruuid = OTHER;
        request.year = 2026;
        request.month = 7;
        ms.submit(request);
        verify(ms.monthSubmissionService).submit(eq(OTHER), eq(2026), eq(7));
        verify(authorizationService, never()).decideSubjectAccess(any(), any(), any(), any(), anySet());
    }

    @Test
    void monthSubmissionReadIsSubjectChecked() {
        MonthSubmissionResource ms = new MonthSubmissionResource();
        ms.monthSubmissionService = mock(MonthSubmissionService.class);
        ms.requestHeaderHolder = headers;
        ms.scope = TestScopeGuards.wired(authorizationService, headers);

        actorIs(ACTOR);
        subjectDecision(WorkResource.READ_SCOPE, OTHER, false);
        assertThrows(ForbiddenException.class, () -> ms.getSubmission(OTHER, 2026, 7));
        verify(ms.monthSubmissionService, never()).findByUserAndMonth(any(), eq(2026), eq(7));
    }
}
