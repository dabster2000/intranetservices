package dk.trustworks.intranet.documentservice.migration.resources;

import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem.ItemStatus;
import dk.trustworks.intranet.documentservice.migration.services.DocumentMigrationJobRunner;
import dk.trustworks.intranet.documentservice.migration.services.EmployeeDocumentHashBackfillService;
import dk.trustworks.intranet.documentservice.migration.services.DocumentMigrationJobRunner.JobStatus;
import dk.trustworks.intranet.documentservice.migration.services.DocumentMigrationJobRunner.JobType;
import dk.trustworks.intranet.documentservice.migration.services.SharePointFolderMatcherService;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMappingTransferService;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationCategorizerService;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationCopyService;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationCrawlerService;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationRenameService;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationReportService;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationReportService.MigrationReport;
import dk.trustworks.intranet.documentservice.migration.services.SharePointMigrationVerifierService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentsParameters;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase-2a migration tooling surface (runbook 2a-2 … 2a-7). TEMPORARY —
 * deleted with the working tables at decommission (spec §9.8). Nothing
 * here runs on its own: every job is admin-triggered from the migration
 * card and the whole feature ships dark.
 *
 * <p>Client-level gate: {@code documents:read}/{@code documents:write}
 * scopes. User-level gate: the BFF routes under
 * {@code api/admin/document-migration/*} require ADMIN (system-JWT model
 * per the house architecture).</p>
 *
 * <p>The run endpoints are asynchronous: crawl/copy walk SharePoint with
 * a politeness delay (minutes to low hours) — far past the ALB idle
 * timeout — so POST starts a single-flight background job (409 when one
 * is already running) and the card polls {@code GET /status}.</p>
 */
@JBossLog
@RequestScoped
@Path("/admin/document-migration")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"documents:read"})
public class DocumentMigrationResource {

    @Inject
    DocumentMigrationJobRunner jobRunner;

    @Inject
    SharePointMigrationCrawlerService crawlerService;

    @Inject
    SharePointFolderMatcherService matcherService;

    @Inject
    SharePointMigrationCopyService copyService;

    @Inject
    SharePointMigrationCategorizerService categorizerService;

    @Inject
    SharePointMigrationRenameService renameService;

    @Inject
    SharePointMigrationVerifierService verifierService;

    @Inject
    SharePointMigrationReportService reportService;

    @Inject
    SharePointMappingTransferService mappingTransferService;

    @Inject
    EmployeeDocumentHashBackfillService hashBackfillService;

    @Inject
    EmployeeDocumentsParameters parameters;

    // ── Jobs ───────────────────────────────────────────────────────────────

    /** M1 — read-only inventory of the 3 SharePoint sites. */
    @POST
    @Path("/crawl")
    @RolesAllowed({"documents:write"})
    public JobStatus crawl() {
        return jobRunner.start(JobType.CRAWL, crawlerService::crawl);
    }

    /** M2 — exact tiers + AI proposals (proposals never auto-apply). */
    @POST
    @Path("/match")
    @RolesAllowed({"documents:write"})
    public JobStatus match() {
        return jobRunner.start(JobType.MATCH, matcherService::match);
    }

    /** M3 — SharePoint→S3 + legacy re-home. Always dry-run first. */
    @POST
    @Path("/copy")
    @RolesAllowed({"documents:write"})
    public JobStatus copy(@QueryParam("dryRun") @DefaultValue("true") boolean dryRun) {
        return jobRunner.start(dryRun ? JobType.COPY_DRY_RUN : JobType.COPY,
                () -> copyService.copy(dryRun));
    }

    /**
     * M4 — AI-first categorization + deterministic signing linkage.
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
     *       needs the migration AI flag ON.</li>
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
     * M6 — standardized display names (V476). Selects on
     * {@code source = MIGRATION AND display_name IS NULL}, so it also
     * backfills documents an earlier categorize run already placed.
     * Metadata only: {@code original_filename} and the S3 keys are never
     * written. Always run {@code dryRun=true} first — it produces the
     * full before/after list without a single DB write.
     */
    @POST
    @Path("/rename")
    @RolesAllowed({"documents:write"})
    public JobStatus rename(@QueryParam("dryRun") @DefaultValue("true") boolean dryRun) {
        return jobRunner.start(dryRun ? JobType.RENAME_DRY_RUN : JobType.RENAME,
                () -> renameService.rename(dryRun));
    }

    /**
     * Backfill {@code sha256} for the rows that never got one (every
     * server-side S3→S3 copy left it null). Duplicate detection falls
     * back to filename + exact size without it, which is good enough to
     * archive a redundant row but not to delete one — this job is what
     * turns that evidence into proof. Idempotent; run {@code dryRun=true}
     * first for the count and the bytes it would read.
     */
    @POST
    @Path("/backfill-sha256")
    @RolesAllowed({"documents:write"})
    public JobStatus backfillSha256(@QueryParam("dryRun") @DefaultValue("true") boolean dryRun) {
        return jobRunner.start(dryRun ? JobType.HASH_BACKFILL_DRY_RUN : JobType.HASH_BACKFILL,
                () -> hashBackfillService.backfill(dryRun));
    }

    /** M5 — size + sha256 verification, folder promotion. */
    @POST
    @Path("/verify")
    @RolesAllowed({"documents:write"})
    public JobStatus verify() {
        return jobRunner.start(JobType.VERIFY, verifierService::verify);
    }

    /** Poll target for the card: the running (or last finished) job. */
    @GET
    @Path("/status")
    public Response status() {
        JobStatus status = jobRunner.status();
        return Response.ok(status == null ? Map.of("idle", true) : status).build();
    }

