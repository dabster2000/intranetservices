package dk.trustworks.intranet.agreementservice.model;

import dk.trustworks.intranet.agreementservice.model.enums.BackfillItemStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * One discovered PDF in the backfill corpus (template-clauses spec
 * §4.8/§10): where it lives in SharePoint, what the extraction proposed
 * ({@code proposal_json} is an ARRAY — one call proposes zero-or-more
 * records) and the human review state. Nothing enters
 * {@code employee_agreements} without a confirm (D8).
 *
 * <p>Idempotency: {@code (user_uuid, doc_sha256)} is UNIQUE — an
 * identical circular in two employees' folders yields one item per
 * employee, while a moved/copied file within one folder collapses to a
 * single item. The stored {@code sharepoint_item_id + e_tag} pair lets
 * re-runs skip unchanged files without re-downloading.</p>
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "agreement_backfill_items")
public class AgreementBackfillItem extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @Column(name = "run_uuid", nullable = false, length = 36)
    private String runUuid;

    /** The employee the document belongs to — corpus is user-keyed only. */
    @Column(name = "user_uuid", nullable = false, length = 36)
    private String userUuid;

    /**
     * The {@code employee_documents.uuid} the item was extracted from
     * (the S3 corpus, V554). NULL on legacy V549-era SharePoint-walk
     * items, whose preview still streams from Graph.
     */
    @Column(name = "employee_document_uuid", length = 36)
    private String employeeDocumentUuid;

    /** Legacy Graph pointer (V549-era items); NULL on S3-sourced items. */
    @Column(name = "site_url", length = 500)
    private String siteUrl;

    /** Legacy Graph pointer (V549-era items); NULL on S3-sourced items. */
    @Column(name = "drive_id", length = 255)
    private String driveId;

    /** Legacy Graph pointer (V549-era items); NULL on S3-sourced items. */
    @Column(name = "sharepoint_item_id", length = 255)
    private String sharepointItemId;

    @Column(name = "e_tag", length = 255)
    private String eTag;

    /** SharePoint link; becomes {@code employee_agreements.document_url} on confirm. */
    @Column(name = "web_url", length = 1000)
    private String webUrl;

    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "doc_sha256", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String docSha256;

    /** {@link BackfillItemStatus} name. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** JSON array of proposals; see {@code AgreementExtractionService.Proposal}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal_json", columnDefinition = "JSON")
    private String proposalJson;

    @Column(name = "extraction_note", length = 500)
    private String extractionNote;

    @Column(name = "reviewed_by", length = 36)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** JSON array of the employee_agreements uuids written by the confirm. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "created_agreements_json", columnDefinition = "JSON")
    private String createdAgreementsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public static Optional<AgreementBackfillItem> findByUserAndSha(String userUuid, String docSha256) {
        return find("userUuid = ?1 AND docSha256 = ?2", userUuid, docSha256).firstResultOptional();
    }

    public static Optional<AgreementBackfillItem> findByUserAndItemId(String userUuid, String sharepointItemId) {
        return find("userUuid = ?1 AND sharepointItemId = ?2 ORDER BY createdAt DESC",
                userUuid, sharepointItemId).firstResultOptional();
    }
}
