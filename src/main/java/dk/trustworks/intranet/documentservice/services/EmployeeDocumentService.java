package dk.trustworks.intranet.documentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.EmployeeDocumentAudit;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentAuditAction;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Domain service for the S3-only employee document store (spec §6.3).
 * Every byte enters and leaves the store through this class — no feature
 * talks to the bucket directly.
 *
 * <h3>Transactional shape</h3>
 * <p>Byte-level writes follow the proven {@code OnboardingUploadService}
 * order: validation (no TX) → S3 put (no TX — never hold a JDBC
 * connection across a remote call) → narrow-TX metadata row + audit →
 * compensating S3 delete when the row fails. S3 puts are strongly
 * consistent; there is no async copy monitoring and no PARTIAL state.</p>
 *
 * <h3>Validation (spec D6 / §6.3)</h3>
 * <p>Interactive uploads: size ≤ the settings-governed cap (hard ceiling
 * 25 MB), MIME allow-list pdf/jpg/png/eml/msg/docx/xlsx with magic-byte
 * verification, filename sanitized to {@code [A-Za-z0-9æøåÆØÅ ._()-]}.
 * System writers (signing archival, promotion) skip the size cap via
 * {@code bypassSizeCap} but never the audit.</p>
 */
@JBossLog
@ApplicationScoped
public class EmployeeDocumentService {

    /** MIME allow-list (D6). Keys = normalized content types; values = human hint for errors. */
    static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "message/rfc822",
            "application/vnd.ms-outlook",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    @Inject
    EmployeeDocumentStorageAdapter storage;

    @Inject
    EmployeeDocumentsParameters parameters;

    @Inject
    ObjectMapper objectMapper;

    // ── Commands / results ─────────────────────────────────────────────────

    /** Byte-level store command (uploads + signing archival). */
    public record StoreCommand(
            String userUuid,
            byte[] bytes,
            String filename,
            String contentType,
            EmployeeDocumentCategory category,
            String label,
            EmployeeDocumentSource source,
            String signingCaseKey,
            Integer documentIndex,
            boolean hrOnly,
            boolean needsReview,
            String actorUuid,
            String migratedFrom,
            boolean bypassSizeCap) {

        /** Interactive upload (HR or self-service). */
        public static StoreCommand upload(String userUuid, byte[] bytes, String filename,
                                          String contentType, EmployeeDocumentCategory category,
                                          String label, boolean hrOnly, boolean selfUpload,
                                          String actorUuid) {
            return new StoreCommand(userUuid, bytes, filename, contentType,
                    category, label,
                    selfUpload ? EmployeeDocumentSource.MANUAL_SELF : EmployeeDocumentSource.MANUAL_HR,
                    null, null,
                    !selfUpload && hrOnly,
                    selfUpload,
                    actorUuid, null, false);
        }
    }

    /** Server-side S3→S3 copy command (promotion / legacy re-home). */
    public record PromoteCommand(
            String userUuid,
            String srcBucket,
            String srcKey,
            String filename,
            EmployeeDocumentCategory category,
            String label,
            EmployeeDocumentSource source,
            String signingCaseKey,
            Integer documentIndex,
            String migratedFrom) { }

    /** Patch command — null field = leave unchanged. */
    public record PatchCommand(
            EmployeeDocumentCategory category,
            String label,
            Boolean archived,
            Boolean hrOnly,
            Boolean needsReview) { }

    /** Download result: bytes + serving metadata. */
    public record DocumentContent(byte[] bytes, String contentType, String filename) { }

    // ── Store (bytes) ──────────────────────────────────────────────────────