    // ── Read side ──────────────────────────────────────────────────────────

    public record FolderDTO(
            Long id, String siteUrl, String folderPath, String folderName,
            String matchedUserUuid, String matchedUserName, String matchMethod,
            String aiSuggestedUserUuid, String aiSuggestedUserName, String aiConfidence,
            String aiReason, int fileCount, long totalBytes, String status, String note) { }

    /** The folders grid, AI suggestion columns included (runbook 2a-7). */
    @GET
    @Path("/folders")
    public List<FolderDTO> folders() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<String, String> nameCache = new HashMap<>();
            return SharePointMigrationFolder.<SharePointMigrationFolder>listAll().stream()
                    .map(folder -> new FolderDTO(
                            folder.getId(), folder.getSiteUrl(), folder.getFolderPath(),
                            folder.getFolderName(),
                            folder.getMatchedUserUuid(),
                            resolve(nameCache, folder.getMatchedUserUuid()),
                            folder.getMatchMethod().name(),
                            folder.getAiSuggestedUserUuid(),
                            resolve(nameCache, folder.getAiSuggestedUserUuid()),
                            folder.getAiConfidence() == null ? null : folder.getAiConfidence().name(),
                            folder.getAiReason(), folder.getFileCount(), folder.getTotalBytes(),
                            folder.getStatus().name(), folder.getNote()))
                    .sorted((a, b) -> a.folderName().compareToIgnoreCase(b.folderName()))
                    .toList();
        });
    }

    public record ProblemItemDTO(Long id, String folderName, String relativePath, String name,
                                 long sizeBytes, String status, String error) { }

    /** Only FAILED/SKIPPED items, with the recorded error (runbook 2a-7). */
    @GET
    @Path("/items/errors")
    public List<ProblemItemDTO> itemErrors() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Map<Long, String> folderNames = new HashMap<>();
            SharePointMigrationFolder.<SharePointMigrationFolder>listAll()
                    .forEach(f -> folderNames.put(f.getId(), f.getFolderName()));
            return SharePointMigrationItem.<SharePointMigrationItem>list(
                            "status IN (?1, ?2)", ItemStatus.FAILED, ItemStatus.SKIPPED).stream()
                    .map(item -> new ProblemItemDTO(item.getId(),
                            folderNames.getOrDefault(item.getFolderId(), "?"),
                            item.getRelativePath(), item.getName(), item.getSizeBytes(),
                            item.getStatus().name(), item.getError()))
                    .toList();
        });
    }

    /** The full report: totals, green criteria, every problem list. */
    @GET
    @Path("/report")
    public MigrationReport report() {
        return reportService.buildReport(parameters.uploadMaxSizeBytes());
    }

    /**
     * CSV export for the HR/DPO walkthrough (runbook 2a-8). Served as
     * text/plain — the BFF route re-labels it text/csv with a download
     * disposition (backendFetch only passes text/plain through unparsed).
     */
    @GET
    @Path("/report/csv")
    @Produces(MediaType.TEXT_PLAIN)
    public Response reportCsv() {
        return Response.ok(reportService.buildCsv())
                .header("Content-Disposition", "attachment; filename=\"document-migration-report.csv\"")
                .build();
    }

    /** Directory for the manual-mapping picker (active AND terminated — D2). */
    @GET
    @Path("/users-directory")
    public List<SharePointFolderMatcherService.DirectoryEntry> usersDirectory() {
        return QuarkusTransaction.requiringNew().call(() -> matcherService.loadDirectory()).stream()
                .sorted((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()))
                .toList();
    }

    // ── Mapping transfer (2a-9): prod-confirmed matching → staging ────────

    /** uuid-keyed mapping export — no names travel (runbook 2a-9). */
    @GET
    @Path("/mappings/export")
    public SharePointMappingTransferService.MappingExport exportMappings() {
        return mappingTransferService.export();
    }

    /**
     * Import a mapping export. Fail-closed per entry (unknown users
     * rejected against THIS environment's user table); idempotent —
     * existing mappings are never overwritten.
     */
    @POST
    @Path("/mappings/import")
    @RolesAllowed({"documents:write"})
    public SharePointMappingTransferService.ImportSummary importMappings(
            SharePointMappingTransferService.MappingExport request) {
        return mappingTransferService.importMappings(request);
    }

    // ── Stage-3 resolution (admin card) ────────────────────────────────────

    /** One click Confirm on an AI suggestion ⇒ AI_CONFIRMED + MAPPED. */
    @POST
    @Path("/folders/{id}/confirm")
    @RolesAllowed({"documents:write"})
    public Response confirm(@PathParam("id") long id) {
        matcherService.confirmSuggestion(id);
        return Response.ok().build();
    }

    public record MapRequest(String userUuid) { }

    /** Admin picked a different user ⇒ MANUAL + MAPPED. */
    @POST
    @Path("/folders/{id}/map")
    @RolesAllowed({"documents:write"})
    public Response map(@PathParam("id") long id, MapRequest request) {
        matcherService.mapManually(id, request == null ? null : request.userUuid());
        return Response.ok().build();
    }

    public record SkipRequest(String note) { }

    /** Department folder / never-hired ⇒ SKIPPED with a mandatory note. */
    @POST
    @Path("/folders/{id}/skip")
    @RolesAllowed({"documents:write"})
    public Response skip(@PathParam("id") long id, SkipRequest request) {
        matcherService.skipFolder(id, request == null ? null : request.note());
        return Response.ok().build();
    }

    private static String resolve(Map<String, String> cache, String userUuid) {
        if (userUuid == null) return null;
        return cache.computeIfAbsent(userUuid, SharePointMigrationReportService::resolveUserName);
    }
}
