package dk.trustworks.intranet.documentservice.maintenance;

import dk.trustworks.intranet.documentservice.maintenance.EmployeeDocumentMaintenanceJobRunner.JobStatus;
import dk.trustworks.intranet.documentservice.maintenance.EmployeeDocumentMaintenanceJobRunner.JobType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.Map;

/**
 * Admin-triggered maintenance jobs over the S3 employee-document store —
 * what survived the retirement of the SharePoint→S3 migration tooling.
 * Nothing here runs on its own: every job is started from the settings
 * card and polled through {@code GET /status}.
 *
 * <p>Client-level gate: {@code documents:read}/{@code documents:write}
 * scopes. User-level gate: the BFF routes under
 * {@code api/admin/employee-documents/maintenance/*} require ADMIN
 * (system-JWT model per the house architecture).</p>
 *
 * <p>The run endpoints are asynchronous: a categorize pass takes minutes
 * (one OpenAI call per batch, one per excerpt) — past the ALB idle
 * timeout — so POST starts a single-flight background job (409 when one
 * is already running) and the card polls {@code GET /status}.</p>
 */
@JBossLog
@RequestScoped
@Path("/admin/employee-documents/maintenance")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"documents:read"})
public class EmployeeDocumentMaintenanceResource {

    @Inject
    EmployeeDocumentMaintenanceJobRunner jobRunner;

    @Inject
    EmployeeDocumentCategorizerService categorizerService;

    @Inject
    EmployeeDocumentHashBackfillService hashBackfillService;

    /**
     * AI-first categorization + deterministic signing linkage.
     *
     * <p>Re-runnable, and re-running is the intended way to finish an
     * interrupted pass: the candidate set is recomputed from the current
     * OTHER rows, and everything already placed is skipped. Two opt-ins
     * widen it for a clean-up run:</p>
     *
     * <ul>
     *   <li>{@code includeFlagged} — also reconsider rows already flagged
     *       {@code needs_review}. They are skipped by default, which
     *       leaves them stranded once a first pass has flagged them.</li>
     *   <li>{@code forceContentPass} — read a first-page excerpt for
     *       every still-OTHER document, not only the ones the name pass
     *       could not place. This is what reaches scans and generically
     *       named files; it costs one extra OpenAI call per document and
     *       needs the AI categorization flag ON.</li>
     * </ul>
     */
    @POST
    @Path("/categorize")
    @RolesAllowed({"documents:write"})
    public JobStatus categorize(
            @QueryParam("includeFlagged") @DefaultValue("false") boolean includeFlagged,
            @QueryParam("forceContentPass") @DefaultValue("false") boolean forceContentPass) {
        return jobRunner.start(JobType.CATEGORIZE,
                () -> categorizerService.categorize(includeFlagged, forceContentPass));
    }

    /**
     * Backfill {@code sha256} for the rows that never got one (every
     * server-side S3→S3 copy leaves it null). Duplicate detection falls
     * back to filename + exact size without it, which is good enough to
     * archive a redundant row but not to delete one — this job is what
     * turns that evidence into proof. Idempotent. {@code dryRun=true}
     * only counts the rows and the bytes it would read.
     */
    @POST
    @Path("/backfill-sha256")
    @RolesAllowed({"documents:write"})
    public JobStatus backfillSha256(@QueryParam("dryRun") @DefaultValue("false") boolean dryRun) {
        return jobRunner.start(JobType.BACKFILL_SHA256, () -> hashBackfillService.backfill(dryRun));
    }

    /** Poll target for the card: the running (or last finished) job. */
    @GET
    @Path("/status")
    public Response status() {
        JobStatus status = jobRunner.status();
        return Response.ok(status == null ? Map.of("idle", true) : status).build();
    }
}
