package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.ai.InterviewRoomAiService;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewRoomResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomDraftRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomFactRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomLandRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomLandResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomPrepResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomPresenceResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomSuggestRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomSuggestResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomTidyRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomTidyResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentInterviewRoomService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST entry point for the Interview Room (room spec 2026-08-26 §6.2):
 * the one-round-trip read model, draft autosave, presence, the atomic
 * land, live fact capture, and the flagged AI endpoints.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>The per-person key is the interview ASSIGNMENT (spec §7.3 —
 *       "assignment is a per-candidate key, not a standing role"); a
 *       full-profile reader may additionally OPEN the room read-only
 *       (the debrief view). Everything else answers 404, never 403 —
 *       existence must not leak.</li>
 *   <li>Whether the viewer is <em>restricted</em> (brief-scoped shelf, no
 *       compensation, no competition) is decided HERE, server-side, off
 *       {@code canReadCandidateProfile} — the room never guesses at a
 *       rule the backend has already ruled on.</li>
 *   <li>Mutations require the caller to be assigned; compensation facts
 *       additionally require {@code recruitment:comp} (machine-client
 *       guard — the BFF's system client expands to every scope, so the
 *       per-user gate is the BFF role list, exactly as on the notes
 *       route).</li>
 *   <li>Flags: {@code recruitment.interviews.enabled} (core) +
 *       {@code recruitment.interview-room.enabled} (slice 1) with the
 *       404 + {@code admin:*} convention; the AI endpoints additionally
 *       require their own {@code recruitment.ai.interview-room.*} flag.</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:interview"})
public class RecruitmentInterviewRoomResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentAiFeatureFlag aiFlags;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentInterviewRoomService roomService;

    @Inject
    InterviewRoomAiService roomAiService;

    // ---- Read model -----------------------------------------------------------------

    /** The room in one round trip (spec §6.2). */
    @GET
    @Path("/interviews/{uuid}/room")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public InterviewRoomResponse room(@PathParam("uuid") UUID interviewUuid) {
        enforceRoomFlag();
        RoomAccess access = resolveAccess(interviewUuid, currentActor(), false);
        return roomService.room(access.viewer.toString(), access.interview, access.application,
                access.position, access.candidate, access.restricted,
                visibility.isCompTierFor(access.viewer.toString(), List.of(access.position)));
    }

    /** Who is in the room + line counts. Never text (spec §5.2). */
    @GET
    @Path("/interviews/{uuid}/presence")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public RoomPresenceResponse presence(@PathParam("uuid") UUID interviewUuid) {
        enforceRoomFlag();
        RoomAccess access = resolveAccess(interviewUuid, currentActor(), false);
        return roomService.presence(access.interview);
    }

    // ---- Draft autosave -------------------------------------------------------------

    /** Autosave the caller's own draft. 409 on a stale {@code clientRevision}. */
    @PUT
    @Path("/interviews/{uuid}/notes")
    public InterviewRoomResponse.RoomDraft saveDraft(@PathParam("uuid") UUID interviewUuid,
                                                     RoomDraftRequest request) {
        enforceRoomFlag();
        RoomAccess access = resolveAccess(interviewUuid, currentActor(), true);
        return roomService.saveDraft(access.interview, access.viewer.toString(), request);
    }

    /** Discard the caller's own draft. Idempotent. */
    @DELETE
    @Path("/interviews/{uuid}/notes")
    public Response discardDraft(@PathParam("uuid") UUID interviewUuid) {
        enforceRoomFlag();
        RoomAccess access = resolveAccess(interviewUuid, currentActor(), true);
        roomService.deleteDraft(access.interview, access.viewer.toString());
        return Response.noContent().build();
    }

    // ---- Live fact capture (⌥↵, spec §5.2) -------------------------------------------

    /**
     * One fact, authorized by assignment — the room-scoped sibling of the
     * notes route (see {@link RoomFactRequest} for why it exists).
     */
    @POST
    @Path("/interviews/{uuid}/facts")
    public Response recordFact(@PathParam("uuid") UUID interviewUuid, RoomFactRequest request) {
        enforceRoomFlag();
        Objects.requireNonNull(request, "request body must not be null");
        RoomAccess access = resolveAccess(interviewUuid, currentActor(), true);
        requireCompScopeForCompFact(request.field());
        requireFullProfileForCompFact(access, request.field());
        RecruitmentEvent event = roomService.recordFact(access.interview, access.candidate,
                request, access.viewer);
        return Response.status(Response.Status.CREATED)
                .entity(Map.of(
                        "eventId", event.getEventId(),
                        "occurredAt", event.getOccurredAt().toString()))
                .build();
    }

    // ---- Land (spec §5.3) ------------------------------------------------------------

    /** The atomic land: scorecard + facts + draft delete, one transaction. */
    @POST
    @Path("/interviews/{uuid}/land")
    public RoomLandResponse land(@PathParam("uuid") UUID interviewUuid, RoomLandRequest request) {
        enforceRoomFlag();
        Objects.requireNonNull(request, "request body must not be null");
        RoomAccess access = resolveAccess(interviewUuid, currentActor(), true);
        if (request.facts() != null) {
            request.facts().forEach(fact -> {
                requireCompScopeForCompFact(fact.field());
                requireFullProfileForCompFact(access, fact.field());
            });
        }
        return roomService.land(access.interview, access.application, access.position,
                access.candidate, request, access.viewer);
    }

    // ---- AI (spec §9, each behind its own flag) ---------------------------------------

    /** Fact extraction chips for the given lines (§5.4). */
    @POST
    @Path("/interviews/{uuid}/notes/suggest")
    public RoomSuggestResponse suggest(@PathParam("uuid") UUID interviewUuid,
                                       RoomSuggestRequest request) {
        enforceRoomFlag();
        if (!aiFlags.isInterviewRoomExtractionEnabled()) {
            throw new NotFoundException("Resource not found");
        }
        RoomAccess access = resolveAccess(interviewUuid, currentActor(), true);
        return roomAiService.suggest(access.interview, access.candidate, request, access.viewer);
    }

    /** Tidy (+ alignment when that flag is on too) at land (§9). */
    @POST
    @Path("/interviews/{uuid}/notes/tidy")
    public RoomTidyResponse tidy(@PathParam("uuid") UUID interviewUuid, RoomTidyRequest request) {
        enforceRoomFlag();
        if (!aiFlags.isInterviewRoomTidyEnabled()) {
            throw new NotFoundException("Resource not found");
        }
        RoomAccess access = resolveAccess(interviewUuid, currentActor(), true);
        return roomAiService.tidy(access.interview, access.candidate, request, access.viewer,
                aiFlags.isInterviewRoomAlignmentEnabled());
    }

    /** The prep pack: catalogue probes specialised to this candidate (§9). */
    @POST
    @Path("/interviews/{uuid}/room/prep")
    public RoomPrepResponse prep(@PathParam("uuid") UUID interviewUuid) {
        enforceRoomFlag();
        if (!aiFlags.isInterviewRoomPrepEnabled()) {
            throw new NotFoundException("Resource not found");
        }
        RoomAccess access = resolveAccess(interviewUuid, currentActor(), true);
        List<String> subjectCodes = access.position.getScorecardTemplate() == null
                ? List.of()
                : access.position.getScorecardTemplate().stream()
                        .map(dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute::code)
                        .toList();
        return roomAiService.prep(access.interview, access.candidate, subjectCodes, access.viewer);
    }

    // ---- Helpers ---------------------------------------------------------------------

    private record RoomAccess(UUID viewer, RecruitmentInterview interview,
                              RecruitmentApplication application, RecruitmentPosition position,
                              RecruitmentCandidate candidate, boolean restricted) {
    }

    /**
     * Resolve the interview chain and the viewer's grant. Assignment OR a
     * full profile read opens the room; mutations require assignment.
     * Invisible → 404, never 403.
     */
    private RoomAccess resolveAccess(UUID interviewUuid, UUID viewer, boolean requireAssigned) {
        RecruitmentInterview interview = RecruitmentInterview.findById(interviewUuid.toString());
        if (interview == null || !interview.isActive()) {
            throw new NotFoundException("Interview not found: " + interviewUuid);
        }
        RecruitmentApplication application =
                RecruitmentApplication.findById(interview.getApplicationUuid());
        if (application == null) {
            throw new NotFoundException("Interview not found: " + interviewUuid);
        }
        RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(application.getCandidateUuid());
        if (position == null || candidate == null) {
            throw new NotFoundException("Interview not found: " + interviewUuid);
        }
        boolean assigned = interview.isAssigned(viewer.toString());
        boolean profileReader = visibility.canReadCandidateProfile(viewer.toString(), candidate);
        if (!assigned && !profileReader) {
            throw new NotFoundException("Interview not found: " + interviewUuid);
        }
        if (requireAssigned && !assigned) {
            // Visible-but-unassigned (a recruiter opening the debrief view)
            // is a real 403 on mutations — same split as scorecard submission.
            throw new WebApplicationException(
                    "Only assigned interviewers can write in this room",
                    Response.Status.FORBIDDEN);
        }
        return new RoomAccess(viewer, interview, application, position, candidate, !profileReader);
    }

    /**
     * A RESTRICTED interviewer (brief-scoped, decision 6) may not write
     * compensation facts: their lane never raises salary (§5.1), and no
     * role below the hiring tier may write one anywhere else in the module
     * — the room must not become the exception (security review).
     */
    private static void requireFullProfileForCompFact(RoomAccess access, String field) {
        if (access.restricted() && RecruitmentFactVocabulary.isCompScoped(field)) {
            throw new WebApplicationException(
                    "Compensation facts are outside the restricted interviewer's lane",
                    Response.Status.FORBIDDEN);
        }
    }

    /**
     * Compensation facts require {@code recruitment:comp} — the
     * machine-client guard the notes route applies; the BFF's system
     * client passes via {@code AdminScopeAugmentor}.
     */
    private void requireCompScopeForCompFact(String field) {
        if (RecruitmentFactVocabulary.isCompScoped(field)
                && !scopeContext.hasScope("recruitment:comp")) {
            throw new WebApplicationException(
                    "Compensation facts require the recruitment:comp scope",
                    Response.Status.FORBIDDEN);
        }
    }

    /** Core interviews flag + the slice-1 room flag, 404 + admin:* convention. */
    private void enforceRoomFlag() {
        if (featureFlag.isInterviewsEnabled() && featureFlag.isInterviewRoomEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    private UUID currentActor() {
        String userUuid = requestHeaderHolder.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            throw new WebApplicationException("X-Requested-By header is required",
                    Response.Status.BAD_REQUEST);
        }
        try {
            return UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("X-Requested-By is not a valid UUID",
                    Response.Status.BAD_REQUEST);
        }
    }
}
