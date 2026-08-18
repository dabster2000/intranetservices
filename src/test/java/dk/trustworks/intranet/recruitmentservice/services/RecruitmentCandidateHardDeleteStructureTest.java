package dk.trustworks.intranet.recruitmentservice.services;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import dk.trustworks.intranet.recruitmentservice.dto.HardDeleteRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.notifications.CandidateDiscussionSlackNotifier;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCardReactor;
import dk.trustworks.intranet.recruitmentservice.resources.RecruitmentResource;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transaction boundaries and call wiring the hard delete depends on,
 * pinned where a behavioural test cannot reach them without a database.
 *
 * <p>Three failures this class exists to prevent, all of them silent:</p>
 * <ol>
 *   <li><b>The orchestrator becoming {@code @Transactional}.</b> It calls
 *       Slack and Microsoft Graph before the cascade and
 *       {@code RecruitmentReportingProjector.rebuild()} after it. Remote I/O
 *       inside a recruitment transaction is the shape of the 2026-08-11
 *       reactor deadlock, and {@code rebuild()} opens its own
 *       {@code QuarkusTransaction.requiringNew()} — from inside an active
 *       transaction that is a second connection held while the first one
 *       waits.</li>
 *   <li><b>The cascade being merged into the orchestrator.</b> A
 *       {@code @Transactional} method called from the same bean bypasses the
 *       interceptor entirely: the cascade would run with no transaction, and
 *       a failure two-thirds of the way through would leave a half-deleted
 *       candidate with no way back.</li>
 *   <li><b>A gate quietly moving after the candidate lookup.</b> The
 *       per-user admin check has to precede it, or the endpoint answers
 *       "which candidates exist" to anyone.</li>
 * </ol>
 */
class RecruitmentCandidateHardDeleteStructureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importClasses(
            RecruitmentResource.class,
            RecruitmentCandidateHardDeleteService.class,
            RecruitmentCandidateDeleteCascade.class);

    // ---- Transaction boundaries -------------------------------------------------

    @Test
    void theCascadeIsTransactional_atRequiredPropagation() throws Exception {
        Method cascade = RecruitmentCandidateDeleteCascade.class.getMethod(
                "deleteCandidate", String.class, String.class);

        Transactional tx = cascade.getAnnotation(Transactional.class);
        assertNotNull(tx, "the whole cascade is one transaction — children, parents, candidate "
                + "and the ledger's COMPLETED stamp commit together or not at all");
        assertEquals(Transactional.TxType.REQUIRED, tx.value());
    }

    /**
     * The ordering the durable record depends on. The ledger row and the
     * record of what was irreversibly done to Microsoft Graph and Slack are
     * each committed in their OWN transaction, before the cascade opens its
     * own — that is the only reason a rolled-back delete leaves a trace at
     * all. Fold either of them into the cascade and the record rolls back
     * with the deletes, which is precisely the bug they exist to close.
     */
    @Test
    void theLedgerIsOpenedAndTheExternalRecordCommittedInTheirOwnTransactions() {
        for (String name : new String[]{"openLedger", "recordExternalRedaction", "markRolledBack"}) {
            Method method = java.util.Arrays.stream(
                            RecruitmentCandidateDeleteCascade.class.getMethods())
                    .filter(m -> m.getName().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing " + name));

            assertNotNull(method.getAnnotation(Transactional.class),
                    name + " must commit on its own — it exists to survive a cascade rollback");
        }
    }

    @Test
    void theOrchestratorIsNotTransactional() throws Exception {
        Method orchestrator = RecruitmentCandidateHardDeleteService.class.getMethod(
                "hardDelete", RecruitmentCandidate.class, String.class, String.class);

        assertNull(orchestrator.getAnnotation(Transactional.class),
                "hardDelete does Slack and Graph I/O before the cascade and triggers a "
                        + "projection rebuild after it — none of that may sit inside a "
                        + "recruitment transaction");
        assertNull(RecruitmentCandidateHardDeleteService.class.getAnnotation(Transactional.class),
                "nor may the class carry one");
    }

    @Test
    void theCascadeLivesInItsOwnBean_soTheInterceptorActuallyFires() {
        assertFalse(RecruitmentCandidateDeleteCascade.class
                        .equals(RecruitmentCandidateHardDeleteService.class),
                "a @Transactional method invoked as a self-call runs with no transaction at "
                        + "all — the two halves must stay two beans");

        Set<String> callees = calleesOf(RecruitmentCandidateHardDeleteService.class, "runDelete",
                String.class, String.class, String.class,
                RecruitmentCandidateHardDeleteService.Preflight.class);
        assertTrue(callees.contains("RecruitmentCandidateDeleteCascade#deleteCandidate"),
                "the orchestrator must delegate the row deletion to the transactional bean; "
                        + "actual callees: " + callees);
    }

    /**
     * The durable record must be opened BEFORE the first irreversible external
     * call and stamped with what those calls did BEFORE the cascade. The order
     * IS the fix, so the order is what is asserted.
     */
    @Test
    void theDurableRecordBracketsTheExternalRedaction() {
        List<String> calls = orderedCalleesOf(RecruitmentCandidateHardDeleteService.class,
                "runDelete", String.class, String.class, String.class,
                RecruitmentCandidateHardDeleteService.Preflight.class);

        int opened = calls.indexOf("RecruitmentCandidateDeleteCascade#openLedger");
        int graph = calls.indexOf("RecruitmentCandidateHardDeleteService#cancelGraphEvents");
        int slack = calls.indexOf("RecruitmentCandidateHardDeleteService#redactSlack");
        int recorded = calls.indexOf("RecruitmentCandidateDeleteCascade#recordExternalRedaction");
        int cascaded = calls.indexOf("RecruitmentCandidateDeleteCascade#deleteCandidate");

        assertTrue(opened >= 0 && graph >= 0 && slack >= 0 && recorded >= 0 && cascaded >= 0,
                "every step must still be there; actual call order: " + calls);
        assertTrue(opened < graph && opened < slack,
                "the ledger row is committed before ANY irreversible external call, or a crash "
                        + "in between leaves cancelled invitations nobody recorded; actual call "
                        + "order: " + calls);
        assertTrue(recorded > graph && recorded > slack,
                "what was redacted can only be recorded after it happened; actual: " + calls);
        assertTrue(recorded < cascaded,
                "and it must be COMMITTED before the transaction that can roll back — otherwise "
                        + "the record dies with the row removals; actual: " + calls);
    }

    /**
     * A cascade failure has to be marked on the surviving row before it
     * propagates. Without this the ledger reads EXTERNAL_REDACTED forever and
     * nobody can tell a crashed delete from one still in flight.
     */
    @Test
    void aCascadeFailureIsRecordedOnTheLedgerBeforeItPropagates() {
        Set<String> callees = calleesOf(RecruitmentCandidateHardDeleteService.class,
                "markRolledBack", String.class, String.class, RuntimeException.class);

        assertTrue(callees.contains("RecruitmentCandidateDeleteCascade#markRolledBack"),
                "actual callees: " + callees);
    }

    @Test
    void theResourceHoldsNoTransaction() throws Exception {
        Method endpoint = RecruitmentResource.class.getMethod(
                "hardDeleteCandidate", UUID.class, HardDeleteRequest.class);

        assertNull(endpoint.getAnnotation(Transactional.class),
                "a transaction opened here would enclose the service's external I/O and its "
                        + "post-commit legs, which is precisely what the split avoids");
    }

    // ---- Gate wiring on the endpoint ---------------------------------------------

    @Test
    void theEndpointRunsThePerUserAdminCheckAndBothBodyGuards() {
        Set<String> callees = calleesOf(RecruitmentResource.class, "hardDeleteCandidate",
                UUID.class, HardDeleteRequest.class);

        assertTrue(callees.contains("RecruitmentVisibility#canHardDeleteCandidate"),
                "@RolesAllowed gates the API client, not the person — the BFF token carries "
                        + "admin:* and AdminScopeAugmentor expands it to every key; "
                        + "actual callees: " + callees);
        assertTrue(callees.contains("RecruitmentResource#requireTypedFullName"),
                "the typed full name is checked server-side, not just in the dialog; "
                        + "actual callees: " + callees);
        assertTrue(callees.contains("RecruitmentResource#requireDeletionReason"),
                "the reason is the only explanation that survives the cascade; "
                        + "actual callees: " + callees);
        assertTrue(callees.contains("RecruitmentResource#requireVisibleCandidate"),
                "the candidate is resolved under the profile-visibility rule, so an invisible "
                        + "one answers 404; actual callees: " + callees);
    }

    // ---- The external and post-commit legs ----------------------------------------

    @Test
    void theOrchestratorRebuildsTheReportingProjection() {
        Set<String> callees = calleesOf(RecruitmentCandidateHardDeleteService.class,
                "rebuildReporting", String.class);

        assertTrue(callees.contains("RecruitmentReportingProjector#rebuild"),
                "rebuild() is the ONLY mechanism that can un-count a deleted candidate — "
                        + "without it the reports Source-mix candidate series stays wrong "
                        + "forever, which is the whole reason this endpoint exists; "
                        + "actual callees: " + callees);
    }

    @Test
    void theSlackRedactionUsesTheForcedPath_notTheLiveRowOne() {
        Set<String> callees = calleesOf(RecruitmentCandidateHardDeleteService.class,
                "redactSlack", String.class,
                RecruitmentCandidateHardDeleteService.Preflight.class, Map.class, Map.class);

        assertTrue(callees.contains("SlackCardReactor#redactRootCardsForHardDelete"),
                "actual callees: " + callees);
        assertTrue(callees.contains(
                        "CandidateDiscussionSlackNotifier#redactDiscussionRootsForHardDelete"),
                "actual callees: " + callees);
        assertFalse(callees.contains("SlackCardReactor#redactRootCards"),
                "redactRootCards re-renders from the LIVE candidate row and only substitutes "
                        + "the placeholder once the status is ANONYMIZED — which a hard-deleted "
                        + "candidate never is. Calling it here would rewrite every card with "
                        + "the person's REAL name and then destroy the rows needed to fix it. "
                        + "actual callees: " + callees);
    }

    @Test
    void theForcedRedactionEntryPointsExistAndArePublic() throws Exception {
        Method cards = SlackCardReactor.class.getMethod(
                "redactRootCardsForHardDelete", String.class);
        Method discussion = CandidateDiscussionSlackNotifier.class.getMethod(
                "redactDiscussionRootsForHardDelete", String.class);

        assertEquals(java.util.List.class, cards.getReturnType(),
                "it returns what it could NOT redact — residue for the ledger, not a boolean");
        assertEquals(java.util.List.class, discussion.getReturnType());
        assertEquals(0, cards.getExceptionTypes().length,
                "Slack trouble must never propagate into a delete that is already half done");
        assertEquals(0, discussion.getExceptionTypes().length);
    }

    // ---- Helpers -----------------------------------------------------------------

    /**
     * Every method this method calls, in source order, as
     * {@code SimpleOwner#name}. Line number is the ordering ArchUnit exposes
     * and it is enough here: the calls being ordered each sit on their own
     * line of one method.
     */
    private static List<String> orderedCalleesOf(Class<?> owner, String methodName,
                                                 Class<?>... params) {
        JavaMethod method = CLASSES.get(owner).getMethod(methodName, params);
        return method.getMethodCallsFromSelf().stream()
                .sorted(java.util.Comparator.comparingInt(JavaMethodCall::getLineNumber))
                .map(JavaMethodCall::getTarget)
                .map(target -> target.getOwner().getSimpleName() + "#" + target.getName())
                .toList();
    }

    /** Every method this method calls, as {@code SimpleOwner#name}. */
    private static Set<String> calleesOf(Class<?> owner, String methodName, Class<?>... params) {
        JavaMethod method = CLASSES.get(owner).getMethod(methodName, params);
        return method.getMethodCallsFromSelf().stream()
                .map(JavaMethodCall::getTarget)
                .map(target -> target.getOwner().getSimpleName() + "#" + target.getName())
                .collect(Collectors.toSet());
    }
}
