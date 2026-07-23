package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.CircleMemberRequest;
import dk.trustworks.intranet.recruitmentservice.dto.PositionBoardResponse;
import dk.trustworks.intranet.recruitmentservice.dto.PositionListResponse;
import dk.trustworks.intranet.recruitmentservice.dto.PositionRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCircleMember;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentBoardService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentPositionService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST entry point for recruitment positions & hiring tracks (ATS plan §P2,
 * spec §6.2). Thin by convention: flag gate → actor resolution → visibility
 * check → delegate to {@link RecruitmentPositionService}.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Class-level {@code @RolesAllowed({"recruitment:read"})}, write
 *       methods override with {@code recruitment:write} — client-level
 *       scopes; the BFF holds {@code admin:*}.</li>
 *   <li>Per-user rules key on the {@code X-Requested-By} user via
 *       {@link RecruitmentVisibility}: partner-track positions are
 *       circle-gated in every code path (list, get, mutate), practice leads
 *       get read access to their practice's non-partner positions. An
 *       invisible position answers 404, never 403 — a partner search must
 *       not leak that the position exists.</li>
 *   <li>Feature flag {@code recruitment.pipeline.enabled}: off + non-admin
 *       caller → 404 (same convention as the dossier endpoints).</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment/positions")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentPositionResource {

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
    RecruitmentPositionService positionService;

    @Inject
    RecruitmentBoardService boardService;

    // ---- Read -----------------------------------------------------------------

    /**
     * List the positions visible to the calling user, optionally filtered.
     *
     * @param practice practice uuid (registry identity, never a code)
     * @param track    PRACTICE_TEAM | PARTNER | STAFF_ROLE
     * @param status   OPEN | ON_HOLD | CLOSED
     */
    @GET
    public PositionListResponse list(@QueryParam("practice") String practice,
                                     @QueryParam("track") RecruitmentHiringTrack track,
                                     @QueryParam("status") RecruitmentPositionStatus status) {
        enforceFlag();
        String viewer = currentActor().toString();
        List<RecruitmentPosition> positions = visibility.filterPositions(viewer, practice, track, status);
        return new PositionListResponse(positions, positions.size());
    }

    @GET
    @Path("/{uuid}")
    public RecruitmentPosition get(@PathParam("uuid") UUID uuid) {
        enforceFlag();
        return requireVisiblePosition(uuid, currentActor());
    }

    /**
     * The position's pipeline board (P7): open applications grouped into
     * one column per {@code stage_set} entry (HIRED included), cards oldest
     * {@code stageEnteredAt} first with server-computed idle flags, and the
     * terminal outcomes summarized in a rail. Read-only; same visibility
     * rule as {@link #get} — an invisible position answers 404, never 403.
     */
    @GET
    @Path("/{uuid}/board")
    public PositionBoardResponse board(@PathParam("uuid") UUID uuid) {
        enforceFlag();
        RecruitmentPosition position = requireVisiblePosition(uuid, currentActor());
        return boardService.board(position);
    }

    // ---- Mutations ---------------------------------------------------------------

    @POST
    @RolesAllowed({"recruitment:write"})
    public Response create(@Valid PositionRequest request) {
        enforceFlag();
        RecruitmentPosition position = positionService.create(request, currentActor());
        return Response.created(URI.create("/recruitment/positions/" + position.getUuid()))
                .entity(position)
                .build();
    }

    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"recruitment:write"})
    public RecruitmentPosition update(@PathParam("uuid") UUID uuid, @Valid PositionRequest request) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentPosition position = requireVisiblePosition(uuid, actor);
        requirePartnerMutationRights(position, actor);
        return positionService.update(position, request, actor);
    }

    @POST
    @Path("/{uuid}/close")
    @RolesAllowed({"recruitment:write"})
    public RecruitmentPosition close(@PathParam("uuid") UUID uuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentPosition position = requireVisiblePosition(uuid, actor);
        requirePartnerMutationRights(position, actor);
        return positionService.close(position, actor);
    }

    // ---- Circle (partner track) -----------------------------------------------------

    @GET
    @Path("/{uuid}/circle")
    public List<RecruitmentCircleMember> circle(@PathParam("uuid") UUID uuid) {
        enforceFlag();
        RecruitmentPosition position = requireVisiblePosition(uuid, currentActor());
        return positionService.circleMembers(position.getUuid());
    }

    @POST
    @Path("/{uuid}/circle")
    @RolesAllowed({"recruitment:write"})
    public Response addCircleMember(@PathParam("uuid") UUID uuid, @Valid CircleMemberRequest request) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentPosition position = requireVisiblePosition(uuid, actor);
        requireCircleManagement(position, actor);
        RecruitmentCircleMember member = positionService.addCircleMember(
                position, request.userUuid(), request.roleInCircle(), actor);
        return Response.status(Response.Status.CREATED).entity(member).build();
    }

    @DELETE
    @Path("/{uuid}/circle/{userUuid}")
    @RolesAllowed({"recruitment:write"})
    public Response removeCircleMember(@PathParam("uuid") UUID uuid,
                                       @PathParam("userUuid") UUID userUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentPosition position = requireVisiblePosition(uuid, actor);
        requireCircleManagement(position, actor);
        positionService.removeCircleMember(position, userUuid.toString(), actor);
        return Response.noContent().build();
    }

    // ---- Helpers --------------------------------------------------------------

    /**
     * Resolve the position and apply the per-viewer visibility rule. An
     * existing-but-invisible position (partner track, viewer outside the
     * circle) answers the same 404 as a nonexistent one.
     */
    private RecruitmentPosition requireVisiblePosition(UUID uuid, UUID viewer) {
        RecruitmentPosition position = RecruitmentPosition.findById(uuid.toString());
        if (position == null || !visibility.canReadPosition(viewer.toString(), position)) {
            throw new NotFoundException("Position not found: " + uuid);
        }
        return position;
    }

    private void requireCircleManagement(RecruitmentPosition position, UUID actor) {
        if (!visibility.canManageCircle(actor.toString(), position)) {
            throw new WebApplicationException(
                    "Only circle owners/recruiters (or HR/admin) may manage the circle",
                    Response.Status.FORBIDDEN);
        }
    }

    /**
     * On partner-track positions, circle PARTICIPANTs may look but not
     * touch: edits and closes require circle-management rights
     * (OWNER/RECRUITER membership, HR or admin). Non-partner tracks pass —
     * their visibility rules already restrict who gets this far.
     */
    private void requirePartnerMutationRights(RecruitmentPosition position, UUID actor) {
        if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER
                && !visibility.canManageCircle(actor.toString(), position)) {
            throw new WebApplicationException(
                    "Only circle owners/recruiters (or HR/admin) may change a partner-track position",
                    Response.Status.FORBIDDEN);
        }
    }

    /**
     * Block the request when {@code recruitment.pipeline.enabled} is off,
     * unless the caller holds {@code admin:*}. 404 (not 503) to avoid
     * leaking the feature's existence — same convention as the dossier
     * endpoints.
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
