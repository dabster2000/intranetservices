package dk.trustworks.intranet.aggregates.crm.enrichment.resources;

import dk.trustworks.intranet.aggregates.crm.enrichment.dto.ClientEnrichmentDTO;
import dk.trustworks.intranet.aggregates.crm.enrichment.services.ClientEnrichmentService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * What the nightly enrichment jobs know about each client, and the one-click answers a
 * person gives back.
 *
 * <p><b>Its own class path, deliberately longer than {@code /clients}.</b> RESTEasy
 * Reactive selects the resource class by its class-level {@code @Path} before it looks at
 * a method, longest prefix first — which is how {@code /clients/cxo} coexists with
 * {@code ClientResource} today and how {@code /clients/enrichment} does here. Every path
 * below is therefore {@code /clients/enrichment/...}, never {@code /clients/{uuid}/...}.
 *
 * <p>{@code crm:read} for the reads (the account page is firm-readable and this is less
 * than that); {@code crm:write} for the answers, the same scope as editing the client;
 * {@code admin:write} for the manual run, which spends registry quota and model credits.
 * {@code @RolesAllowed} gates the CLIENT, not the person — the per-person gate is the
 * BFF's {@code requirePermission}.
 */
@Tag(name = "crm")
@JBossLog
@Path("/clients/enrichment")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@RolesAllowed({"crm:read"})
public class ClientEnrichmentResource {

    @Inject
    ClientEnrichmentService enrichmentService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @GET
    @Operation(summary = "Enrichment state of every client that has one",
            description = "One row per client the jobs have touched or seeded. Clients without a row are PENDING everywhere.")
    public List<ClientEnrichmentDTO> all() {
        return enrichmentService.readAll();
    }

    @GET
    @Path("/status")
    @Operation(summary = "Whether an enrichment run is in progress")
    public Map<String, Object> status() {
        return Map.of("running", enrichmentService.isRunning());
    }

    @GET
    @Path("/{clientUuid}")
    @Operation(summary = "Enrichment state of one client")
    public ClientEnrichmentDTO one(@PathParam("clientUuid") String clientUuid) {
        return enrichmentService.read(clientUuid);
    }

    @POST
    @Path("/{clientUuid}/cvr/accept")
    @RolesAllowed({"crm:write"})
    @Operation(summary = "Accept the proposed CVR",
            description = "Looks the candidate up in the registry again and applies it: the registry's data overwrites the client. 409 when nothing is proposed or the CVR is on another client.")
    public ClientEnrichmentDTO acceptCvr(@PathParam("clientUuid") String clientUuid) {
        log.infof("CVR candidate accepted: client=%s by=%s", clientUuid, requestHeaderHolder.getUserUuid());
        return enrichmentService.acceptCvrCandidate(clientUuid);
    }

    @POST
    @Path("/{clientUuid}/cvr/dismiss")
    @RolesAllowed({"crm:write"})
    @Operation(summary = "Reject the proposed CVR, or close the CVR question for this client")
    public ClientEnrichmentDTO dismissCvr(@PathParam("clientUuid") String clientUuid) {
        log.infof("CVR question dismissed: client=%s by=%s", clientUuid, requestHeaderHolder.getUserUuid());
        return enrichmentService.dismissCvr(clientUuid);
    }

    @POST
    @Path("/{clientUuid}/{job}/retry")
    @RolesAllowed({"crm:write"})
    @Operation(summary = "Re-run one check for one client now",
            description = "job is cvr, logo or sector. CVR and sector answer inline; a logo runs in the background and the row reads PENDING until it lands.")
    public Response retry(@PathParam("clientUuid") String clientUuid, @PathParam("job") String job) {
        ClientEnrichmentService.Job parsed = ClientEnrichmentService.parseJob(job)
                .orElseThrow(() -> new jakarta.ws.rs.BadRequestException("job must be cvr, logo or sector"));
        log.infof("Enrichment retry: client=%s job=%s by=%s", clientUuid, parsed, requestHeaderHolder.getUserUuid());
        ClientEnrichmentDTO dto = enrichmentService.retry(clientUuid, parsed);
        return Response.status(parsed == ClientEnrichmentService.Job.LOGO ? Response.Status.ACCEPTED : Response.Status.OK)
                .entity(dto)
                .build();
    }

    @POST
    @Path("/run")
    @RolesAllowed({"admin:write"})
    @Operation(summary = "Start an enrichment run now",
            description = "job is all (default), cvr, logo or sector. 202 when started, 409 when a run is already in progress, 412 when a switch is off. Not subject to the staging gate — this is how staging rehearses.")
    public Response run(@QueryParam("job") String job) {
        Set<ClientEnrichmentService.Job> jobs = job == null || job.isBlank() || "all".equalsIgnoreCase(job.trim())
                ? EnumSet.allOf(ClientEnrichmentService.Job.class)
                : EnumSet.of(ClientEnrichmentService.parseJob(job)
                        .orElseThrow(() -> new jakarta.ws.rs.BadRequestException("job must be all, cvr, logo or sector")));
        String actor = requestHeaderHolder.getUserUuid() == null ? "unknown" : requestHeaderHolder.getUserUuid();
        boolean started = enrichmentService.runManual(jobs, actor);
        if (!started) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "An enrichment run is already in progress"))
                    .build();
        }
        log.infof("Enrichment run started manually: jobs=%s by=%s", jobs, actor);
        return Response.status(Response.Status.ACCEPTED).entity(Map.of("started", true, "jobs", jobs)).build();
    }
}
