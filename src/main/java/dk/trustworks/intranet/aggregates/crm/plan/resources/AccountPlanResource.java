package dk.trustworks.intranet.aggregates.crm.plan.resources;

import dk.trustworks.intranet.aggregates.crm.plan.dto.AccountPlanDTO;
import dk.trustworks.intranet.aggregates.crm.plan.dto.PlanRequests;
import dk.trustworks.intranet.aggregates.crm.plan.services.AccountPlanService;
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
 * The account plan (CRM spec §3.3), one endpoint per reducer in the frontend's
 * {@code accountPlanReducers.ts}.
 *
 * <p>The granularity is deliberate. The tab applies the matching pure reducer optimistically
 * and then calls the endpoint, so an edit lands instantly and the server stays the
 * authority; a single coarse "save the whole plan" PUT would make two people editing the
 * same plan overwrite each other's objectives wholesale.
 *
 * <p><b>Its own root.</b> {@code /account-plans} is a prefix no other class owns — nesting
 * under {@code /clients} would be shadowed by {@code ClientResource} and 404 whatever the
 * method paths said.
 *
 * <p>Reads are {@code accounts:read} (every employee — plans are firm-readable, like the
 * page they sit on); every write is {@code accounts:write}, the sales tier.
 */
@Tag(name = "crm")
@JBossLog
@Path("/account-plans")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"accounts:read"})
public class AccountPlanResource {

    @Inject
    AccountPlanService planService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    @Path("/{clientUuid}")
    public AccountPlanDTO read(@PathParam("clientUuid") String clientUuid) {
        return planService.read(clientUuid);
    }

    @POST
    @Path("/{clientUuid}")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO start(@PathParam("clientUuid") String clientUuid,
                                PlanRequests.StartPlanRequest request) {
        return planService.start(clientUuid, request, requireActor());
    }

    /** "Still true", and the health assessment behind it. */
    @PATCH
    @Path("/{clientUuid}")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO patch(@PathParam("clientUuid") String clientUuid,
                                PlanRequests.PlanPatchRequest request) {
        return planService.patch(clientUuid, request, requireActor());
    }

    @PUT
    @Path("/{clientUuid}/sentences/{slot}")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO putSentence(@PathParam("clientUuid") String clientUuid,
                                      @PathParam("slot") String slot,
                                      PlanRequests.SentenceRequest request) {
        return planService.putSentence(clientUuid, slot, request, requireActor());
    }

    // ---- Objectives --------------------------------------------------------

    @POST
    @Path("/{clientUuid}/objectives")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO addObjective(@PathParam("clientUuid") String clientUuid,
                                       PlanRequests.ObjectiveRequest request) {
        return planService.addObjective(clientUuid, request, requireActor());
    }

    @PATCH
    @Path("/{clientUuid}/objectives/{objectiveUuid}")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO updateObjective(@PathParam("clientUuid") String clientUuid,
                                          @PathParam("objectiveUuid") String objectiveUuid,
                                          PlanRequests.ObjectiveRequest request) {
        return planService.updateObjective(clientUuid, objectiveUuid, request, requireActor());
    }

    /** Closing an objective as achieved — it leaves the live list but the row survives. */
    @DELETE
    @Path("/{clientUuid}/objectives/{objectiveUuid}")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO closeObjective(@PathParam("clientUuid") String clientUuid,
                                         @PathParam("objectiveUuid") String objectiveUuid) {
        return planService.closeObjective(clientUuid, objectiveUuid, requireActor());
    }

    // ---- Actions -----------------------------------------------------------

    @POST
    @Path("/{clientUuid}/actions")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO addAction(@PathParam("clientUuid") String clientUuid,
                                    PlanRequests.ActionRequest request) {
        return planService.addAction(clientUuid, request, requireActor());
    }

    @PATCH
    @Path("/{clientUuid}/actions/{actionUuid}")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO updateAction(@PathParam("clientUuid") String clientUuid,
                                       @PathParam("actionUuid") String actionUuid,
                                       PlanRequests.ActionRequest request) {
        return planService.updateAction(clientUuid, actionUuid, request, requireActor());
    }

    @DELETE
    @Path("/{clientUuid}/actions/{actionUuid}")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO removeAction(@PathParam("clientUuid") String clientUuid,
                                       @PathParam("actionUuid") String actionUuid) {
        return planService.removeAction(clientUuid, actionUuid, requireActor());
    }

    // ---- People on the plan ------------------------------------------------

    @POST
    @Path("/{clientUuid}/stakeholders")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO addStakeholder(@PathParam("clientUuid") String clientUuid,
                                         PlanRequests.StakeholderRequest request) {
        return planService.addStakeholder(clientUuid, request, requireActor());
    }

    @PATCH
    @Path("/{clientUuid}/stakeholders/{stakeholderUuid}")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO updateStakeholder(@PathParam("clientUuid") String clientUuid,
                                            @PathParam("stakeholderUuid") String stakeholderUuid,
                                            PlanRequests.StakeholderRequest request) {
        return planService.updateStakeholder(clientUuid, stakeholderUuid, request, requireActor());
    }

    @DELETE
    @Path("/{clientUuid}/stakeholders/{stakeholderUuid}")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO removeStakeholder(@PathParam("clientUuid") String clientUuid,
                                            @PathParam("stakeholderUuid") String stakeholderUuid) {
        return planService.removeStakeholder(clientUuid, stakeholderUuid, requireActor());
    }

    // ---- Reviews -----------------------------------------------------------

    /** Closes a review: bumps the plan version and freezes a snapshot (rule 9). */
    @POST
    @Path("/{clientUuid}/reviews")
    @RolesAllowed({"accounts:write"})
    public AccountPlanDTO closeReview(@PathParam("clientUuid") String clientUuid,
                                      PlanRequests.ReviewRequest request) {
        return planService.closeReview(clientUuid, request, requireActor());
    }

    /**
     * The acting employee. {@code X-Requested-By} falls back to the BFF's client id when
     * the header is missing, so anything that is not a uuid is refused rather than stamped
     * on the plan as its author.
     */
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
