package dk.trustworks.intranet.documentservice.model;

import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Metadata row for a document in the S3-only employee document store
 * (spec §6.2, V452). Bytes live in the dedicated bucket
 * {@code trustworks-employee-documents-{env}} under
 * {@code users/{userUuid}/{uuid}-{slug}}; this row is the system of
 * record for everything else (category, provenance, flags, audit
 * linkage). Rows are only ever written through
 * {@link dk.trustworks.intranet.documentservice.services.EmployeeDocumentService}.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "employee_documents")
public class EmployeeDocument extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "user_uuid", nullable = false, length = 36)
    private String userUuid;

    @Column(name = "s3_key", nullable = false, length = 512)
    private String s3Key;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false)
    private EmployeeDocumentCategory category = EmployeeDocumentCategory.OTHER;

    /** Free text; the Phase-2 migration puts the relative folder path here. */
    @Column(name = "label", length = 255)
    private String label;

    /**
     * Immutable. The signing linkage matches this byte-for-byte
     * (decision A4) and HR compared it against the legacy store during
     * migration verification — nothing renames it.
     * Standardized names
     * live in {@link #displayName}.
     */
    @Column(name = "original_filename", nullable = false, length = 500)
    private String originalFilename;

    /**
     * Standardized display name {@code {date}_{CATEGORY}_{subject}.{ext}}
     * (V476). What the download is called and what both UIs show; NULL
     * ⇒ fall back to {@link #originalFilename}.
     */
    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    /** Hex sha256 of the bytes; null for server-side copies (promotion) until backfilled. */
    @Column(name = "sha256", length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private EmployeeDocumentSource source;

    @Column(name = "signing_case_key", length = 255)
    private String signingCaseKey;

    @Column(name = "document_index")
    private Integer documentIndex;

    /** Hidden from employee self-view (spec D1/§6.9). */
    @Column(name = "hr_only", nullable = false)
    private boolean hrOnly;

    /** Replaced the legacy "Arkiv" folders (spec D3). */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    /** Self-uploads pending HR categorization (spec D1). */
    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    /** Actor user uuid; null for system writers (batchlets, promotion). */
    @Column(name = "uploaded_by", length = 36)
    private String uploadedBy;

    /**
     * Provenance, kept for the audit trail: the legacy SharePoint webUrl
     * the completed migration copied the bytes from, or
     * {@code files:{uuid}} for re-homed/promoted objects.
     */
    @Column(name = "migrated_from", length = 1024)
    private String migratedFrom;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    // ── Queries ────────────────────────────────────────────────────────────

    public static List<EmployeeDocument> findByUser(String userUuid) {
        return list("userUuid = ?1 ORDER BY createdAt DESC", userUuid);
    }

    public static List<EmployeeDocument> findNeedingReview() {
        return list("needsReview = true ORDER BY createdAt ASC");
    }

    public static EmployeeDocument findBySigningCase(String signingCaseKey, int documentIndex) {
        return find("signingCaseKey = ?1 AND documentIndex = ?2", signingCaseKey, documentIndex).firstResult();
    }

    public static List<EmployeeDocument> findBySigningCase(String signingCaseKey) {
        return list("signingCaseKey = ?1 ORDER BY documentIndex ASC", signingCaseKey);
    }

    /** Same bytes for the same employee — the hash is the identity (null sha256 rows never match). */
    public static EmployeeDocument findByUserAndSha256(String userUuid, String sha256) {
        if (sha256 == null || sha256.isBlank()) return null;
        return find("userUuid = ?1 AND sha256 = ?2 ORDER BY createdAt", userUuid, sha256).firstResult();
    }

    public static EmployeeDocument findByProvenance(String migratedFrom) {
        return find("migratedFrom = ?1", migratedFrom).firstResult();
    }
}
