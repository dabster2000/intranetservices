package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.CandidateInterviewsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.DebriefResponse;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewResponse;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewScheduleRequest;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewScorecardsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.MyInterviewsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ScorecardSubmitRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentInterviewService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * REST entry point for the interview loop (ATS plan §P11, spec §5.3/§6.2):
 * interview scheduling, blind scorecards, debrief and the interviewer's own
 * list. Thin by convention: flag gate → actor resolution →
 * visibility/decision check → delegate to
 * {@link RecruitmentInterviewService}.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Scheduling mutations require {@code recruitment:write} + decision
 *       rights on the position; scorecard submission and the "mine" list
 *       require {@code recruitment:interview} (the interviewer scope, P1) —
 *       the caller must be an ASSIGNED interviewer (per-candidate
 *       assignment, spec §7.2), enforced in the service.</li>
 *   <li>Reads answer 404 for invisible rows (never 403). An assigned
 *       interviewer may read their interview's surfaces even without
 *       position visibility — the P11 interviewer grant.</li>
 *   <li>Feature flag {@code recruitment.interviews.enabled} (core flag 2):
 *       off + non-admin caller → 404, same convention as the sibling
 *       resources; admins bypass for dark testing.</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentInterviewResource {

    private static final String ADMIN_WILDCARD = "admin:*";
    private static final int LOCATION_MAX_LENGTH = 200;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentInterviewService interviewService;

    // ---- Scheduling ------------------------------------------------------------

    /** Create (= schedule) an interview on an application. */
    @POST
    @Path("/applications/{uuid}/interviews")
    @RolesAllowed({"recruitment:write"})
    public Response create(@PathParam("uuid") UUID applicationUuid,
                           InterviewCreateRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        if (request.kind() == null) {
            throw badRequest("kind is required — ROUND or INFORMAL");
        }
        if (request.scheduledAt() == null) {
            throw badRequest("scheduledAt is required — interviews are scheduled with a time");
        }
        requireLocationWithinLimit(request.location());
        UUID actor = currentActor();
        RecruitmentApplication application = requireVisibleApplication(applicationUuid, actor);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);
        RecruitmentCandidate candidate = requireCandidate(application.getCandidateUuid());

        RecruitmentInterview interview =
                interviewService.create(application, position, candidate, request, actor);
        return Response.created(URI.create("/recruitment/interviews/" + interview.getUuid()))
                .entity(interviewService.scorecardsFor(actor.toString(), interview,
                        application, position))
                .build();
    }

    /** Reschedule (new time mandatory; location/interviewers optional). */
    @POST
    @Path("/interviews/{uuid}/schedule")
    @RolesAllowed({"recruitment:write"})
    public InterviewScorecardsResponse reschedule(@PathParam("uuid") UUID interviewUuid,
                                                  InterviewScheduleRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        if (request.scheduledAt() == null) {
            throw badRequest("scheduledAt is required");
        }
        requireLocationWithinLimit(request.location());
        UUID actor = currentActor();
        RecruitmentInterview interview = requireVisibleInterview(interviewUuid, actor);
        RecruitmentApplication application = applicationOf(interview);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);
        RecruitmentCandidate candidate = requireCandidate(application.getCandidateUuid());

        interviewService.reschedule(interview, application, position, candidate, request, actor);
        return interviewService.scorecardsFor(actor.toString(), interview, application, position);
    }

    /** Cancel — the Outlook event (when synced) is cancelled too. */
    @POST
    @Path("/interviews/{uuid}/cancel")
    @RolesAllowed({"recruitment:write"})
    public Response cancel(@PathParam("uuid") UUID interviewUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentInterview interview = requireVisibleInterview(interviewUuid, actor);
        RecruitmentApplication application = applicationOf(interview);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);

        interviewService.cancel(interview, application, position, actor);
        return Response.noContent().build();
    }

    // ---- Scorecards ---------------------------------------------------------------

    /** Submit the caller's OWN blind scorecard (assigned interviewers only). */
    @POST
    @Path("/interviews/{uuid}/scorecards")
    @RolesAllowed({"recruitment:interview"})
    public InterviewScorecardsResponse submitScorecard(@PathParam("uuid") UUID interviewUuid,
                                                       ScorecardSubmitRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        UUID actor = currentActor();
        RecruitmentInterview interview = requireVisibleInterview(interviewUuid, actor);
        RecruitmentApplication application = applicationOf(interview);
        RecruitmentPosition position = positionOf(application);

        interviewService.submitScorecard(interview, application, position, request, actor);
        return interviewService.scorecardsFor(actor.toString(), interview, application, position);
    }

    /** The blind-filtered scorecard view of one interview. */
    @GET
    @Path("/interviews/{uuid}/scorecards")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public InterviewScorecardsResponse scorecards(@PathParam("uuid") UUID interviewUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentInterview interview = requireVisibleInterview(interviewUuid, actor);
        RecruitmentApplication application = applicationOf(interview);
        RecruitmentPosition position = positionOf(application);
        return interviewService.scorecardsFor(actor.toString(), interview, application, position);
    }

    /** The per-round debrief for an application (side-by-side unlock rules inside). */
    @GET
    @Path("/applications/{uuid}/debrief")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public DebriefResponse debrief(@PathParam("uuid") UUID applicationUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentApplication application = requireApplicationForInterviewRead(applicationUuid, actor);
        RecruitmentPosition position = positionOf(application);
        return interviewService.debrief(actor.toString(), application, position);
    }

    // ---- Lists ----------------------------------------------------------------------

    /** All interviews across the candidate's applications the caller may see. */
    @GET
    @Path("/candidates/{uuid}/interviews")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public CandidateInterviewsResponse listForCandidate(@PathParam("uuid") UUID candidateUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null
                || !visibility.canReadCandidateProfile(actor.toString(), candidate)) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return interviewService.listForCandidate(actor.toString(), candidate);
    }

    /** The caller's own upcoming + recent interviews with the kit. */
    @GET
    @Path("/interviews/mine")
    @RolesAllowed({"recruitment:interview"})
    public MyInterviewsResponse mine() {
        enforceFlag();
        UUID actor = currentActor();
        return interviewService.listMine(actor.toString());
    }

    // ---- Helpers ----------------------------------------------------------------------

    private RecruitmentCandidate requireCandidate(String candidateUuid) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null) {
            // FK makes this unreachable; defensive all the same.
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return candidate;
    }

    /**
     * Resolve the application and apply the per-viewer visibility rule
     * (position semantics — same helper as the sibling application
     * resource): an existing-but-invisible application answers the same
     * 404 as a nonexistent one.
     */
    private RecruitmentApplication requireVisibleApplication(UUID applicationUuid, UUID viewer) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(applicationUuid.toString());
        if (application == null
                || !visibility.canReadApplication(viewer.toString(), application)) {
            throw new NotFoundException("Application not found: " + applicationUuid);
        }
        return application;
    }

    /**
     * Resolve an interview the viewer may access: assigned interviewer OR
     * position-visible application. Invisible rows answer the same 404 as
     * nonexistent ones.
     */
    private RecruitmentInterview requireVisibleInterview(UUID interviewUuid, UUID viewer) {
        RecruitmentInterview interview = RecruitmentInterview.findById(interviewUuid.toString());
        if (interview == null) {
            throw new NotFoundException("Interview not found: " + interviewUuid);
        }
        RecruitmentApplication application = applicationOf(interview);
        if (!interviewService.canAccessInterview(viewer.toString(), interview, application)) {
            throw new NotFoundException("Interview not found: " + interviewUuid);
        }
        return interview;
    }

    /**
     * Resolve the application for interview-read surfaces (debrief): the
     * position rule OR an interview assignment on this application.
     */
    private RecruitmentApplication requireApplicationForInterviewRead(UUID applicationUuid,
                                                                      UUID viewer) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(applicationUuid.toString());
        if (application == null) {
            throw new NotFoundException("Application not found: " + applicationUuid);
        }
        if (visibility.canReadApplication(viewer.toString(), application)) {
            return application;
        }
        boolean assignedOnAny = RecruitmentInterview
                .<RecruitmentInterview>list("applicationUuid", application.getUuid())
                .stream()
                .anyMatch(i -> i.isActive() && i.isAssigned(viewer.toString()));
        if (!assignedOnAny) {
            throw new NotFoundException("Application not found: " + applicationUuid);
        }
        return application;
    }

    private RecruitmentApplication applicationOf(RecruitmentInterview interview) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(interview.getApplicationUuid());
        if (application == null) {
            // FK makes this unreachable; defensive all the same.
            throw new NotFoundException("Application not found: " + interview.getApplicationUuid());
        }
        return application;
    }

    private RecruitmentPosition positionOf(RecruitmentApplication application) {
        RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
        if (position == null) {
            throw new NotFoundException("Position not found: " + application.getPositionUuid());
        }
        return position;
    }

    /**
     * Scheduling mutations require decision rights on the position (spec
     * §7.2) — same tier as stage moves: admin/recruiter everywhere,
     * teamlead/hiring owner on their own positions, circle OWNER/RECRUITER
     * on partner track. Assigned interviewers do NOT reschedule.
     */
    private void requireDecisionRights(RecruitmentPosition position, UUID actor) {
        if (!visibility.canDecideOnApplication(actor.toString(), position)) {
            throw new WebApplicationException(
                    "Only the recruiter, the hiring owner or the position's teamlead may schedule interviews",
                    Response.Status.FORBIDDEN);
        }
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }

    /** Explicit server-side cap — bean validation is inert (house rule). */
    private static void requireLocationWithinLimit(String location) {
        if (location != null && location.length() > LOCATION_MAX_LENGTH) {
            throw badRequest("location must be at most " + LOCATION_MAX_LENGTH + " characters");
        }
    }

    /**
     * Block the request when {@code recruitment.interviews.enabled} is off,
     * unless the caller holds {@code admin:*} (core-flag admin bypass —
     * dark testing). 404, not 503: the feature's existence must not leak.
     */
    private void enforceFlag() {
        if (featureFlag.isInterviewsEnabled()) {
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