    /**
     * Validate and store bytes as a new document. Not {@code @Transactional}
     * — see class Javadoc for the ordering. Throws 400 on validation
     * failures, 413 on size violations, 409 on signing-idempotency
     * collisions (the caller treats it as already-stored).
     */
    public EmployeeDocument store(StoreCommand cmd) {
        requireValidUserUuid(cmd.userUuid());

        if (cmd.bytes() == null || cmd.bytes().length == 0) {
            throw badRequest("EMPTY_FILE", "The uploaded file is empty.");
        }
        if (!cmd.bypassSizeCap() && cmd.bytes().length > parameters.uploadMaxSizeBytes()) {
            throw new WebApplicationException(Response.status(413)
                    .entity("{\"error\":\"FILE_TOO_LARGE\",\"maxMb\":" + parameters.uploadMaxSizeMb() + "}")
                    .type(MediaType.APPLICATION_JSON).build());
        }

        String contentType = normalizeContentType(cmd.contentType());
        if (!ALLOWED_MIME_TYPES.contains(contentType)) {
            throw badRequest("UNSUPPORTED_MEDIA_TYPE",
                    "Allowed file types: PDF, JPG, PNG, EML, MSG, DOCX, XLSX.");
        }
        if (!magicMatches(contentType, cmd.bytes())) {
            // Asserted MIME and actual bytes disagree — refuse rather than
            // trust the caller's Content-Type (polyglot defense, spec §10).
            throw badRequest("CONTENT_MISMATCH",
                    "The file's content does not match its declared type.");
        }

        String safeFilename = sanitizeFilename(cmd.filename());
        if (safeFilename.isBlank()) {
            throw badRequest("INVALID_FILENAME", "The filename contains no usable characters.");
        }

        String docUuid = UUID.randomUUID().toString();
        String key = buildKey(cmd.userUuid(), docUuid, safeFilename);

        EmployeeDocument doc = new EmployeeDocument();
        doc.setUuid(docUuid);
        doc.setUserUuid(cmd.userUuid());
        doc.setS3Key(key);
        doc.setCategory(cmd.category() == null ? EmployeeDocumentCategory.OTHER : cmd.category());
        doc.setLabel(trimTo(cmd.label(), 255));
        doc.setOriginalFilename(trimTo(safeFilename, 500));
        doc.setContentType(contentType);
        doc.setFileSizeBytes(cmd.bytes().length);
        doc.setSha256(sha256Hex(cmd.bytes()));
        doc.setSource(cmd.source());
        doc.setSigningCaseKey(cmd.signingCaseKey());
        doc.setDocumentIndex(cmd.documentIndex());
        doc.setHrOnly(cmd.hrOnly());
        doc.setNeedsReview(cmd.needsReview());
        doc.setUploadedBy(cmd.actorUuid());
        doc.setMigratedFrom(trimTo(cmd.migratedFrom(), 1024));

        // 1. Upload outside any TX.
        storage.put(key, cmd.bytes(), contentType, objectMetadata(doc));

        // 2. Narrow-TX row + UPLOAD audit; compensating S3 delete on failure.
        try {
            persistWithAudit(doc, new EmployeeDocumentAudit(
                    docUuid, cmd.userUuid(), cmd.actorUuid(),
                    EmployeeDocumentAuditAction.UPLOAD,
                    auditDetail(doc)));
        } catch (RuntimeException e) {
            compensateStorage(key, docUuid);
            throw e;
        }

        log.infof("Employee document stored uuid=%s user=%s source=%s size=%d",
                docUuid, cmd.userUuid(), cmd.source(), cmd.bytes().length);
        return doc;
    }

    // ── Store (server-side copy) ───────────────────────────────────────────

