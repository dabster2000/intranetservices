package dk.trustworks.intranet.documentservice.resources;

import dk.trustworks.intranet.documentservice.dto.EmployeeDocumentDTO;
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
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
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
        EmployeeDocument doc = employeeDocumentService.get(uuid);
        return EmployeeDocumentDTO.from(doc, UserEmployeeDocumentResource.resolveName(doc.getUploadedBy()));
    }

    // ── Mutations ──────────────────────────────────────────────────────────

    /** Patch body — absent/null field = leave unchanged. */
    public record PatchRequest(String category, String label, Boolean archived,
                               Boolean hrOnly, Boolean needsReview) { }

    @PATCH
    @Path("/{uuid}")
    @RolesAllowed({"documents:write"})
    public EmployeeDocumentDTO patch(@PathParam("uuid") String uuid, PatchRequest request) {
        if (request == null) throw new BadRequestException("Request body is required");
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
                        request.hrOnly(), request.needsReview()),
                requestHeaderHolder.getUserUuid());
        return EmployeeDocumentDTO.from(doc, UserEmployeeDocumentResource.resolveName(doc.getUploadedBy()));
    }

    /** Hard delete: all S3 versions + row + DELETE audit (spec §6.4). */
    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"documents:write"})
    public Response delete(@PathParam("uuid") String uuid) {
        employeeDocumentService.delete(uuid, requestHeaderHolder.getUserUuid());
        return Response.noContent().build();
    }

    // ── HR review queue ────────────────────────────────────────────────────

    /** All self-uploads pending HR categorization, across users (spec §6.4). */
    @GET
    @Path("/review-queue")
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
                "writers", Map.of(
                        "signing", featureFlag.isSigningWriterEnabled(),
                        "promotion", featureFlag.isPromotionWriterEnabled(),
                        "onboarding", featureFlag.isOnboardingWriterEnabled()),
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
