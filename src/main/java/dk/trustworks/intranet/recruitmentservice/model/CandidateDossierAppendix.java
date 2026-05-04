package dk.trustworks.intranet.recruitmentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One file appendix attached to a {@link CandidateDossier}.
 * <p>
 * The actual file bytes live in S3 keyed by {@link #fileUuid}; this entity
 * stores only the metadata needed to render the dossier's appendix list and
 * to track display ordering. {@code original_filename} is the recipient-
 * facing filename and is sanitised by the application layer before insert
 * (the database only enforces non-empty after trim via
 * {@code chk_cda_filename_not_empty}).
 * <p>
 * Pure child of the {@code CandidateDossier} aggregate — never referenced
 * outside that aggregate's transaction boundary.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "candidate_dossier_appendices")
public class CandidateDossierAppendix extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36, nullable = false, updatable = false)
    private String uuid;

    /** Internal FK to {@code candidate_dossiers.uuid}. */
    @Column(name = "dossier_uuid", length = 36, nullable = false)
    private String dossierUuid;

    /** S3 storage key. Mirrors {@code template_documents.file_uuid}. */
    @Column(name = "file_uuid", length = 36, nullable = false)
    private String fileUuid;

    @Column(name = "original_filename", length = 500, nullable = false)
    private String originalFilename;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    /** 1-based ordering within the dossier. */
    @Column(name = "display_order", nullable = false)
    private int displayOrder = 1;

    /** Soft FK to {@code users.uuid} of the actor who uploaded this appendix. */
    @Column(name = "uploaded_by_useruuid", length = 36, nullable = false)
    private String uploadedByUseruuid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
