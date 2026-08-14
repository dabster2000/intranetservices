package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.SchedulingRequestCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SchedulingRequestResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * REST entry points of Method B candidate-option scheduling (plan §8.5).
 * Thin by convention: flag gate → actor resolution → visibility/decision
 * check → delegate to {@link RecruitmentSchedulingService}.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>All mutations require {@code recruitment:write} + decision
 *       rights on the position (the same rule as scheduling a Method A
 *       interview); the panel read requires {@code recruitment:read} and
 *       answers 404 for invisible rows (never 403).</li>
 *   <li>Feature flag {@code dk.trustworks.recruitment.scheduling.methodb.enabled}:
 *       off ⇒ 404 for EVERYONE — no admin bypass; Method B entry points
 *       are absent until the D8 staging soak turns the flag on.</li>
 * </ul>
 */
@JBossLog
@RequestScoped
@Path("/recruitment")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentSchedulingResource {

    static final int MAX_INTERVIEWERS = 10;
    static final int MAX_WINDOW_DAYS = 60;
    static final int DEFAULT_AUTOMATION_DAYS = 14;

    @Inject
    RecruitmentSchedulingFeatureFlag methodBFlag;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentSchedulingService schedulingService;

    // ---- Create -----------------------------------------------------------

    /** Start Method B on an application. */
    @POST
    @Path("/applications/{uuid}/scheduling-requests")
    @RolesAllowed({"recruitment:write"})
    public Response create(@PathParam("uuid") UUID applicationUuid,
                           SchedulingRequestCreateRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        validateCreate(request);
        UUID actor = currentActor();
        RecruitmentApplication application = requireVisibleApplication(applicationUuid, actor);
        requireDecisionRights(positionOf(application), actor);

        int automationDays = request.automationDays() != null
                ? request.automationDays() : DEFAULT_AUTOMATION_DAYS;
        LocalDateTime automationDeadline = LocalDateTime.now().plusDays(automationDays);
        RecruitmentSchedulingRequest row = schedulingService.create(
                application, request, automationDeadline, actor.toString());
        return Response.created(URI.create("/recruitment/scheduling-requests/" + row.getUuid()))
                .entity(schedulingService.aggregate(row))
                .build();
    }

    // ---- Read -------------------------------------------------------------

    /**
     * The Method B flag as a literal boolean — the ONE scheduling endpoint
     * alive while the flag is off, mirroring the AI-flags posture: it
     * reports the flag (so the dialog knows whether to show the method
     * selector) and carries no flag guard of its own. No admin bypass —
     * everyone sees the same value.
     */
    @GET
    @Path("/scheduling/flags")
    public SchedulingFlagsResponse flags() {
        return new SchedulingFlagsResponse(methodBFlag.isMethodBEnabled());
    }

    /** An application's scheduling requests, newest first — the FE panel
     * discovers the active request (and shows the history) through this. */
    @GET
    @Path("/applications/{uuid}/scheduling-requests")
    public List<SchedulingRequestResponse> listForApplication(
            @PathParam("uuid") UUID applicationUuid) {
        enforceFlag();
        UUID actor = currentActor();
        requireVisibleApplication(applicationUuid, actor);
        return schedulingService.aggregatesForApplication(applicationUuid.toString());
    }

    /** The FE panel aggregate. */
    @GET
    @Path("/scheduling-requests/{uuid}")
    public SchedulingRequestResponse get(@PathParam("uuid") UUID requestUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentSchedulingRequest row = requireVisibleRequest(requestUuid, actor);
        return schedulingService.aggregate(row);
    }

    // ---- Commands ---------------------------------------------------------

    /** Stop automation and release everything. */
    @POST
    @Path("/scheduling-requests/{uuid}/cancel")
    @RolesAllowed({"recruitment:write"})
    public SchedulingRequestResponse cancel(@PathParam("uuid") UUID requestUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentSchedulingRequest row = requireDecidableRequest(requestUuid, actor);
        schedulingService.cancel(row, actor.toString());
        return schedulingService.aggregate(row);
    }

    /** Manual takeover: hand the request back to the recruiter. */
    @POST
    @Path("/scheduling-requests/{uuid}/handback")
    @RolesAllowed({"recruitment:write"})
    public SchedulingRequestResponse handback(@PathParam("uuid") UUID requestUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentSchedulingRequest row = requireDecidableRequest(requestUuid, actor);
        schedulingService.handBack(row, actor.toString(), "RECRUITER_TAKEOVER");
        return schedulingService.aggregate(row);
    }

    /** Extend the search window / automation anchor. */
    @POST
    @Path("/scheduling-requests/{uuid}/extend-window")
    @RolesAllowed({"recruitment:write"})
    public SchedulingRequestResponse extendWindow(@PathParam("uuid") UUID requestUuid,
                                                  ExtendWindowRequest body) {
        enforceFlag();
        Objects.requireNonNull(body, "request body must not be null");
        if (body.windowEnd() == null) {
            throw badRequest("windowEnd is required");
        }
        UUID actor = currentActor();
        RecruitmentSchedulingRequest row = requireDecidableRequest(requestUuid, actor);
        schedulingService.extendWindow(row, actor.toString(),
                body.windowEnd(), body.automationDeadline());
        return schedulingService.aggregate(row);
    }

    /** Swap a required interviewer (defaults §29.8). */
    @POST
    @Path("/scheduling-requests/{uuid}/replace-interviewer")
    @RolesAllowed({"recruitment:write"})
    public SchedulingRequestResponse replaceInterviewer(@PathParam("uuid") UUID requestUuid,
                                                        ReplaceInterviewerRequest body) {
        enforceFlag();
        Objects.requireNonNull(body, "request body must not be null");
        if (body.fromUserUuid() == null || body.toUserUuid() == null) {
            throw badRequest("fromUserUuid and toUserUuid are required");
        }
        requireExistingUser(body.toUserUuid());
        UUID actor = currentActor();
        RecruitmentSchedulingRequest row = requireDecidableRequest(requestUuid, actor);
        schedulingService.replaceInterviewer(row, actor.toString(),
                body.fromUserUuid(), body.toUserUuid());
        return schedulingService.aggregate(row);
    }

    /** The D11 review action: release some options, approve the rest. */
    @POST
    @Path("/scheduling-requests/{uuid}/approve-options")
    @RolesAllowed({"recruitment:write"})
    public SchedulingRequestResponse approveOptions(@PathParam("uuid") UUID requestUuid,
                                                    ApproveOptionsRequest body) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentSchedulingRequest row = requireDecidableRequest(requestUuid, actor);
        schedulingService.approveOptions(row, actor.toString(),
                body != null ? body.releaseSlotUuids() : null);
        return schedulingService.aggregate(row);
    }

    // ---- Request bodies ---------------------------------------------------

    /** Envelope of {@code GET /recruitment/scheduling/flags}. */
    public record SchedulingFlagsResponse(boolean methodBEnabled) {
    }

    public record ExtendWindowRequest(LocalDate windowEnd,
                                      LocalDateTime automationDeadline) {
    }

    public record ReplaceInterviewerRequest(String fromUserUuid, String toUserUuid) {
    }

    public record ApproveOptionsRequest(List<String> releaseSlotUuids) {
    }

    // ---- Validation -------------------------------------------------------

    private void validateCreate(SchedulingRequestCreateRequest request) {
        if (request.kind() == null) {
            throw badRequest("kind is required — ROUND or INFORMAL");
        }
        if (request.kind() == RecruitmentInterviewKind.ROUND
                && (request.round() == null || request.round() < 1 || request.round() > 3)) {
            throw badRequest("round must be 1..3 for ROUND interviews");
        }
        if (request.kind() == RecruitmentInterviewKind.INFORMAL && request.round() != null) {
            throw badRequest("round must be null for INFORMAL interviews");
        }
        if (request.interviewerUuids() == null || request.interviewerUuids().isEmpty()
                || request.interviewerUuids().size() > MAX_INTERVIEWERS) {
            throw badRequest("interviewerUuids must name 1–" + MAX_INTERVIEWERS + " interviewers");
        }
        Set<String> seen = new HashSet<>(request.interviewerUuids());
        if (seen.size() < request.interviewerUuids().size()) {
            throw badRequest("interviewerUuids contains duplicates");
        }
        request.interviewerUuids().forEach(this::requireExistingUser);
        if (request.optionalInterviewerUuids() != null) {
            if (request.optionalInterviewerUuids().size() > MAX_INTERVIEWERS) {
                throw badRequest("optionalInterviewerUuids must name at most "
                        + MAX_INTERVIEWERS + " interviewers");
            }
            for (String optional : request.optionalInterviewerUuids()) {
                requireExistingUser(optional);
                if (seen.contains(optional)) {
                    throw badRequest("optionalInterviewerUuids overlaps interviewerUuids");
                }
            }
        }
        if (request.durationMinutes() != null
                && (request.durationMinutes() < 15 || request.durationMinutes() > 480)) {
            throw badRequest("durationMinutes must be 15..480");
        }
        if (request.requestedOptions() != null
                && (request.requestedOptions() < 1 || request.requestedOptions() > 3)) {
            throw badRequest("requestedOptions must be 1..3");
        }
        if (request.windowStart() == null || request.windowEnd() == null) {
            throw badRequest("windowStart and windowEnd are required");
        }
        if (request.windowEnd().isBefore(request.windowStart())) {
            throw badRequest("windowEnd must not be before windowStart");
        }
        if (request.windowEnd().isBefore(LocalDate.now())) {
            throw badRequest("windowEnd is in the past");
        }
        if (request.windowStart().plusDays(MAX_WINDOW_DAYS).isBefore(request.windowEnd())) {
            throw badRequest("the window may span at most " + MAX_WINDOW_DAYS + " days");
        }
        if (request.permittedStart() != null && request.permittedEnd() != null
                && !request.permittedStart().isBefore(request.permittedEnd())) {
            throw badRequest("permittedStart must be before permittedEnd");
        }
        if (request.minSeparationHours() != null
                && (request.minSeparationHours() < 0 || request.minSeparationHours() > 168)) {
            throw badRequest("minSeparationHours must be 0..168");
        }
        if (request.candidateDeadline() != null
                && request.candidateDeadline().isBefore(LocalDateTime.now())) {
            throw badRequest("candidateDeadline is in the past");
        }
        if (request.automationDays() != null
                && (request.automationDays() < 1 || request.automationDays() > 60)) {
            throw badRequest("automationDays must be 1..60");
        }
        if (request.location() != null && request.location().length() > 200) {
            throw badRequest("location must be at most 200 characters");
        }
    }

    private void requireExistingUser(String userUuid) {
        if (userUuid == null || User.findById(userUuid) == null) {
            throw badRequest("Unknown interviewer: " + userUuid);
        }
    }

    // ---- Plumbing ---------------------------------------------------------

    private RecruitmentApplication requireVisibleApplication(UUID applicationUuid, UUID viewer) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(applicationUuid.toString());
        if (application == null
                || !visibility.canReadApplication(viewer.toString(), application)) {
            throw new NotFoundException("Application not found: " + applicationUuid);
        }
        return application;
    }

    private RecruitmentSchedulingRequest requireVisibleRequest(UUID requestUuid, UUID viewer) {
        RecruitmentSchedulingRequest row =
                RecruitmentSchedulingRequest.findById(requestUuid.toString());
        if (row == null) {
            throw new NotFoundException("Scheduling request not found: " + requestUuid);
        }
        RecruitmentApplication application =
                RecruitmentApplication.findById(row.getApplicationUuid());
        if (application == null
                || !visibility.canReadApplication(viewer.toString(), application)) {
            throw new NotFoundException("Scheduling request not found: " + requestUuid);
        }
        return row;
    }

    /** Visible AND the caller holds decision rights on the position. */
    private RecruitmentSchedulingRequest requireDecidableRequest(UUID requestUuid, UUID viewer) {
        RecruitmentSchedulingRequest row = requireVisibleRequest(requestUuid, viewer);
        RecruitmentApplication application =
                RecruitmentApplication.findById(row.getApplicationUuid());
        requireDecisionRights(positionOf(application), viewer);
        return row;
    }

    private RecruitmentPosition positionOf(RecruitmentApplication application) {
        RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
        if (position == null) {
            throw new NotFoundException("Position not found: " + application.getPositionUuid());
        }
        return position;
    }

    private void requireDecisionRights(RecruitmentPosition position, UUID actor) {
        if (!visibility.canDecideOnApplication(actor.toString(), position)) {
            throw new WebApplicationException(
                    "Only the recruiter, the hiring owner or the position's teamlead may manage scheduling",
                    Response.Status.FORBIDDEN);
        }
    }

    private void enforceFlag() {
        if (!methodBFlag.isMethodBEnabled()) {
            // Deliberately no admin bypass: Method B entry points are
            // absent until the flag turns on (flag javadoc, D8).
            throw new NotFoundException("Resource not found");
        }
    }

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

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }
}
