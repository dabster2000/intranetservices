package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.CreateExpenseDTO;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseFileService;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.DataScope;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeEnforced;
import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.security.ScopeResolution;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Database-free coverage of Phase 9.2 enforcement on the expense endpoints:
 * scoped when the caller identifies a human actor ({@code X-Requested-By}),
 * untouched when it does not (batch jobs, e-conomic sync — the Phase 12
 * deferral recorded in findings). The mobile expense flow is the OWN case by
 * construction: its session resolves the actor to the device owner, who always
 * reaches themselves — pinned here so over-gating the flow cannot compile
 * silently.
 */
class ExpenseResourceScopeTest {

    private static final String ACTOR = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OTHER = "bbbbbbbb-0000-0000-0000-000000000002";

    private ExpenseResource resource;
    private ExpenseService expenseService;
    private AuthorizationService authorizationService;
    private RequestHeaderHolder headers;
    private SecurityIdentity identity;

    @BeforeEach
    void setUp() {
        resource = new ExpenseResource();
        expenseService = mock(ExpenseService.class);
        authorizationService = mock(AuthorizationService.class);
        headers = mock(RequestHeaderHolder.class);
        identity = mock(SecurityIdentity.class);

        ScopeGuard guard = dk.trustworks.intranet.security.TestScopeGuards.wired(authorizationService, headers);

        resource.expenseService = expenseService;
        resource.expenseFileService = mock(ExpenseFileService.class);
        resource.header = headers;
        resource.identity = identity;
        resource.scope = guard;
    }

    private void actorIs(String actor) {
        when(headers.getUserUuid()).thenReturn(actor);
    }

    private void reachIs(String permission, ScopeResolution resolution) {
        when(authorizationService.resolveReach(eq(ACTOR), eq(permission), any(), anySet()))
                .thenReturn(resolution);
    }

    private void decisionIs(String permission, String subject, boolean allowed) {
        when(authorizationService.decideSubjectAccess(eq(ACTOR), eq(permission), eq(subject), any(), anySet()))
                .thenReturn(new AuthorizationService.AccessDecision(allowed,
                        allowed ? DataScope.OWN : null, false, 1,
                        allowed ? "subject is among them" : "subject is not among them"));
    }

    private static Expense expenseOwnedBy(String useruuid) {
        Expense e = new Expense();
        e.setUseruuid(useruuid);
        return e;
    }

    // ------------------------------------------------------------------
    // Ledger list — GET /expenses/user/{useruuid}
    // ------------------------------------------------------------------

    @Test
    void headerlessListKeepsPrePhase9Behaviour() {
        actorIs(null);
        List<Expense> rows = List.of(expenseOwnedBy(OTHER));
        when(expenseService.findVisibleByUser(eq(OTHER), anyInt(), anyInt(), isNull())).thenReturn(rows);

        assertEquals(rows, resource.findByUser(OTHER, "50", "0", false));
        verify(expenseService, never()).findVisibleByUser(any(), anyInt(), anyInt(), anySet());
    }

    @Test
    void ownScopedActorReadsTheirOwnLedgerThroughTheScopedQuery() {
        // The mobile flow's exact shape: tw_mobile_session resolves the actor to
        // the device owner, OWN reach includes them, the ledger stays theirs.
        actorIs(ACTOR);
        reachIs("expenses:read", ScopeResolution.bounded(DataScope.OWN, Set.of(ACTOR)));
        List<Expense> rows = List.of(expenseOwnedBy(ACTOR));
        when(expenseService.findVisibleByUser(ACTOR, 0, 50, Set.of(ACTOR))).thenReturn(rows);

        assertEquals(rows, resource.findByUser(ACTOR, "50", "0", false));
        verify(expenseService, never()).findVisibleByUser(eq(ACTOR), anyInt(), anyInt(), isNull());
    }

    @Test
    void ownScopedActorCannotListAnotherLedger() {
        actorIs(ACTOR);
        reachIs("expenses:read", ScopeResolution.bounded(DataScope.OWN, Set.of(ACTOR)));

        assertThrows(ForbiddenException.class, () -> resource.findByUser(OTHER, "50", "0", false));
        verify(expenseService, never()).findVisibleByUser(any(), anyInt(), anyInt(), any());
    }

    @Test
    void unboundedActorListsAnyLedgerUnscoped() {
        actorIs(ACTOR);
        reachIs("expenses:read", ScopeResolution.unboundedAll());
        List<Expense> rows = List.of(expenseOwnedBy(OTHER));
        when(expenseService.findVisibleByUser(eq(OTHER), anyInt(), anyInt(), isNull())).thenReturn(rows);

        assertEquals(rows, resource.findByUser(OTHER, "50", "0", false));
    }

