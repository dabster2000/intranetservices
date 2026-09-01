package dk.trustworks.intranet.agreementservice.resources;

import dk.trustworks.intranet.agreementservice.dto.BackfillItemDTO;
import dk.trustworks.intranet.agreementservice.dto.BackfillRunDTO;
import dk.trustworks.intranet.agreementservice.model.AgreementBackfillItem;
import dk.trustworks.intranet.agreementservice.security.AgreementAccessPolicy;
import dk.trustworks.intranet.agreementservice.services.AgreementBackfillJobRunner;
import dk.trustworks.intranet.agreementservice.services.AgreementBackfillService;
import dk.trustworks.intranet.agreementservice.services.AgreementBackfillService.ConfirmRequest;
import dk.trustworks.intranet.agreementservice.services.AgreementsFeatureFlag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * The AI-backfill console API (template-clauses spec §10, D8): run
 * management for the single-flight corpus walk and the human review
 * queue. Same posture as {@code AgreementResource} — the
 * {@code agreements:*} scopes gate the BFF client, the acting human
 * must be HR/ADMIN on every endpoint, and nothing enters the registry
 * without a confirm.
 *
 * <p>The literal {@code /agreements/backfill} class path wins JAX-RS
 * matching over {@code AgreementResource}'s {@code /agreements/{uuid}}
 * template.</p>
 */
@JBossLog
@Tag(name = "agreements", description = "AI backfill of the agreement registry from signed SharePoint documents")
@Path("/agreements/backfill")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"agreements:read"})
public class AgreementBackfillResource {

    @Inject
    AgreementBackfillService backfillService;

    @Inject
    AgreementAccessPolicy accessPolicy;

    @Inject
    AgreementsFeatureFlag featureFlag;

    // ── Runs ───────────────────────────────────────────────────────────────

    @GET
    @Path("/status")
    @Operation(summary = "Current (or last) walk job state (HR/ADMIN)")
    public Response status() {
        accessPolicy.requireManager();
        AgreementBackfillJobRunner.JobStatus status = backfillService.jobStatus();
        // Stable empty-state shape so callers never parse a bare 204.
        return Response.ok(status != null ? status : java.util.Map.of("running", false)).build();
    }

    @GET
    @Path("/runs")
    @Operation(summary = "Recent backfill runs, newest first (HR/ADMIN)")
    public List<BackfillRunDTO> runs(@QueryParam("limit") @DefaultValue("20") int limit) {
        accessPolicy.requireManager();
        return backfillService.findRuns(limit);
    }

    @GET
    @Path("/runs/{uuid}")
    @Operation(summary = "One backfill run with live counters (HR/ADMIN)")
    public BackfillRunDTO run(@PathParam("uuid") String uuid) {
        accessPolicy.requireManager();
        return backfillService.findRun(uuid);
    }

    /**
     * Start a corpus walk. Flag-gated on
     * {@code documents.agreements.backfill.enabled}: the walk downloads
     * employee documents and spends AI tokens, so unlike the passive
     * registry surfaces it must never start while the feature is dark.
     */
    @POST
    @Path("/runs")
    @RolesAllowed({"agreements:write"})
    @Operation(summary = "Start a backfill corpus walk (HR/ADMIN); dryRun enumerates and counts only")
    public Response startRun(@QueryParam("dryRun") @DefaultValue("false") boolean dryRun) {
        String actor = accessPolicy.requireManager();
        if (!featureFlag.isBackfillEnabled()) {
            throw new WebApplicationException(
                    "The agreement backfill is disabled (documents.agreements.backfill.enabled)", 409);
        }
        log.infof("POST /agreements/backfill/runs: actor=%s dryRun=%s", actor, dryRun);
        return Response.status(Response.Status.CREATED)
                .entity(backfillService.startRun(actor, dryRun)).build();
    }

    // ── Review queue ───────────────────────────────────────────────────────

    @GET
    @Path("/items")
    @Operation(summary = "Backfill items for the review queue (HR/ADMIN); filters optional")
    public List<BackfillItemDTO> items(@QueryParam("runUuid") String runUuid,
                                       @QueryParam("status") String status,
                                       @QueryParam("userUuid") String userUuid) {
        accessPolicy.requireManager();
        return backfillService.findItems(runUuid, status, userUuid);
    }

    /** PDF preview beside the proposal — bytes fetched live from SharePoint. */
    @GET
    @Path("/items/{uuid}/document")
    @Produces("application/pdf")
    @Operation(summary = "Stream the item's source PDF for the review preview (HR/ADMIN)")
    public Response document(@PathParam("uuid") String uuid) {
        accessPolicy.requireManager();
        AgreementBackfillItem item = backfillService.requireItem(uuid);
        byte[] bytes = backfillService.downloadDocument(uuid);
        String safeName = item.getFileName() == null ? "document.pdf"
                : item.getFileName().replaceAll("[\\r\\n\"\\\\]", "_");
        return Response.ok(bytes)
                .header("Content-Disposition", "inline; filename=\"" + safeName + "\"")
                .build();
    }

    @POST
    @Path("/items/{uuid}/confirm")
    @RolesAllowed({"agreements:write"})
    @Operation(summary = "Confirm (optionally edited) proposals — writes registry rows (HR/ADMIN); one-shot")
    public BackfillItemDTO confirm(@PathParam("uuid") String uuid, ConfirmRequest request) {
        String actor = accessPolicy.requireManager();
        if (request == null) {
            throw new WebApplicationException("Request body is required", 400);
        }
        log.infof("POST /agreements/backfill/items/%s/confirm", uuid);
        return backfillService.confirm(uuid, request, actor);
    }

    @POST
    @Path("/items/{uuid}/reject")
    @RolesAllowed({"agreements:write"})
    @Operation(summary = "Reject the document's proposals — nothing enters the registry (HR/ADMIN); one-shot")
    public BackfillItemDTO reject(@PathParam("uuid") String uuid) {
        String actor = accessPolicy.requireManager();
        log.infof("POST /agreements/backfill/items/%s/reject", uuid);
        return backfillService.reject(uuid, actor);
    }
}