    /**
     * Promotion / re-home: server-side {@code CopyObject} from another
     * bucket — no byte shuffling through the JVM (spec §6.3). Content type
     * is inferred from the filename extension; {@code sha256} stays null
     * (no bytes were read here). Idempotent per {@code migratedFrom}:
     * a re-run skips sources that already have a row.
     */
    public EmployeeDocument storeFromS3(PromoteCommand cmd) {
        requireValidUserUuid(cmd.userUuid());

        if (cmd.migratedFrom() != null) {
            EmployeeDocument existing = EmployeeDocument.findByProvenance(cmd.migratedFrom());
            if (existing != null) {
                log.debugf("storeFromS3: source %s already promoted as %s — skipping",
                        cmd.migratedFrom(), existing.getUuid());
                return existing;
            }
        }

        String safeFilename = sanitizeFilename(cmd.filename());
        if (safeFilename.isBlank()) safeFilename = "document.pdf";

        String docUuid = UUID.randomUUID().toString();
        String key = buildKey(cmd.userUuid(), docUuid, safeFilename);
        String contentType = contentTypeFromFilename(safeFilename);

        EmployeeDocument doc = new EmployeeDocument();
        doc.setUuid(docUuid);
        doc.setUserUuid(cmd.userUuid());
        doc.setS3Key(key);
        doc.setCategory(cmd.category() == null ? EmployeeDocumentCategory.OTHER : cmd.category());
        doc.setLabel(trimTo(cmd.label(), 255));
        doc.setOriginalFilename(trimTo(safeFilename, 500));
        doc.setContentType(contentType);
        doc.setSource(cmd.source());
        doc.setSigningCaseKey(cmd.signingCaseKey());
        doc.setDocumentIndex(cmd.documentIndex());
        doc.setMigratedFrom(trimTo(cmd.migratedFrom(), 1024));

        long size = storage.copyFromBucket(cmd.srcBucket(), cmd.srcKey(), key, contentType, objectMetadata(doc));
        doc.setFileSizeBytes(size);

        try {
            persistWithAudit(doc, new EmployeeDocumentAudit(
                    docUuid, cmd.userUuid(), null,
                    EmployeeDocumentAuditAction.UPLOAD,
                    auditDetail(doc)));
        } catch (RuntimeException e) {
            compensateStorage(key, docUuid);
            throw e;
        }

        log.infof("Employee document promoted uuid=%s user=%s from=%s/%s size=%d",
                docUuid, cmd.userUuid(), cmd.srcBucket(), cmd.srcKey(), size);
        return doc;
    }

    // ── Read side ──────────────────────────────────────────────────────────

    /**
     * List a user's documents. {@code includeHrOnly=false} is the employee
     * self-view (spec §6.9); {@code includeArchived=false} hides archived
     * rows (the default view in both surfaces).
     */
    public List<EmployeeDocument> list(String userUuid, boolean includeHrOnly, boolean includeArchived) {
        return EmployeeDocument.findByUser(userUuid).stream()
                .filter(d -> includeHrOnly || !d.isHrOnly())
                .filter(d -> includeArchived || !d.isArchived())
                .toList();
    }

    /** All self-uploads pending HR categorization, oldest first (HR review queue). */
    public List<EmployeeDocument> reviewQueue() {
        return EmployeeDocument.findNeedingReview();
    }

    /**
     * Fetch a document's bytes for serving. Writes a best-effort DOWNLOAD
     * audit row (never blocks the read — spec §6.4).
     */
    public DocumentContent download(String docUuid, String actorUuid) {
        EmployeeDocument doc = requireDocument(docUuid);
        EmployeeDocumentStorageAdapter.StoredObject stored = storage.get(doc.getS3Key());
        try {
            audit(new EmployeeDocumentAudit(doc.getUuid(), doc.getUserUuid(), actorUuid,
                    EmployeeDocumentAuditAction.DOWNLOAD, auditDetail(doc)));
        } catch (RuntimeException e) {
            log.warnf(e, "DOWNLOAD audit failed for document %s (read served anyway)", docUuid);
        }
        String contentType = stored.contentType() != null ? stored.contentType() : doc.getContentType();
        return new DocumentContent(stored.bytes(), contentType, doc.getOriginalFilename());
    }

    /** Metadata lookup without bytes (BFF authz checks). 404 when missing. */
    public EmployeeDocument get(String docUuid) {
        return requireDocument(docUuid);
    }

    // ── Mutations ──────────────────────────────────────────────────────────