    // ------------------------------------------------------------------
    // Receipt file — GET /expenses/file/{uuid} (the phase file's named route)
    // ------------------------------------------------------------------

    @Test
    void receiptOfAnotherUserIs403ForABoundedActor() {
        actorIs(ACTOR);
        when(expenseService.findByUuid("exp-1")).thenReturn(expenseOwnedBy(OTHER));
        decisionIs("expenses:read", OTHER, false);

        assertThrows(ForbiddenException.class, () -> resource.getFileById("exp-1"));
    }

    @Test
    void ownReceiptIsServedToTheOwner() {
        actorIs(ACTOR);
        when(expenseService.findByUuid("exp-1")).thenReturn(expenseOwnedBy(ACTOR));
        decisionIs("expenses:read", ACTOR, true);

        resource.getFileById("exp-1"); // no throw — the file service replies
    }

    @Test
    void receiptWithNoOwningExpenseIs404ForAnActor() {
        actorIs(ACTOR);
        when(expenseService.findByUuid("orphan")).thenReturn(null);

        assertThrows(NotFoundException.class, () -> resource.getFileById("orphan"));
    }

    @Test
    void headerlessReceiptFetchIsUntouched() {
        // The nightly PDF/e-conomic flows read receipts with no actor header.
        actorIs(null);

        resource.getFileById("exp-1");
        verify(expenseService, never()).findByUuid(any());
    }

    // ------------------------------------------------------------------
    // Single expense — GET /expenses/{uuid}
    // ------------------------------------------------------------------

    @Test
    void singleExpenseOutsideReachIs403() {
        actorIs(ACTOR);
        when(expenseService.findByUuid("exp-2")).thenReturn(expenseOwnedBy(OTHER));
        decisionIs("expenses:read", OTHER, false);

        assertThrows(ForbiddenException.class, () -> resource.findByUuid("exp-2"));
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Test
    void filingAnExpenseForAnotherUserRequiresUnboundedReviewReach() {
        actorIs(ACTOR);
        reachIs("expenses:review", ScopeResolution.bounded(DataScope.OWN, Set.of(ACTOR)));
        CreateExpenseDTO dto = new CreateExpenseDTO();
        dto.setUseruuid(OTHER);

        assertThrows(ForbiddenException.class, () -> resource.saveExpense(dto));
    }

    @Test
    void filingYourOwnExpenseNeedsNoReviewReach() throws Exception {
        actorIs(ACTOR);
        CreateExpenseDTO dto = new CreateExpenseDTO();
        dto.setUseruuid(ACTOR);
        dto.setExpensedate(java.time.LocalDate.now());

        resource.classificationService = mock(
                dk.trustworks.intranet.expenseservice.services.ExpenseClassificationService.class);
        resource.saveExpense(dto); // no throw; processExpense mock absorbs the save
    }

    @Test
    void reviewerBranchOnUpdateIsActorBasedNotClientCredentialBased() {
        // The BFF system token always carries expenses:review — before Phase 9.2
        // the "reviewer" branch admitted every BFF-carried human. Now the human's
        // own reach decides.
        actorIs(ACTOR);
        when(identity.hasRole("expenses:review")).thenReturn(true); // client credential says yes
        reachIs("expenses:review", ScopeResolution.none());          // the human holds nothing
        when(expenseService.findByUuid("exp-3")).thenReturn(expenseOwnedBy(OTHER));

        assertThrows(ForbiddenException.class, () -> resource.updateOne("exp-3", new Expense()));
        assertThrows(ForbiddenException.class, () -> resource.delete("exp-3"));
    }

    // ------------------------------------------------------------------
    // Placement pins — company-wide lists stay @ScopeEnforced
    // ------------------------------------------------------------------

    @Test
    void companyWideExpenseListsAreScopeEnforced() throws Exception {
        for (String methodName : List.of("findByPeriod", "findByStatuses")) {
            Method m = findMethod(methodName);
            assertTrue(m.isAnnotationPresent(ScopeEnforced.class),
                    methodName + " must stay @ScopeEnforced — it returns a company-wide row set");
        }
        Method project = ExpenseResource.class.getMethod("findByProjectAndPeriod",
                String.class, String.class, String.class);
        assertTrue(project.isAnnotationPresent(ScopeEnforced.class));
    }

    private static Method findMethod(String name) {
        for (Method m : ExpenseResource.class.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        throw new AssertionError("no method " + name + " on ExpenseResource");
    }
}
