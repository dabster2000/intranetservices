package dk.trustworks.intranet.recruitmentservice.security;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import dk.trustworks.intranet.recruitmentservice.dto.ApplicationCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.resources.RecruitmentApplicationResource;
import dk.trustworks.intranet.recruitmentservice.resources.RecruitmentResource;
import dk.trustworks.intranet.recruitmentservice.services.CandidateService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentApplicationService;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two structural properties change B depends on, pinned where a
 * behavioural test cannot reach them without a database.
 *
 * <h3>B5 — the attach path resolves the candidate under the visibility rule</h3>
 * Partner secrecy for a candidate is <em>derived</em> from their applications
 * ({@code partnerTrackOnlyCandidateUuids} hides a candidate only while ALL of
 * them sit on PARTNER positions), so one non-partner application permanently
 * de-cloaks a confidential candidate for every viewer. The position gates on
 * the attach endpoint cannot stop that — the caller owns the position in that
 * scenario. Only a candidate gate can, and until change A the sole gate was
 * the BFF's role array, which change A replaces with a permission that team
 * leads hold. A regression here is silent and irreversible, hence a ratchet.
 *
 * <h3>B2 — a failed attach rolls the candidate back</h3>
 * The atomic create is safe only because both halves are {@code @Transactional}
 * at REQUIRED propagation, so the inner attach <em>joins</em> the outer
 * transaction. Moving the composition up into the resource — which carries no
 * transaction — would commit the candidate and then fail the attach, leaving a
 * stranded row. These assertions fail the build if that boundary moves.
 */
class RecruitmentAtomicCreateStructureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .importClasses(RecruitmentApplicationResource.class, RecruitmentResource.class);

    // ---- B5 ---------------------------------------------------------------------

    @Test
    void attachEndpoint_resolvesTheCandidateThroughTheVisibilityGate() {
        Set<String> callees = calleesOf(RecruitmentApplicationResource.class, "create",
                UUID.class, ApplicationCreateRequest.class);

        assertTrue(callees.contains("RecruitmentApplicationResource#requireVisibleCandidate"),
                "POST /candidates/{uuid}/applications must resolve the candidate through "
                        + "requireVisibleCandidate — attaching is how a confidential partner "
                        + "candidate gets permanently de-cloaked; actual callees: " + callees);
        assertFalse(callees.contains("RecruitmentApplicationResource#requireCandidate"),
                "the attach path must NOT use the existence-only lookup: the candidate uuid "
                        + "is caller-supplied there");
    }

    @Test
    void perCandidateListing_alsoResolvesThroughTheVisibilityGate() {
        Set<String> callees = calleesOf(RecruitmentApplicationResource.class,
                "listForCandidate", UUID.class);

        assertTrue(callees.contains("RecruitmentApplicationResource#requireVisibleCandidate"),
                "GET /candidates/{uuid}/applications takes a caller-supplied candidate uuid, "
                        + "so it must answer 404 for an invisible candidate rather than "
                        + "confirming they exist; actual callees: " + callees);
    }

    @Test
    void theVisibilityGateActuallyAsksTheVisibilityRule() {
        // Guards against the gate being renamed into existence but left hollow.
        Set<String> callees = calleesOf(RecruitmentApplicationResource.class,
                "requireVisibleCandidate", UUID.class, UUID.class);

        assertTrue(callees.contains("RecruitmentVisibility#canReadCandidateProfile"),
                "requireVisibleCandidate must consult RecruitmentVisibility.canReadCandidateProfile; "
                        + "actual callees: " + callees);
    }

    // ---- A2 / A3 gate wiring on the create endpoint ------------------------------

    @Test
    void createCandidate_runsThePerUserGates() {
        Set<String> callees = calleesOf(RecruitmentResource.class, "createCandidate",
                CandidateRequest.class);

        assertTrue(callees.contains("RecruitmentVisibility#canCreateCandidate"),
                "@RolesAllowed gates the API client, not the person — the resource must call "
                        + "canCreateCandidate explicitly; actual callees: " + callees);
        assertTrue(callees.contains("RecruitmentVisibility#isRecruiterTier"),
                "the A3 dossier gate must be present on the create path; actual callees: " + callees);
        assertTrue(callees.contains("RecruitmentPositionAccess#requireDecidablePosition"),
                "the position leg must go through the shared 404-then-403 guard; "
                        + "actual callees: " + callees);
    }

    // ---- B2 ---------------------------------------------------------------------

    @Test
    void bothHalvesOfTheAtomicCreate_joinOneTransaction() throws Exception {
        Method create = CandidateService.class.getMethod(
                "createCandidate", CandidateRequest.class, UUID.class, RecruitmentPosition.class);
        Method attach = RecruitmentApplicationService.class.getMethod(
                "create", RecruitmentCandidate.class, RecruitmentPosition.class, UUID.class);

        Transactional outer = create.getAnnotation(Transactional.class);
        Transactional inner = attach.getAnnotation(Transactional.class);

        assertNotNull(outer, "CandidateService.createCandidate(req, actor, position) must be "
                + "@Transactional — it is the outer transaction the attach joins");
        assertNotNull(inner, "RecruitmentApplicationService.create must stay @Transactional");
        assertEquals(Transactional.TxType.REQUIRED, outer.value(),
                "REQUIRED is what makes the inner call join rather than start its own "
                        + "transaction; REQUIRES_NEW would commit the attach independently");
        assertEquals(Transactional.TxType.REQUIRED, inner.value(),
                "REQUIRES_NEW here would survive the outer rollback and strand an application");
    }

    @Test
    void theResourceHoldsNoTransaction_soCompositionCannotMoveUpThere() throws Exception {
        Method endpoint = RecruitmentResource.class.getMethod(
                "createCandidate", CandidateRequest.class);

        assertNull(endpoint.getAnnotation(Transactional.class),
                "RecruitmentResource.createCandidate is not transactional — composing the "
                        + "candidate create and the attach here would commit the candidate and "
                        + "then fail the attach, which is exactly the stranded row B2 avoids");
        assertNull(RecruitmentResource.class.getAnnotation(Transactional.class),
                "nor may the class carry one");
    }

    @Test
    void theResourceDelegatesTheAttach_ratherThanDoingItItself() {
        Set<String> callees = calleesOf(RecruitmentResource.class, "createCandidate",
                CandidateRequest.class);

        assertTrue(callees.contains("CandidateService#createCandidate"),
                "the resource delegates to CandidateService; actual callees: " + callees);
        assertFalse(callees.contains("RecruitmentApplicationService#create"),
                "the resource must not call RecruitmentApplicationService.create directly — "
                        + "outside a transaction that is the stranded-candidate bug; "
                        + "actual callees: " + callees);
    }

    // ---- Helpers ----------------------------------------------------------------

    /**
     * Every method this method calls, as {@code SimpleOwner#name}.
     *
     * <p>Qualified by owner on purpose: a bare name set makes
     * {@code URI.create(...)} indistinguishable from
     * {@code RecruitmentApplicationService.create(...)}, and an assertion
     * that cannot tell those apart is worse than no assertion.
     */
    private static Set<String> calleesOf(Class<?> owner, String methodName, Class<?>... params) {
        JavaMethod method = CLASSES.get(owner).getMethod(methodName, params);
        return method.getMethodCallsFromSelf().stream()
                .map(JavaMethodCall::getTarget)
                .map(target -> target.getOwner().getSimpleName() + "#" + target.getName())
                .collect(Collectors.toSet());
    }
}