    /**
     * Patch category/label/archived/hr_only/needs_review. Clearing
     * {@code needsReview} together with a category set is how HR approves
     * a self-upload (spec §6.8A).
     */
    @Transactional
    public EmployeeDocument update(String docUuid, PatchCommand cmd, String actorUuid) {
        EmployeeDocument doc = requireDocument(docUuid);
        boolean archiving = cmd.archived() != null && cmd.archived() && !doc.isArchived();

        if (cmd.category() != null) doc.setCategory(cmd.category());
        if (cmd.label() != null) doc.setLabel(trimTo(cmd.label().isBlank() ? null : cmd.label(), 255));
        if (cmd.archived() != null) doc.setArchived(cmd.archived());
        if (cmd.hrOnly() != null) doc.setHrOnly(cmd.hrOnly());
        if (cmd.needsReview() != null) doc.setNeedsReview(cmd.needsReview());
        doc.persist();

        audit(new EmployeeDocumentAudit(doc.getUuid(), doc.getUserUuid(), actorUuid,
                archiving ? EmployeeDocumentAuditAction.ARCHIVE : EmployeeDocumentAuditAction.UPDATE,
                auditDetail(doc)));
        return doc;
    }

    /**
     * Hard delete: every S3 version first (privacy over referential
     * comfort — a failed row delete leaves a row pointing at nothing,
     * which a retry cleans up; the reverse would orphan bytes), then the
     * row + DELETE audit.
     */
    public void delete(String docUuid, String actorUuid) {
        EmployeeDocument doc = requireDocument(docUuid);
        storage.deleteAllVersions(doc.getS3Key());
        deleteRowWithAudit(doc.getUuid(), new EmployeeDocumentAudit(
                doc.getUuid(), doc.getUserUuid(), actorUuid,
                EmployeeDocumentAuditAction.DELETE, auditDetail(doc)));
        log.infof("Employee document deleted uuid=%s user=%s by=%s", docUuid, doc.getUserUuid(), actorUuid);
    }

    /**
     * GDPR erasure (DPO on-request or retention job): delete every S3
     * object version under the user's prefix, delete all rows, scrub the
     * audit trail's {@code detail} fields (the fact of processing is
     * retained, the content PII is not), and write one ERASE_ALL /
     * RETENTION_DELETE row. Idempotent.
     *
     * @return number of documents erased
     */
    public int eraseAllForUser(String userUuid, String actorUuid, String reason,
                               EmployeeDocumentAuditAction action) {
        requireValidUserUuid(userUuid);
        List<EmployeeDocument> docs = EmployeeDocument.findByUser(userUuid);

        // S3 first (privacy first): per-row keys, then the whole prefix as
        // belt-and-braces for objects whose row is already gone.
        for (EmployeeDocument doc : docs) {
            storage.deleteAllVersions(doc.getS3Key());
        }
        int sweptVersions = storage.deleteAllVersionsUnderPrefix("users/" + userUuid + "/");

        eraseRowsAndScrub(userUuid, new EmployeeDocumentAudit(
                null, userUuid, actorUuid, action,
                "documents=" + docs.size() + "; sweptVersions=" + sweptVersions
                        + (reason == null || reason.isBlank() ? "" : "; reason=" + reason)));

        log.infof("Employee documents erased user=%s action=%s documents=%d sweptVersions=%d",
                userUuid, action, docs.size(), sweptVersions);
        return docs.size();
    }

    // ── DSAR ───────────────────────────────────────────────────────────────

