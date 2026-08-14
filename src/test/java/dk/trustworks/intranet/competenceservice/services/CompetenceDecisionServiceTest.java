package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttemptDecision;
import dk.trustworks.intranet.competenceservice.model.DecisionType;
import dk.trustworks.intranet.competenceservice.services.CompetenceDecisionService.DecisionOutcome;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.DataScope;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeResolution;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompetenceDecisionService#decide} — spec §5.10, §6.2, §10.1, §10.4, tested in the
 * DB-free fast tier (§12.1) because that is the tier the CI deploy gate runs.
 *
 * <p>Two of the assertions here are the module's own credibility. The competence module
 * <em>teaches</em> funktionsadskillelse, so a leader approving their own attempt would
 * discredit the content it delivers, and an auditor is expected to probe exactly that.
 * The same goes for an impersonated approval: the entire value of the ledger is that it
 * names who attested, so a row carrying an admin's session but somebody else's identity is
 * worse than no row at all. Both rules therefore belong in the gating tier and not in the
 * ungated {@code @QuarkusTest} tier, which rots silently.
 *
 * <p><strong>Why no {@code @QuarkusTest}.</strong> The two collaborators {@code decide}
 * actually needs are an {@link AuthorizationService} (an interface — a plain Mockito mock)
 * and a {@link RequestHeaderHolder} (a Lombok {@code @Data} bean — just {@code new}). The
 * only database contact left is {@code CompetenceAttempt.findByUuid}, a Panache static that
 * {@code mockStatic} intercepts the way the rest of this codebase's fast-tier service tests
 * do, and the {@code new CompetenceAttemptDecision().persist()} in the append path, which
 * {@code mockConstruction} intercepts so the ledger write is observed rather than executed.
 * Verifying the constructed rows is in fact a stronger assertion than a round trip: it lets
 * the test state that a refused item wrote <em>nothing</em>, which is the property that
 * matters for an append-only table.
 *
 * <p>The DB-level immutability of {@code competence_attempt_decision} (UPDATE and DELETE both
 * SIGNAL, so a correction is an appended REVOKED row and never an edit) is the
 * {@code @QuarkusTest} tier's job — §12.2.
 */
class CompetenceDecisionServiceTest {

    /** The team lead doing the deciding. */
    private static final String ACTOR = "8f6f2f2a-lead-0000-0000-000000000001";
    private static final String SUBJECT_A = "8f6f2f2a-empl-0000-0000-00000000000a";
    private static final String SUBJECT_B = "8f6f2f2a-empl-0000-0000-00000000000b";

    private static final String ATTEMPT_A = "attempt-a";
    private static final String ATTEMPT_B = "attempt-b";
    /** The actor's own passed attempt — the funktionsadskillelse probe. */
    private static final String ATTEMPT_SELF = "attempt-self";

    private final AuthorizationService authorizationService = mock(AuthorizationService.class);
    private final RequestHeaderHolder requestHeaderHolder = new RequestHeaderHolder();

    private final CompetenceDecisionService service = new CompetenceDecisionService();

    @BeforeEach
    void wire() {
        // The injection points are package-private, and this test shares their package, so the
        // service can be assembled without a CDI container.
        service.authorizationService = authorizationService;
        service.requestHeaderHolder = requestHeaderHolder;
        service.statusService = mock(CompetenceStatusService.class);
        requestHeaderHolder.setUserUuid(ACTOR);
        // Reach wide enough to include the actor themselves: the self-approval refusal must be
        // the reason the actor's own attempt is refused, not a scope miss that would mask it.
        reachOver(ACTOR, SUBJECT_A, SUBJECT_B);
    }

    // -----------------------------------------------------------------------
    // §10.4 — impersonated write refused
    // -----------------------------------------------------------------------

