package dk.trustworks.intranet.aggregates.crm.sector.resources;

import dk.trustworks.intranet.aggregates.crm.plan.dto.PlanRequests;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorPlanDTO;
import dk.trustworks.intranet.aggregates.crm.sector.dto.SectorReviewRequest;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorPlanService;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * The sector plan (sectors spec §5) — {@code AccountPlanResource}'s shape keyed by segment,
 * minus the people. One endpoint per reducer, so the plan components' optimistic
 * transitions work unchanged against a sector.
 *
 * <p>Its own root, {@code /sector-plans}, for the same reason every CRM resource has one.
 * Reads are {@code accounts:read}; every write is {@code accounts:write}.
 */
@Tag(name = "crm")
@JBossLog
@Path("/sector-plans")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"accounts:read"})
public class SectorPlanResource {

    @Inject
    SectorPlanService planService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    @Path("/{segment}")
    public SectorPlanDTO read(@PathParam("segment") String segment) {
        return planService.read(SectorService.parseSegment(segment));
    }

    @POST
    @Path("/{segment}")
    @RolesAllowed({"accounts:write"})
    public SectorPlanDTO start(@PathParam("segment") String segment, PlanRequests.StartPlanRequest request) {
        return planService.start(SectorService.parseSegment(segment), request, requireActor());
    }

    @PATCH
    @Path("/{segment}")
    @RolesAllowed({"accounts:write"})
    public SectorPlanDTO patch(@PathParam("segment") String segment, PlanRequests.PlanPatchRequest request) {
        return planService.patch(SectorService.parseSegment(segment), request, requireActor());
    }

    @PUT
    @Path("/{segment}/sentences/{slot}")
    @RolesAllowed({"accounts:write"})
    public SectorPlanDTO putSentence(@PathParam("segment") String segment, @PathParam("slot") String slot,
                                     PlanRequests.SentenceRequest request) {
        return planService.putSentence(SectorService.parseSegment(segment), slot, request, requireActor());
    }

    // ---- Objectives --------------------------------------------------------

    @POST
    @Path("/{segment}/objectives")
    @RolesAllowed({"accounts:write"})
    public SectorPlanDTO addObjective(@PathParam("segment") String segment, PlanRequests.ObjectiveRequest request) {
        return planService.addObjective(SectorService.parseSegment(segment), request, requireActor());
    }

    @PATCH
    @Path("/{segment}/objectives/{objectiveUuid}")
    @RolesAllowed({"accounts:write"})
    public SectorPlanDTO updateObjective(@PathParam("segment") String segment,
                                         @PathParam("objectiveUuid") String objectiveUuid,
                                         PlanRequests.ObjectiveRequest request) {
        return planService.updateObjective(SectorService.parseSegment(segment), objectiveUuid, request, requireActor());
    }

    /** Closes the objective. The row survives — account objectives may still serve it. */
    @DELETE
    @Path("/{segment}/objectives/{objectiveUuid}")
    @RolesAllowed({"accounts:write"})
    public SectorPlanDTO closeObjective(@PathParam("segment") String segment,
                                        @PathParam("objectiveUuid") String objectiveUuid) {
        return planService.closeObjective(SectorService.parseSegment(segment), objectiveUuid, requireActor());
    }

    // ---- Actions -----------------------------------------------------------

    @POST
    @Path("/{segment}/actions")
    @RolesAllowed({"accounts:write"})
    public SectorPlanDTO addAction(@PathParam("segment") String segment, PlanRequests.ActionRequest request) {
        return planService.addAction(SectorService.parseSegment(segment), request, requireActor());
    }

    @PATCH
    @Path("/{segment}/actions/{actionUuid}")
    @RolesAllowed({"accounts:write"})
    public SectorPlanDTO updateAction(@PathParam("segment") String segment,
                                      @PathParam("actionUuid") String actionUuid,
                                      PlanRequests.ActionRequest request) {
        return planService.updateAction(SectorService.parseSegment(segment), actionUuid, request, requireActor());
    }

    @DELETE
    @Path("/{segment}/actions/{actionUuid}")
    @RolesAllowed({"accounts:write"})
    public SectorPlanDTO removeAction(@PathParam("segment") String segment,
                                      @PathParam("actionUuid") String actionUuid) {
        return planService.removeAction(SectorService.parseSegment(segment), actionUuid, requireActor());
    }

    // ---- Reviews -----------------------------------------------------------

    @POST
    @Path("/{segment}/reviews")
    @RolesAllowed({"accounts:write"})
    public SectorPlanDTO closeReview(@PathParam("segment") String segment, SectorReviewRequest request) {
        return planService.closeReview(SectorService.parseSegment(segment), request, requireActor());
    }

    private String requireActor() {
        String actor = requestHeaderHolder.getUserUuid();
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException("X-Requested-By header is required", Response.Status.BAD_REQUEST);
        }
        try {
            java.util.UUID.fromString(actor.trim());
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("X-Requested-By is not a valid UUID", Response.Status.BAD_REQUEST);
        }
        return actor.trim();
    }
}