    /**
     * DSAR export: zip of every document (bytes) + {@code manifest.json}
     * with metadata and the full audit trail (spec §6.10). Audited as
     * DSAR_EXPORT.
     */
    public byte[] dsarExportZip(String userUuid, String actorUuid) {
        requireValidUserUuid(userUuid);
        List<EmployeeDocument> docs = EmployeeDocument.findByUser(userUuid);
        List<EmployeeDocumentAudit> trail = EmployeeDocumentAudit.findByUser(userUuid);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            ArrayNode manifestDocs = objectMapper.createArrayNode();
            for (EmployeeDocument doc : docs) {
                String entryName = "documents/" + doc.getUuid().substring(0, 8) + "-" + doc.getOriginalFilename();
                try {
                    byte[] bytes = storage.get(doc.getS3Key()).bytes();
                    zip.putNextEntry(new ZipEntry(entryName));
                    zip.write(bytes);
                    zip.closeEntry();
                } catch (RuntimeException e) {
                    log.errorf(e, "DSAR export: could not fetch %s for user %s", doc.getS3Key(), userUuid);
                    entryName = null;
                }
                ObjectNode node = manifestDocs.addObject();
                node.put("uuid", doc.getUuid());
                node.put("filename", doc.getOriginalFilename());
                node.put("category", doc.getCategory().name());
                node.put("label", doc.getLabel());
                node.put("source", doc.getSource().name());
                node.put("contentType", doc.getContentType());
                node.put("fileSizeBytes", doc.getFileSizeBytes());
                node.put("sha256", doc.getSha256());
                node.put("archived", doc.isArchived());
                node.put("createdAt", doc.getCreatedAt() == null ? null : doc.getCreatedAt().toString());
                node.put("zipEntry", entryName);
            }

            ArrayNode manifestAudit = objectMapper.createArrayNode();
            for (EmployeeDocumentAudit row : trail) {
                ObjectNode node = manifestAudit.addObject();
                node.put("action", row.getAction().name());
                node.put("documentUuid", row.getDocumentUuid());
                node.put("actorUuid", row.getActorUuid());
                node.put("detail", row.getDetail());
                node.put("at", row.getCreatedAt() == null ? null : row.getCreatedAt().toString());
            }

            ObjectNode manifest = objectMapper.createObjectNode();
            manifest.put("userUuid", userUuid);
            manifest.put("documentCount", docs.size());
            manifest.set("documents", manifestDocs);
            manifest.set("auditTrail", manifestAudit);

            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();
        } catch (IOException e) {
            throw new WebApplicationException("DSAR export failed: " + e.getMessage(), 500);
        }

