package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.CandidateDedupeService;
import dk.trustworks.intranet.recruitmentservice.services.CandidateService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The per-user gates on {@code POST /recruitment/candidates} (change A).
 *
 * <p>Two things are being pinned here, and both exist because
 * {@code @RolesAllowed} does <b>not</b> gate a person: the BFF's system token
 * carries {@code admin:*} and {@code AdminScopeAugmentor} expands it to every
 * key, so the annotation lets every employee's request through. The only
 * thing that stops a person is an explicit in-resource check.
 * <ol>
 *   <li><b>A2</b> — creation requires {@code canCreateCandidate}: the
 *       recruiter tier, or the narrow {@code recruitment:intake} grant.</li>
 *   <li><b>A3</b> — {@code templateUuid} in the body opens a
 *       {@code CandidateDossier}, i.e. the offer/contract surface, which
 *       go-live decision D17 denies the team lead. An intake-only caller
 *       must be refused <em>before any write</em>, so the assertion is not
 *       merely "403" but "the service was never called".</li>
 * </ol>
 *
 * <p>Database-free: the resource's collaborators are injected fields, so the
 * decision logic is exercised directly with test doubles and no Quarkus boot.
 */
class RecruitmentCandidateIntakeAuthzTest {

    private static final UUID ACTOR = UUID.randomUUID();

    private RecruitmentResource resource;
    private RecruitmentVisibility visibility;
    private CandidateService candidateService;
    private RecruitmentFeatureFlag featureFlag;
    private RequestHeaderHolder headers;

    @BeforeEach
    void setUp() {
        visibility = mock(RecruitmentVisibility.class);
        candidateService = mock(CandidateService.class);
        featureFlag = mock(RecruitmentFeatureFlag.class);
        headers = new RequestHeaderHolder();
        headers.setUserUuid(ACTOR.toString());

        resource = new RecruitmentResource();
        resource.visibility = visibility;
        resource.candidateService = candidateService;
        resource.featureFlag = featureFlag;
        resource.requestHeaderHolder = headers;
        resource.scopeContext = mock(ScopeContext.class);
        resource.dedupeService = mock(CandidateDedupeService.class);

        when(featureFlag.isEnabled()).thenReturn(true);
    }

    // ---- A2: who may create at all ---------------------------------------------

