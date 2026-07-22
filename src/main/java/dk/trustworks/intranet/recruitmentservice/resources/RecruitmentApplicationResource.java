package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.ApplicationCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ApplicationListResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ApplicationRejectRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ApplicationResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ApplicationStageRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ApplicationWithdrawRequest;
import dk.trustworks.intranet.recruitmentservice.dto.AssignTeamRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ExpectedStartDateRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentApplicationService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST entry point for recruitment applications (ATS plan §P4, spec §6.2)
 * — the pipeline data model's command surface. Thin by convention: flag
 * gate → actor resolution → visibility/decision check → delegate to
 * {@link RecruitmentApplicationService}.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Class-level {@code @RolesAllowed({"recruitment:read"})}, write
 *       methods override with {@code recruitment:write} — client-level
 *       scopes; the BFF holds {@code admin:*}.</li>
 *   <li>Per-user rules key on the {@code X-Requested-By} user via
 *       {@link RecruitmentVisibility}: an application is exactly as visible
 *       as its position (partner track = circle-gated), reads answer 404
 *       for invisible rows (never 403 — existence must not leak), and
 *       decisions require {@code canDecideOnApplication} (practice leads
 *       read but never decide, spec §7.2).</li>
 *   <li>Feature flag {@code recruitment.pipeline.enabled}: off + non-admin
 *       caller → 404 (same convention as the sibling resources).</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentApplicationResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentApplicationService applicationService;

    // ---- Per-candidate collection ------------------------------------------------

    /**
     * The candidate's applications the caller may see, hydrated with
     * position facts. Partner-track applications are absent for non-circle
     * viewers.
     */
    @GET
    @Path("/candidates/{uuid}/applications")
    public ApplicationListResponse listForCandidate(@PathParam("uuid") UUID candidateUuid) {
        enforceFlag();
        UUID actor = currentActor();
        requireCandidate(candidateUuid);
        List<ApplicationResponse> applications =
                applicationService.listForCandidate(actor.toString(), candidateUuid.toString());
        return new ApplicationListResponse(applications, applications.size());
    }

    /** Attach a candidate to a position (the application starts in SCREENING). */
    @POST
    @Path("/candidates/{uuid}/applications")
    @RolesAllowed({"recruitment:write"})
    public Response create(@PathParam("uuid") UUID candidateUuid,
                           @Valid ApplicationCreateRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        // Explicit input checks: the module has no active bean-validation
        // extension, so @Valid alone does not enforce the annotations (house
        // pattern — see the P2/P3 resources).
        if (request.positionUuid() == null || request.positionUuid().isBlank()) {
            throw badRequest("positionUuid is required — an application is always FOR a position");
        }
        UUID actor = currentActor();
        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        // The position must exist AND be visible to the actor — an invisible
        // partner-track position answers the same 404 as a nonexistent one.
        RecruitmentPosition position = requireVisiblePosition(request.positionUuid(), actor);
        requireDecisionRights(position, actor);

        RecruitmentApplication application = applicationService.create(candidate, position, actor);
        return Response.created(URI.create("/recruitment/applications/" + application.getUuid()))
                .entity(applicationService.toResponse(application, position))
                .build();
    }

    // ---- Stage moves ---------------------------------------------------------------

    /**
     * Advance or move back within the position's stage set. Back moves are
     * flagged {@code direction=BACK} on the event; forward skips require
     * recruiter/owner rights; HIRED is never reachable here.
     */
    @POST
    @Path("/applications/{uuid}/stage")
    @RolesAllowed({"recruitment:write"})
    public ApplicationResponse changeStage(@PathParam("uuid") UUID applicationUuid,
                                           @Valid ApplicationStageRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        if (request.stage() == null) {
            throw badRequest("stage is required");
        }
        UUID actor = currentActor();
        RecruitmentApplication application = requireVisibleApplication(applicationUuid, actor);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);
        boolean mayFastTrack = visibility.isRecruiterOrHiringOwner(actor.toString(), position);
        applicationService.changeStage(application, position, request.stage(), mayFastTrack, actor);
        return applicationService.toResponse(application, position);
    }

    // ---- Terminals -----------------------------------------------------------------

    /** Reject with a mandatory coded reason (free-text note → event pii). */
    @POST
    @Path("/applications/{uuid}/reject")
    @RolesAllowed({"recruitment:write"})
    public ApplicationResponse reject(@PathParam("uuid") UUID applicationUuid,
                                      @Valid ApplicationRejectRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        if (request.reasonCode() == null) {
            throw badRequest(
                    "reasonCode is required — pick the closest coded reason; elaborate in the note");
        }
        UUID actor = currentActor();
        RecruitmentApplication application = requireVisibleApplication(applicationUuid, actor);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);
        RecruitmentCandidate candidate = requireCandidate(UUID.fromString(application.getCandidateUuid()));
        boolean isRecruiterOrOwner = visibility.isRecruiterOrHiringOwner(actor.toString(), position);
        applicationService.reject(application, position, candidate, request, isRecruiterOrOwner, actor);
        return applicationService.toResponse(application, position);
    }

    /** The candidate backed out (optional note → event pii). */
    @POST
    @Path("/applications/{uuid}/withdraw")
    @RolesAllowed({"recruitment:write"})
    public ApplicationResponse withdraw(@PathParam("uuid") UUID applicationUuid,
                                        @Valid ApplicationWithdrawRequest request) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentApplication application = requireVisibleApplication(applicationUuid, actor);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);
        RecruitmentCandidate candidate = requireCandidate(UUID.fromString(application.getCandidateUuid()));
        applicationService.withdraw(application, position, candidate,
                request != null ? request.note() : null, actor);
        return applicationService.toResponse(application, position);
    }

    /**
     * Close as silver medalist: candidate pooled ({@code SILVER_MEDALIST})
     * + pool-retention consent requested.
     */
    @POST
    @Path("/applications/{uuid}/return-to-pool")
    @RolesAllowed({"recruitment:write"})
    public ApplicationResponse returnToPool(@PathParam("uuid") UUID applicationUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentApplication application = requireVisibleApplication(applicationUuid, actor);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);
        RecruitmentCandidate candidate = requireCandidate(UUID.fromString(application.getCandidateUuid()));
        applicationService.returnToPool(application, position, candidate, actor);
        return applicationService.toResponse(application, position);
    }

    // ---- Plain updates -------------------------------------------------------------

    /** The offer-stage team decision — any existing team is valid. */
    @POST
    @Path("/applications/{uuid}/assign-team")
    @RolesAllowed({"recruitment:write"})
    public ApplicationResponse assignTeam(@PathParam("uuid") UUID applicationUuid,
                                          @Valid AssignTeamRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        if (request.teamUuid() == null || request.teamUuid().isBlank()) {
            throw badRequest("teamUuid is required");
        }
        UUID actor = currentActor();
        RecruitmentApplication application = requireVisibleApplication(applicationUuid, actor);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);
        applicationService.assignTeam(application, position, request.teamUuid(), actor);
        return applicationService.toResponse(application, position);
    }

    /** Airtable's Ansættelsesdato — typically set at OFFER. */
    @PUT
    @Path("/applications/{uuid}/expected-start-date")
    @RolesAllowed({"recruitment:write"})
    public ApplicationResponse setExpectedStartDate(@PathParam("uuid") UUID applicationUuid,
                                                    @Valid ExpectedStartDateRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        if (request.expectedStartDate() == null) {
            throw badRequest("expectedStartDate is required");
        }
        UUID actor = currentActor();
        RecruitmentApplication application = requireVisibleApplication(applicationUuid, actor);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);
        applicationService.setExpectedStartDate(application, position,
                request.expectedStartDate(), actor);
        return applicationService.toResponse(application, position);
    }

    // ---- Helpers --------------------------------------------------------------------

    private RecruitmentCandidate requireCandidate(UUID candidateUuid) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return candidate;
    }

    /**
     * Resolve the application and apply the per-viewer visibility rule: an
     * existing-but-invisible application (partner-track position, viewer
     * outside the circle) answers the same 404 as a nonexistent one.
     */
    private RecruitmentApplication requireVisibleApplication(UUID applicationUuid, UUID viewer) {
        RecruitmentApplication application = RecruitmentApplication.findById(applicationUuid.toString());
        if (application == null || !visibility.canReadApplication(viewer.toString(), application)) {
            throw new NotFoundException("Application not found: " + applicationUuid);
        }
        return application;
    }

    private RecruitmentPosition requireVisiblePosition(String positionUuid, UUID viewer) {
        RecruitmentPosition position = positionUuid != null
                ? RecruitmentPosition.findById(positionUuid.trim())
                : null;
        if (position == null || !visibility.canReadPosition(viewer.toString(), position)) {
            throw new NotFoundException("Position not found: " + positionUuid);
        }
        return position;
    }

    private RecruitmentPosition positionOf(RecruitmentApplication application) {
        RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
        if (position == null) {
            // FK makes this unreachable; defensive all the same.
            throw new NotFoundException("Position not found: " + application.getPositionUuid());
        }
        return position;
    }

    /**
     * Pipeline decisions require decision rights on the position (spec
     * §7.2): admin/recruiter everywhere, teamlead/hiring owner on their own
     * positions, circle OWNER/RECRUITER on partner track. A practice lead
     * can read but never decide.
     */
    private void requireDecisionRights(RecruitmentPosition position, UUID actor) {
        if (!visibility.canDecideOnApplication(actor.toString(), position)) {
            throw new WebApplicationException(
                    "Only the recruiter, the hiring owner or the position's teamlead may act on this application",
                    Response.Status.FORBIDDEN);
        }
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }

    /**
     * Block the request when {@code recruitment.pipeline.enabled} is off,
     * unless the caller holds {@code admin:*}. 404 (not 503) to avoid
     * leaking the feature's existence — same convention as the sibling
     * recruitment resources.
     */
    private void enforceFlag() {
        if (featureFlag.isPipelineEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    /**
     * Resolve the acting user from {@code X-Requested-By} (set by
     * {@code HeaderInterceptor}). 400 when absent — every rule on this
     * resource is per-user.
     */
    private UUID currentActor() {
        String userUuid = requestHeaderHolder.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By header is required",
                    Response.Status.BAD_REQUEST);
        }
        try {
            return UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "X-Requested-By is not a valid UUID",
                    Response.Status.BAD_REQUEST);
        }
    }
}