        audit(new EmployeeDocumentAudit(null, userUuid, actorUuid,
                EmployeeDocumentAuditAction.DSAR_EXPORT, "documents=" + docs.size()));
        return baos.toByteArray();
    }

    // ── Narrow transactions ────────────────────────────────────────────────

    @Transactional
    void persistWithAudit(EmployeeDocument doc, EmployeeDocumentAudit auditRow) {
        doc.persist();
        auditRow.persist();
    }

    @Transactional
    void audit(EmployeeDocumentAudit auditRow) {
        auditRow.persist();
    }

    @Transactional
    void deleteRowWithAudit(String docUuid, EmployeeDocumentAudit auditRow) {
        EmployeeDocument.deleteById(docUuid);
        auditRow.persist();
    }

    @Transactional
    void eraseRowsAndScrub(String userUuid, EmployeeDocumentAudit eraseRow) {
        EmployeeDocument.delete("userUuid", userUuid);
        EmployeeDocumentAudit.update("detail = null WHERE userUuid = ?1", userUuid);
        eraseRow.persist();
    }

    // ── Validation helpers (package-private for unit tests) ────────────────

    /**
     * Positive allow-list sanitizer (spec §6.3): keep
     * {@code [A-Za-z0-9æøåÆØÅ ._()-]}, collapse runs of dots (no
     * {@code ..} resurfacing), trim. Danish characters are the norm in
     * this corpus and deliberately preserved.
     */
    static String sanitizeFilename(String raw) {
        if (raw == null) return "";
        String filtered = raw.replaceAll("[^A-Za-z0-9æøåÆØÅ ._()\\-]", "");
        filtered = filtered.replaceAll("\\.{2,}", ".");
        return filtered.trim();
    }

    /**
     * ASCII slug for the S3 key (spec §6.3): lowercase, æ→ae/ø→oe/å→aa,
     * everything outside {@code [a-z0-9._-]} becomes {@code -}, collapsed,
     * max 80 chars. Keeps the AWS console browsable; the UUID prefix
     * guarantees uniqueness.
     */
    static String asciiSlug(String filename, int maxLength) {
        if (filename == null) return "file";
        String slug = filename.toLowerCase()
                .replace("æ", "ae").replace("ø", "oe").replace("å", "aa")
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("-+\\.", ".")
                .replaceAll("^[-.]+|[-.]+$", "");
        if (slug.isBlank()) slug = "file";
        return slug.length() > maxLength ? slug.substring(0, maxLength) : slug;
    }

    static String buildKey(String userUuid, String docUuid, String filename) {
        return "users/" + userUuid + "/" + docUuid + "-" + asciiSlug(filename, 80);
    }

    /** Strip charset suffix and lowercase. */
    static String normalizeContentType(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        String bare = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return bare.trim().toLowerCase();
    }

    /**
     * Magic-byte verification per allow-listed type (spec D6/§10):
     * <ul>
     *   <li>PDF: {@code %PDF}</li>
     *   <li>JPEG: {@code FF D8 FF}</li>
     *   <li>PNG: {@code 89 50 4E 47 0D 0A 1A 0A}</li>
     *   <li>DOCX/XLSX: ZIP magic {@code PK\03\04}</li>
     *   <li>MSG: OLE2 compound magic {@code D0 CF 11 E0} (implementation
     *       finding: .msg is binary OLE2, not printable — the spec's
     *       "lenient printable header" check only fits .eml)</li>
     *   <li>EML: lenient printable-ASCII header check (first bytes must
     *       look like RFC 5322 header text)</li>
     * </ul>
     */
    static boolean magicMatches(String contentType, byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        return switch (contentType) {
            case "application/pdf" ->
                    bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
            case "image/jpeg" ->
                    (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8
                    && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                    && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                    bytes[0] == 0x50 && bytes[1] == 0x4b && bytes[2] == 0x03 && bytes[3] == 0x04;
            case "application/vnd.ms-outlook" ->
                    (bytes[0] & 0xff) == 0xd0 && (bytes[1] & 0xff) == 0xcf
                            && (bytes[2] & 0xff) == 0x11 && (bytes[3] & 0xff) == 0xe0;
            case "message/rfc822" -> looksLikeEmlHeader(bytes);
            default -> false;
        };
    }

    /**
     * Lenient .eml check: the first 128 bytes must be printable ASCII /
     * whitespace and contain a colon (an RFC 5322 header line).
     */
    static boolean looksLikeEmlHeader(byte[] bytes) {
        int limit = Math.min(bytes.length, 128);
        boolean sawColon = false;
        for (int i = 0; i < limit; i++) {
            int b = bytes[i] & 0xff;
            if (b == ':') sawColon = true;
            boolean printable = (b >= 0x20 && b < 0x7f) || b == '\r' || b == '\n' || b == '\t';
            if (!printable) return false;
        }
        return sawColon;
    }

    /** Infer serving content type from the filename extension (promotion path). */
    static String contentTypeFromFilename(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".eml")) return "message/rfc822";
        if (lower.endsWith(".msg")) return "application/vnd.ms-outlook";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        return "application/octet-stream";
    }

    /** Truncate to a column width; null-safe. */
    static String trimTo(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    private void compensateStorage(String key, String docUuid) {
        try {
            storage.deleteAllVersions(key);
            log.infof("Compensating S3 delete OK for document %s", docUuid);
        } catch (RuntimeException e) {
            log.errorf(e, "ORPHAN: compensating S3 delete FAILED key=%s document=%s", key, docUuid);
        }
    }

    private Map<String, String> objectMetadata(EmployeeDocument doc) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("user-uuid", doc.getUserUuid());
        metadata.put("document-uuid", doc.getUuid());
        metadata.put("category", doc.getCategory().name());
        metadata.put("source", doc.getSource().name());
        return metadata;
    }

    private static String auditDetail(EmployeeDocument doc) {
        return doc.getOriginalFilename() + " [" + doc.getCategory().name() + "]";
    }

    private static EmployeeDocument requireDocument(String docUuid) {
        EmployeeDocument doc = EmployeeDocument.findById(docUuid);
        if (doc == null) throw new NotFoundException("Employee document not found: " + docUuid);
        return doc;
    }

    private static void requireValidUserUuid(String userUuid) {
        try {
            UUID.fromString(userUuid);
        } catch (Exception e) {
            throw badRequest("INVALID_USER_UUID", "Not a valid user uuid.");
        }
    }

    private static BadRequestException badRequest(String code, String message) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