    @Test
    void plainEmployee_cannotCreateACandidate() {
        when(visibility.canCreateCandidate(ACTOR.toString())).thenReturn(false);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.createCandidate(atsRequest(null, null)));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), thrown.getResponse().getStatus());
        verifyNoInteractions(candidateService);
    }

    @Test
    void intakeGrantHolder_mayCreateAPlainCandidate() {
        when(visibility.canCreateCandidate(ACTOR.toString())).thenReturn(true);
        when(candidateService.createCandidate(any(), eq(ACTOR), isNull()))
                .thenReturn(emptyResponseWithUuid("cand-1"));

        Response response = resource.createCandidate(atsRequest(null, null));

        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
        verify(candidateService).createCandidate(any(), eq(ACTOR), isNull());
    }

    @Test
    void missingRequestedByHeader_is400_notASilentCreate() {
        headers.setUserUuid(null);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.createCandidate(atsRequest(null, null)));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), thrown.getResponse().getStatus());
        verifyNoInteractions(candidateService);
    }

    // ---- A3: the dossier path stays shut to intake-only callers -----------------

    @Test
    void intakeGrantHolder_cannotOpenADossierThroughTemplateUuid() {
        // The exact escalation A3 exists to close: the grant buys intake, and
        // templateUuid would quietly turn that into the contract flow.
        when(visibility.canCreateCandidate(ACTOR.toString())).thenReturn(true);
        when(visibility.isRecruiterTier(ACTOR.toString())).thenReturn(false);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.createCandidate(
                        atsRequest(UUID.randomUUID().toString(), null)));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), thrown.getResponse().getStatus());
        // Before any write: no dossier row, no candidate row, no event.
        verifyNoInteractions(candidateService);
    }

    @Test
    void blankTemplateUuid_isNotTreatedAsADossierRequest() {
        // "" is what an untouched form field sends; it must not 403 a caller
        // who is doing the ordinary ATS create.
        when(visibility.canCreateCandidate(ACTOR.toString())).thenReturn(true);
        when(candidateService.createCandidate(any(), eq(ACTOR), isNull()))
                .thenReturn(emptyResponseWithUuid("cand-2"));

        Response response = resource.createCandidate(atsRequest("   ", null));

        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
    }

    @Test
    void recruiterTier_keepsTheDossierPath() {
        when(visibility.canCreateCandidate(ACTOR.toString())).thenReturn(true);
        when(visibility.isRecruiterTier(ACTOR.toString())).thenReturn(true);
        when(candidateService.createCandidate(any(), eq(ACTOR), isNull()))
                .thenReturn(emptyResponseWithUuid("cand-3"));

        Response response = resource.createCandidate(
                atsRequest(UUID.randomUUID().toString(), null));

        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
    }

    // ---- Ordering: authorization runs before position resolution ----------------

    @Test
    void unauthorizedCaller_isRefusedBeforeAnyPositionLookup() {
        // Belt and braces on the gate order: a caller who may not create must
        // not be able to use this endpoint as a position-existence oracle.
        // The pipeline flag has to be on, because a supplied positionUuid
        // makes this an ATS-expansion surface and enforcePipelineFlag() runs
        // first — with the flag off the honest answer is 404, not 403.
        when(featureFlag.isPipelineEnabled()).thenReturn(true);
        when(visibility.canCreateCandidate(ACTOR.toString())).thenReturn(false);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.createCandidate(atsRequest(null, UUID.randomUUID().toString())));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), thrown.getResponse().getStatus());
        verifyNoInteractions(candidateService);
    }

    // ---- Feature-flag scope of the new position leg ------------------------------

    @Test
    void supplyingAPosition_requiresThePipelineFlag() {
        // The attach is an ATS-expansion surface and must stay dark with the
        // rest of the pipeline: flag off + non-admin caller → 404.
        when(featureFlag.isPipelineEnabled()).thenReturn(false);

        WebApplicationException thrown = assertThrows(WebApplicationException.class,
                () -> resource.createCandidate(atsRequest(null, UUID.randomUUID().toString())));

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), thrown.getResponse().getStatus());
        verifyNoInteractions(candidateService);
    }

    @Test
    void thePositionlessCreate_isUnaffectedByThePipelineFlag() {
        // The regression this guards: the positionless create predates the
        // pipeline flag and is used by the dossier flow, so the new
        // enforcePipelineFlag() must fire ONLY when a position was supplied.
        when(featureFlag.isPipelineEnabled()).thenReturn(false);
        when(visibility.canCreateCandidate(ACTOR.toString())).thenReturn(true);
        when(candidateService.createCandidate(any(), eq(ACTOR), isNull()))
                .thenReturn(emptyResponseWithUuid("cand-4"));

        Response response = resource.createCandidate(atsRequest(null, null));

        assertEquals(Response.Status.CREATED.getStatusCode(), response.getStatus());
    }

    // ---- Helpers ----------------------------------------------------------------

    private static CandidateRequest atsRequest(String templateUuid, String positionUuid) {
        return new CandidateRequest(
                "Jane", "Doe", null, null, null, null, null, null,
                templateUuid,
                dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource.OTHER,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null,
                positionUuid);
    }

    /**
     * A {@link CandidateResponse} with only {@code uuid} set, built through
     * the canonical constructor by reflection so this test keeps compiling
     * when the DTO gains components (it just gained {@code applicationUuid}).
     */
    private static CandidateResponse emptyResponseWithUuid(String uuid) {
        try {
            RecordComponent[] components = CandidateResponse.class.getRecordComponents();
            Class<?>[] types = Arrays.stream(components)
                    .map(RecordComponent::getType)
                    .toArray(Class<?>[]::new);
            Object[] args = new Object[components.length];
            args[0] = uuid;
            return CandidateResponse.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not build a CandidateResponse fixture", e);
        }
    }

    /** Compile-time anchor: the 3-arg create overload B2 introduced exists. */
    @SuppressWarnings("unused")
    private static void overloadExists(CandidateService service, CandidateRequest req,
                                       UUID actor, RecruitmentPosition position) {
        service.createCandidate(req, actor, position);
    }
}
