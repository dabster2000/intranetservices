package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.dto.EmployeeDocumentCorpusRowDTO;
import dk.trustworks.intranet.documentservice.dto.EmployeeDocumentDTO;
import dk.trustworks.intranet.documentservice.dto.EmployeeDocumentHistoryEntryDTO;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.EmployeeDocumentAudit;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentAuditAction;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentRetentionService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.PatchCommand;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentsFeatureFlag;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentsParameters;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeEnforced;
import dk.trustworks.intranet.security.ScopeGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Document-scoped surface of the employee document store (spec §6.4):
 * content serving, PATCH, hard delete, the HR review queue, the GDPR
 * pair (DPO erase + DSAR export) and the two admin endpoints backing the
 * Settings → Employee Documents tab (stats + retention preview).
 *
 * <p>Client-level gate: {@code documents:read}/{@code documents:write}
 * scopes ({@code documents:gdpr} for the DPO pair). User-level gate: the
 * BFF ({@code checkEmployeeDataAccess} / {@code requireRoles}) — the
 * system-JWT model per the house architecture.</p>
 */
@JBossLog
@RequestScoped
@Path("/employee-documents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"documents:read"})
public class EmployeeDocumentResource {

    @Inject
    EmployeeDocumentService employeeDocumentService;

    @Inject
    EmployeeDocumentRetentionService retentionService;

    @Inject
    EmployeeDocumentsFeatureFlag featureFlag;

    @Inject
    EmployeeDocumentsParameters parameters;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    EntityManager em;

    @Inject
    ScopeGuard scope;

    static final String READ_SCOPE = "documents:read";
    static final String WRITE_SCOPE = "documents:write";

    /**
     * Phase 10.6 (access-intent Decision 13): a document's subject is its
     * owning employee. USER reaches OWN, HR/ADMIN reach ALL (V473) — exactly
     * the audience the BFF already enforces, so every guard here is
     * behaviour-preserving on deploy. The document is resolved BEFORE the
     * check so the verdict keys on the row actually served — the file-by-UUID
     * routes are this batch's highest-risk item (phase file 10.6/F-11).
     */
    private EmployeeDocument requireDocumentReach(String documentUuid, String permissionKey) {
        EmployeeDocument doc = employeeDocumentService.get(documentUuid);
        scope.requireSubjectWhenActor(permissionKey, doc.getUserUuid(),
                "Employee documents outside your reach");
        return doc;
    }

    // ── Serving ────────────────────────────────────────────────────────────

    /**
     * Stream a document's bytes. Audited (DOWNLOAD, X-Requested-By actor).
     * Served with {@code Content-Disposition: attachment} + nosniff —
     * never inline (spec §10; the corpus contains .eml/.msg).
     * Filename encoded per RFC 5987 (Danish characters are the norm).
     */
    @GET
    @Path("/{uuid}/content")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response content(@PathParam("uuid") String uuid) {
        requireDocumentReach(uuid, READ_SCOPE);
        EmployeeDocumentService.DocumentContent content =
                employeeDocumentService.download(uuid, requestHeaderHolder.getUserUuid());
        String encoded = URLEncoder.encode(content.filename(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return Response.ok(content.bytes())
                .type(content.contentType())
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }

    /** Metadata for a single document (BFF authz checks — no bytes). */
    @GET
    @Path("/{uuid}")
    public EmployeeDocumentDTO get(@PathParam("uuid") String uuid) {
        EmployeeDocument doc = requireDocumentReach(uuid, READ_SCOPE);
        return EmployeeDocumentDTO.from(doc, UserEmployeeDocumentResource.resolveName(doc.getUploadedBy()));
    }

    /**
     * A document's lifecycle history — filed / migrated / changed /
     * archived, oldest first. Access events are excluded (see
     * {@link EmployeeDocumentAudit#LIFECYCLE_ACTIONS}).
     *
     * <p>Ownership is enforced at the BFF, exactly as for
     * {@code /{uuid}/content}: this endpoint takes a document uuid and
     * the caller has already proven the document belongs to the file
     * they may read.</p>
     */
    @GET
    @Path("/{uuid}/history")
    public List<EmployeeDocumentHistoryEntryDTO> history(@PathParam("uuid") String uuid) {
        EmployeeDocument doc = employeeDocumentService.get(uuid);
        Map<String, String> nameCache = new HashMap<>();
        return EmployeeDocumentAudit.findLifecycleByDocument(uuid).stream()
                .map(a -> EmployeeDocumentHistoryEntryDTO.from(a, doc.getUserUuid(),
                        a.getActorUuid() == null ? null
                                : nameCache.computeIfAbsent(a.getActorUuid(),
                                        UserEmployeeDocumentResource::resolveName)))
                .toList();
    }

    /**
     * The documents archived for one signing case, {@code document_index}
     * order — what the signing UIs link to (spec §6.5.5). A case whose archival has not run yet returns an empty
     * list, which is the honest answer: PENDING means no bytes are filed.
     *
     * <p>Authorization mirrors {@link #get(String)} rather than the signing
     * case's own gate, and that difference is deliberate. A signing case is
     * readable by the subject's team lead; a document is not (spec §6.9
     * excludes team leads from the document surface). Resolving a case key
     * must therefore not become a side door into the file: every distinct
     * owner among the rows is put through the same subject reach check the
     * per-document endpoints use, so a caller who may see the case but not
     * the file gets 403 here and an empty section in the dialog.</p>
     *
     * <p>{@code includeHrOnly} is the BFF-set trust flag, exactly as on
     * {@code /users/{uuid}/employee-documents} — self-view never sets it.</p>
     */
    @GET
    @Path("/by-signing-case/{caseKey}")
    public List<EmployeeDocumentDTO> bySigningCase(
            @PathParam("caseKey") String caseKey,
            @QueryParam("includeHrOnly") @DefaultValue("false") boolean includeHrOnly,
            @QueryParam("includeArchived") @DefaultValue("false") boolean includeArchived) {
        List<EmployeeDocument> docs = employeeDocumentService.findBySigningCase(caseKey);
        docs.stream()
                .map(EmployeeDocument::getUserUuid)
                .distinct()
                .forEach(owner -> scope.requireSubjectWhenActor(READ_SCOPE, owner,
                        "Employee documents outside your reach"));
        return docs.stream()
                .filter(d -> includeHrOnly || !d.isHrOnly())
                .filter(d -> includeArchived || !d.isArchived())
                .map(d -> EmployeeDocumentDTO.from(d,
                        UserEmployeeDocumentResource.resolveName(d.getUploadedBy())))
                .toList();
    }

    // ── Mutations ──────────────────────────────────────────────────────────

    /**
     * Patch body — absent/null field = leave unchanged.
     *
     * <p>{@code displayName} is the exception: an explicit empty string
     * means "clear it and fall back to the original filename". A value
     * is normalized server-side (sanitized, original extension forced,
     * truncated) before it can reach a Content-Disposition header.</p>
     */
    public record PatchRequest(String category, String label, Boolean archived,
                               Boolean hrOnly, Boolean needsReview, String displayName) { }

    @PATCH
    @Path("/{uuid}")
    @RolesAllowed({"documents:write"})
    public EmployeeDocumentDTO patch(@PathParam("uuid") String uuid, PatchRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        requireDocumentReach(uuid, WRITE_SCOPE);
        EmployeeDocumentCategory category = null;
        if (request.category() != null && !request.category().isBlank()) {
            try {
                category = EmployeeDocumentCategory.valueOf(request.category().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown category: " + request.category());
            }
        }
        EmployeeDocument doc = employeeDocumentService.update(uuid,
                new PatchCommand(category, request.label(), request.archived(),
                        request.hrOnly(), request.needsReview(), request.displayName()),
                requestHeaderHolder.getUserUuid());
        return EmployeeDocumentDTO.from(doc, UserEmployeeDocumentResource.resolveName(doc.getUploadedBy()));
    }

    /**
     * Bulk update body — every field is optional; null = leave unchanged
     * on every document. {@code category} and {@code needsReview} were
     * added for the HR console: triaging the review queue or the
     * uncategorized backlog is "apply one category to a hand-picked set
     * and clear the flag", which was otherwise N single PATCHes.
     */
    public record BulkFlagsRequest(List<String> uuids, Boolean hrOnly, Boolean archived,
                                   String category, Boolean needsReview) { }

    /**
     * Apply one metadata change to many documents at once — the HR tab's
     * multi-select action bar and the HR console's bulk actions. One
     * transaction, one audit row per document (identical to N single
     * PATCHes, minus the partial-failure window).
     *
     * <p>Authorization is enforced at the BFF: the per-employee route
     * verifies every uuid belongs to the employee whose file is open
     * (IDOR defense, spec §10), and the console route is HR/ADMIN-gated
     * because its selections are cross-employee by design.</p>
     */
    @POST
    @Path("/bulk/flags")
    @RolesAllowed({"documents:write"})
    public EmployeeDocumentService.BulkFlagsSummary bulkFlags(BulkFlagsRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
        EmployeeDocumentCategory category = null;
        if (request.category() != null && !request.category().isBlank()) {
            try {
                category = EmployeeDocumentCategory.valueOf(request.category().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown category: " + request.category());
            }
        }
        return employeeDocumentService.updateBulk(request.uuids(), request.hrOnly(),
                request.archived(), category, request.needsReview(),
                requestHeaderHolder.getUserUuid());
    }

    /**
     * Every document in the store, across all employees, with the
     * employee's name resolved — the single fetch behind the HR document
     * console's three views (review queue, duplicates, uncategorized).
     *
     * <p>One endpoint rather than three because the views are three
     * questions about the same corpus, and answering them from one
     * snapshot is what keeps their counts consistent with each other.
     * The corpus is ~2,500 rows of metadata; the BFF is ADMIN/HR-gated
     * and strips {@code sha256} before anything reaches a browser.</p>
     */
    @GET
    @Path("/admin/corpus")
    public List<EmployeeDocumentCorpusRowDTO> corpus() {
        Map<String, String> nameCache = new HashMap<>();
        return EmployeeDocument.<EmployeeDocument>listAll().stream()
                .map(d -> EmployeeDocumentCorpusRowDTO.from(d,
                        nameCache.computeIfAbsent(d.getUserUuid(),
                                UserEmployeeDocumentResource::resolveName)))
                .toList();
    }

    /** Hard delete: all S3 versions + row + DELETE audit (spec §6.4). */
    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"documents:write"})
    public Response delete(@PathParam("uuid") String uuid) {
        requireDocumentReach(uuid, WRITE_SCOPE);
        employeeDocumentService.delete(uuid, requestHeaderHolder.getUserUuid());
        return Response.noContent().build();
    }

    // ── HR review queue ────────────────────────────────────────────────────

    /** All self-uploads pending HR categorization, across users (spec §6.4). */
    @GET
    @Path("/review-queue")
    @ScopeEnforced
    public List<EmployeeDocumentDTO> reviewQueue() {
        return UserEmployeeDocumentResource.toDTOs(employeeDocumentService.reviewQueue());
    }

    // ── GDPR (DPO flow) ────────────────────────────────────────────────────

    /**
     * Typed-confirmation body for the DPO erase (mirrors the candidate
     * DPO flow): the caller must literally type {@code ERASE} to proceed.
     */
    public record EraseRequest(String confirmation, String reason) { }

    /**
     * DPO on-request erasure: every document (all S3 versions), all rows,
     * audit detail scrub, one ERASE_ALL row (spec §6.10).
     */
    @POST
    @Path("/gdpr/users/{useruuid}/erase")
    @RolesAllowed({"documents:gdpr"})
    @ScopeEnforced
    public Response erase(@PathParam("useruuid") String useruuid, EraseRequest request) {
        if (request == null || !"ERASE".equals(request.confirmation())) {
            throw new BadRequestException("Confirmation mismatch — type ERASE to confirm");
        }
        int erased = employeeDocumentService.eraseAllForUser(
                useruuid, requestHeaderHolder.getUserUuid(),
                request.reason(), EmployeeDocumentAuditAction.ERASE_ALL);
        return Response.ok(Map.of("erasedDocuments", erased)).build();
    }

    /** DSAR export: zip of documents + manifest.json (metadata + audit trail). */
    @GET
    @Path("/gdpr/users/{useruuid}/export")
    @RolesAllowed({"documents:gdpr"})
    @ScopeEnforced
    @Produces("application/zip")
    public Response dsarExport(@PathParam("useruuid") String useruuid) {
        byte[] zip = employeeDocumentService.dsarExportZip(useruuid, requestHeaderHolder.getUserUuid());
        return Response.ok(zip)
                .header("Content-Disposition",
                        "attachment; filename*=UTF-8''employee-documents-dsar-" + useruuid + ".zip")
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }

    // ── Admin: settings-tab backing endpoints ──────────────────────────────

    /** Store stats for the settings tab's status strip (spec §6.7). */
    @GET
    @Path("/admin/stats")
    @ScopeEnforced
    public Map<String, Object> stats() {
        long documentCount = EmployeeDocument.count();
        Long userCount = em.createQuery(
                "select count(distinct d.userUuid) from EmployeeDocument d", Long.class).getSingleResult();
        long needsReviewCount = EmployeeDocument.count("needsReview = true");
        // Run summaries are written with the sentinel userUuid 'SYSTEM'
        // (per-user RETENTION_DELETE rows carry the erased user's uuid).
        EmployeeDocumentAudit lastRetentionRun = EmployeeDocumentAudit
                .find("action = ?1 and userUuid = 'SYSTEM' order by createdAt desc",
                        EmployeeDocumentAuditAction.RETENTION_DELETE)
                .firstResult();

        return Map.of(
                "documentCount", documentCount,
                "userCount", userCount == null ? 0L : userCount,
                "needsReviewCount", needsReviewCount,
                // The writer toggles are gone — every writer is S3-only now.
                // Hard-coded true for this ONE release so an older frontend
                // still reading them during the pipelined deploy renders
                // "on"; the next release removes the object.
                "writers", Map.of(
                        "signing", true,
                        "promotion", true,
                        "onboarding", true),
                "ui", Map.of(
                        "hrTab", featureFlag.isHrTabEnabled(),
                        "selfService", featureFlag.isSelfServiceEnabled()),
                "retention", Map.of(
                        "enabled", featureFlag.isRetentionEnabled(),
                        "years", parameters.retentionYears(),
                        "nightlyUserCap", parameters.nightlyUserCap(),
                        "lastRunAt", lastRetentionRun == null || lastRetentionRun.getCreatedAt() == null
                                ? "" : lastRetentionRun.getCreatedAt().toString(),
                        "lastRunDetail", lastRetentionRun == null || lastRetentionRun.getDetail() == null
                                ? "" : lastRetentionRun.getDetail()));
    }

    /**
     * Dry-run of the retention job under the CURRENT settings (spec §6.7):
     * which ex-employees the next run would erase documents for. Shown in
     * the settings tab and in the arming modal. ADMIN-gated at the BFF —
     * the response lists ex-employee names.
     */
    @GET
    @Path("/admin/retention-preview")
    @ScopeEnforced
    public Map<String, Object> retentionPreview() {
        int years = parameters.retentionYears();
        int cap = parameters.nightlyUserCap();
        List<EmployeeDocumentRetentionService.RetentionCandidate> eligible =
                retentionService.eligibleUsers(years);
        return Map.of(
                "retentionYears", years,
                "nightlyUserCap", cap,
                "eligibleCount", eligible.size(),
                "candidates", eligible.stream().map(c -> Map.of(
                        "userUuid", c.userUuid(),
                        "displayName", c.displayName(),
                        "terminatedDate", c.terminatedDate().toString(),
                        "deleteAfter", c.deleteAfter().toString(),
                        "documentCount", c.documentCount())).toList());
    }
}