    @Test
    void decide_whileImpersonating_isRefusedWithForbidden() {
        requestHeaderHolder.setActingForUuid("admin-behind-the-session");

        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A)).thenReturn(passedAttempt(ATTEMPT_A, SUBJECT_A));

            WebApplicationException ex = assertThrows(WebApplicationException.class,
                    () -> service.decide(List.of(ATTEMPT_A), DecisionType.APPROVED, null, ACTOR));

            assertEquals(Response.Status.FORBIDDEN.getStatusCode(), ex.getResponse().getStatus());
            // WebApplicationException built from a Response entity loses the entity, so the
            // message must be carried by the String constructor. Assert it survived.
            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().toLowerCase().contains("impersonat"),
                    "the refusal must say why: " + ex.getMessage());
            assertTrue(ledger.constructed().isEmpty(),
                    "an impersonated request must not append to the ledger at all");
        }
    }

    @Test
    void decide_whileImpersonating_isRefusedBeforeReachIsEvenResolved() {
        requestHeaderHolder.setActingForUuid("admin-behind-the-session");

        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class)) {
            assertThrows(WebApplicationException.class,
                    () -> service.decide(List.of(ATTEMPT_A), DecisionType.APPROVED, null, ACTOR));

            // Ordering matters: an impersonated session must not be able to probe another
            // person's scope membership through a refused write.
            verify(authorizationService, never())
                    .resolveReach(any(), any(), any(LocalDate.class), anySet());
            attempts.verifyNoInteractions();
        }
    }

    @Test
    void decide_withBlankActingForHeader_isNotTreatedAsImpersonation() {
        // X-Acting-For arriving empty is an ordinary request; isImpersonated() keys on blankness.
        requestHeaderHolder.setActingForUuid("   ");

        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A))
                    .thenReturn(passedAttempt(ATTEMPT_A, SUBJECT_A));

            List<DecisionOutcome> outcomes =
                    service.decide(List.of(ATTEMPT_A), DecisionType.APPROVED, null, ACTOR);

            assertEquals(1, outcomes.size());
            assertTrue(outcomes.get(0).ok());
            assertEquals(1, ledger.constructed().size());
        }
    }

    // -----------------------------------------------------------------------
    // §5.10 — self-approval refused
    // -----------------------------------------------------------------------

    @Test
    void decide_ownAttempt_isRefusedAndWritesNothing() {
        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_SELF))
                    .thenReturn(passedAttempt(ATTEMPT_SELF, ACTOR));

            List<DecisionOutcome> outcomes =
                    service.decide(List.of(ATTEMPT_SELF), DecisionType.APPROVED, null, ACTOR);

            assertEquals(1, outcomes.size());
            DecisionOutcome outcome = outcomes.get(0);
            assertEquals(ATTEMPT_SELF, outcome.attemptUuid());
            assertFalse(outcome.ok(), "a leader may not approve their own attempt (§5.10)");
            assertTrue(selfApprovalRefusal(outcome),
                    "the refusal must name the reason, not read as a scope miss: " + outcome.message());
            assertTrue(ledger.constructed().isEmpty(),
                    "a refused self-approval must leave the append-only ledger untouched");
        }
    }

    @Test
    void decide_ownAttempt_isRefusedForRevocationToo() {
        // The rule is about the actor and the subject being the same person, not about the
        // direction of the decision: self-revocation would be self-service evidence too.
        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_SELF))
                    .thenReturn(passedAttempt(ATTEMPT_SELF, ACTOR));

            List<DecisionOutcome> outcomes = service.decide(
                    List.of(ATTEMPT_SELF), DecisionType.REVOKED, "wrongly approved", ACTOR);

            assertEquals(1, outcomes.size());
            assertFalse(outcomes.get(0).ok());
            assertTrue(selfApprovalRefusal(outcomes.get(0)));
            assertTrue(ledger.constructed().isEmpty());
        }
    }

    @Test
    void decide_outsideReach_isRefusedAsScopeMissNotAsSelfApproval() {
        // Reach is checked before the self-check on purpose: for an actor whose grant does not
        // even cover themselves, "outside your data scope" is the honest answer, and it keeps
        // the scope check unconditional rather than short-circuited by identity.
        reachOver(SUBJECT_A);

        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_SELF))
                    .thenReturn(passedAttempt(ATTEMPT_SELF, ACTOR));
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_B))
                    .thenReturn(passedAttempt(ATTEMPT_B, SUBJECT_B));

            List<DecisionOutcome> outcomes = service.decide(
                    List.of(ATTEMPT_SELF, ATTEMPT_B), DecisionType.APPROVED, null, ACTOR);

            assertEquals(2, outcomes.size());
            assertFalse(outcomes.get(0).ok());
            assertFalse(selfApprovalRefusal(outcomes.get(0)),
                    "an actor outside their own reach must be told about the scope, not about "
                            + "self-approval: " + outcomes.get(0).message());
            assertFalse(outcomes.get(1).ok(), "SUBJECT_B is not in the actor's reach");
            assertTrue(ledger.constructed().isEmpty());
        }
    }

    @Test
    void decide_noGrantAtAll_refusesEveryItemAndNeverWidensToEveryone() {
        // Fail closed (§3.3): an unresolvable reach is nothing, never the whole company.
        when(authorizationService.resolveReach(any(), any(), any(LocalDate.class), anySet()))
                .thenReturn(ScopeResolution.none());

        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A))
                    .thenReturn(passedAttempt(ATTEMPT_A, SUBJECT_A));

            List<DecisionOutcome> outcomes =
                    service.decide(List.of(ATTEMPT_A), DecisionType.APPROVED, null, ACTOR);

            assertEquals(1, outcomes.size());
            assertFalse(outcomes.get(0).ok());
            assertTrue(ledger.constructed().isEmpty());
        }
    }

    @Test
    void decide_resolvesReachForTheApprovePermission() {
        // The queue and the write share one permission key; a typo here would silently widen
        // or narrow the whole surface, and no other test would notice.
        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ignored =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A))
                    .thenReturn(passedAttempt(ATTEMPT_A, SUBJECT_A));

            service.decide(List.of(ATTEMPT_A), DecisionType.APPROVED, null, ACTOR);

            verify(authorizationService).resolveReach(
                    eq(ACTOR), eq(CompetenceDecisionService.PERMISSION_APPROVE),
                    any(LocalDate.class), anySet());
            assertEquals("competence:approve", CompetenceDecisionService.PERMISSION_APPROVE);
        }
    }

    // -----------------------------------------------------------------------
    // §6.2 — bulk decision partial results
    // -----------------------------------------------------------------------

    @Test
    void decide_bulkWithOneSelfApproval_returnsPartialResultsAndDecidesTheRest() {
        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A))
                    .thenReturn(passedAttempt(ATTEMPT_A, SUBJECT_A));
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_SELF))
                    .thenReturn(passedAttempt(ATTEMPT_SELF, ACTOR));
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_B))
                    .thenReturn(passedAttempt(ATTEMPT_B, SUBJECT_B));

            List<DecisionOutcome> outcomes = service.decide(
                    List.of(ATTEMPT_A, ATTEMPT_SELF, ATTEMPT_B), DecisionType.APPROVED, null, ACTOR);

            // One outcome per requested attempt, in the order supplied — the UI marks rows.
            assertEquals(3, outcomes.size());
            assertEquals(List.of(ATTEMPT_A, ATTEMPT_SELF, ATTEMPT_B),
                    outcomes.stream().map(DecisionOutcome::attemptUuid).toList());

            // Exactly one refusal, and it is the self-approval.
            List<DecisionOutcome> refused = outcomes.stream().filter(o -> !o.ok()).toList();
            assertEquals(1, refused.size(), "one bad row must not discard the batch (§6.2)");
            assertEquals(ATTEMPT_SELF, refused.get(0).attemptUuid());
            assertTrue(selfApprovalRefusal(refused.get(0)), refused.get(0).message());

            // The other two are decided, and an ok outcome carries no message to render.
            assertTrue(outcomes.get(0).ok());
            assertTrue(outcomes.get(2).ok());
            assertNull(outcomes.get(0).message());
            assertNull(outcomes.get(2).message());

            // Two ledger rows, not three: the refusal wrote nothing.
            assertEquals(2, ledger.constructed().size());
            CompetenceAttemptDecision first = ledger.constructed().get(0);
            CompetenceAttemptDecision second = ledger.constructed().get(1);
            verify(first).setAttemptUuid(ATTEMPT_A);
            verify(second).setAttemptUuid(ATTEMPT_B);
            for (CompetenceAttemptDecision row : ledger.constructed()) {
                verify(row).setDecision(DecisionType.APPROVED);
                // The ledger names who attested — the actor, never the subject.
                verify(row).setActorUuid(ACTOR);
                verify(row, never()).setActorUuid(SUBJECT_A);
                verify(row, never()).setActorUuid(SUBJECT_B);
                verify(row).setDecidedAt(any(LocalDateTime.class));
                verify(row).persist();
            }
        }
    }

    @Test
    void decide_bulkWithUnknownAndUndecidableRows_stillDecidesTheGoodOne() {
        CompetenceAttempt inProgress = attempt(ATTEMPT_B, SUBJECT_B);
        inProgress.setSubmittedAt(null);
        inProgress.setPassed(null);

        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid("no-such-attempt")).thenReturn(null);
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_B)).thenReturn(inProgress);
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A))
                    .thenReturn(passedAttempt(ATTEMPT_A, SUBJECT_A));

            List<DecisionOutcome> outcomes = service.decide(
                    Arrays.asList("no-such-attempt", ATTEMPT_B, ATTEMPT_A),
                    DecisionType.APPROVED, null, ACTOR);

            assertEquals(3, outcomes.size());
            assertFalse(outcomes.get(0).ok(), "an unknown attempt is refused, not thrown");
            assertFalse(outcomes.get(1).ok(), "an unsubmitted attempt cannot be approved");
            assertTrue(outcomes.get(2).ok());
            assertEquals(1, ledger.constructed().size());
            verify(ledger.constructed().get(0)).setAttemptUuid(ATTEMPT_A);
        }
    }

    @Test
    void decide_repeatedUuid_isDecidedOnce() {
        // A double-clicked bulk button must not append the same verdict twice — the ledger is
        // append-only, so a duplicate row cannot be cleaned up afterwards.
        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A))
                    .thenReturn(passedAttempt(ATTEMPT_A, SUBJECT_A));

            List<DecisionOutcome> outcomes = service.decide(
                    List.of(ATTEMPT_A, ATTEMPT_A, ATTEMPT_A), DecisionType.APPROVED, null, ACTOR);

            assertEquals(1, outcomes.size());
            assertTrue(outcomes.get(0).ok());
            assertEquals(1, ledger.constructed().size());
        }
    }

    @Test
    void decide_abandonedOrFailedAttempt_isRefusedPerItem() {
        CompetenceAttempt failed = passedAttempt(ATTEMPT_A, SUBJECT_A);
        failed.setPassed(false);
        CompetenceAttempt abandoned = passedAttempt(ATTEMPT_B, SUBJECT_B);
        abandoned.setAbandoned(true);

        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A)).thenReturn(failed);
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_B)).thenReturn(abandoned);

            List<DecisionOutcome> outcomes = service.decide(
                    List.of(ATTEMPT_A, ATTEMPT_B), DecisionType.APPROVED, null, ACTOR);

            assertEquals(2, outcomes.size());
            assertFalse(outcomes.get(0).ok(), "a failed attempt is not approvable");
            assertFalse(outcomes.get(1).ok(), "an abandoned attempt is not approvable");
            assertTrue(ledger.constructed().isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // revocation notes and request-level validation
    // -----------------------------------------------------------------------

    @Test
    void decide_revocationWithoutNote_isRefusedPerItemWhileApprovalNeedsNone() {
        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A))
                    .thenReturn(passedAttempt(ATTEMPT_A, SUBJECT_A));

            // A revocation takes something away from a person, so the ledger records why.
            List<DecisionOutcome> blank = service.decide(
                    List.of(ATTEMPT_A), DecisionType.REVOKED, "   ", ACTOR);
            assertEquals(1, blank.size());
            assertFalse(blank.get(0).ok(), "whitespace is not a reason");
            assertTrue(ledger.constructed().isEmpty());

            List<DecisionOutcome> missing = service.decide(
                    List.of(ATTEMPT_A), DecisionType.REVOKED, null, ACTOR);
            assertFalse(missing.get(0).ok());
            assertTrue(ledger.constructed().isEmpty());

            // An approval may stand on the score alone.
            List<DecisionOutcome> approved = service.decide(
                    List.of(ATTEMPT_A), DecisionType.APPROVED, null, ACTOR);
            assertTrue(approved.get(0).ok());
            assertEquals(1, ledger.constructed().size());
            verify(ledger.constructed().get(0)).setNote(null);
        }
    }

    @Test
    void decide_revocationWithNote_appendsTheTrimmedNote() {
        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A))
                    .thenReturn(passedAttempt(ATTEMPT_A, SUBJECT_A));

            List<DecisionOutcome> outcomes = service.decide(
                    List.of(ATTEMPT_A), DecisionType.REVOKED, "  forkert godkendt  ", ACTOR);

            assertTrue(outcomes.get(0).ok());
            assertEquals(1, ledger.constructed().size());
            verify(ledger.constructed().get(0)).setDecision(DecisionType.REVOKED);
            verify(ledger.constructed().get(0)).setNote("forkert godkendt");
        }
    }

    @Test
    void decide_noAttemptsSupplied_isBadRequest() {
        // Refused up front rather than returning an empty list: an empty batch is a client bug,
        // and there is deliberately no "approve everything pending" wildcard (§5.10).
        assertBadRequest(() -> service.decide(null, DecisionType.APPROVED, null, ACTOR));
        assertBadRequest(() -> service.decide(List.of(), DecisionType.APPROVED, null, ACTOR));
        assertBadRequest(() -> service.decide(Collections.emptyList(), DecisionType.APPROVED, null, ACTOR));
    }

    @Test
    void decide_noDecisionSupplied_isBadRequest() {
        assertBadRequest(() -> service.decide(List.of(ATTEMPT_A), null, null, ACTOR));
    }

    @Test
    void decide_overlongNote_isBadRequestBeforeAnythingIsWritten() {
        // The column is VARCHAR(1000). Because decide() is transactional, letting the INSERT
        // fail mid-loop would roll back the items the caller was already told had succeeded.
        String tooLong = "x".repeat(CompetenceDecisionService.NOTE_MAX_LENGTH + 1);

        try (MockedStatic<CompetenceAttempt> attempts = mockStatic(CompetenceAttempt.class);
             MockedConstruction<CompetenceAttemptDecision> ledger =
                     mockConstruction(CompetenceAttemptDecision.class)) {
            attempts.when(() -> CompetenceAttempt.findByUuid(ATTEMPT_A))
                    .thenReturn(passedAttempt(ATTEMPT_A, SUBJECT_A));

            assertBadRequest(() -> service.decide(
                    List.of(ATTEMPT_A), DecisionType.REVOKED, tooLong, ACTOR));
            assertTrue(ledger.constructed().isEmpty());
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** Grants the actor a bounded TEAM reach over exactly these subjects. */
    private void reachOver(String... subjects) {
        when(authorizationService.resolveReach(
                eq(ACTOR), eq(CompetenceDecisionService.PERMISSION_APPROVE),
                any(LocalDate.class), anySet()))
                .thenReturn(ScopeResolution.bounded(DataScope.TEAM, Set.of(subjects)));
    }

    private static CompetenceAttempt attempt(String uuid, String useruuid) {
        CompetenceAttempt attempt = new CompetenceAttempt();
        attempt.setUuid(uuid);
        attempt.setUseruuid(useruuid);
        attempt.setRequirementUuid("requirement-1");
        attempt.setKref("K1");
        attempt.setVersionLabel("v1.0");
        attempt.setStartedAt(LocalDateTime.now().minusHours(2));
        return attempt;
    }

    /** A submitted, passed, non-abandoned attempt — the only shape the queue admits. */
    private static CompetenceAttempt passedAttempt(String uuid, String useruuid) {
        CompetenceAttempt attempt = attempt(uuid, useruuid);
        attempt.setSubmittedAt(LocalDateTime.now().minusHours(1));
        attempt.setPassed(true);
        return attempt;
    }

    /**
     * Whether this outcome is the funktionsadskillelse refusal rather than one of the other
     * per-item refusals. Matched on the meaning rather than the exact sentence so that
     * rewording the user-facing text does not fail the test, while turning it into a scope
     * miss or a state error does.
     */
    private static boolean selfApprovalRefusal(DecisionOutcome outcome) {
        String message = outcome.message();
        return message != null && message.toLowerCase().contains("own attempt");
    }

    private static void assertBadRequest(Executable call) {
        WebApplicationException ex = assertThrows(WebApplicationException.class, call);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getResponse().getStatus());
        assertNotNull(ex.getMessage(), "a WebApplicationException built from a Response entity "
                + "loses the entity — the message must come from the String constructor");
    }
}
