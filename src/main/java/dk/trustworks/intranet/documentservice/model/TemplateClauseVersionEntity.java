package dk.trustworks.intranet.documentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One wording version of a clause (template-clauses spec §4.2, D7):
 * append-only history — editing wording means a new version with a change
 * note. A version becomes immutable the moment any document was sent with
 * it ({@code signing_case_clauses} references it); sent documents keep
 * the exact wording they used.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "template_clause_versions")
public class TemplateClauseVersionEntity extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @Column(name = "clause_uuid", nullable = false, length = 36)
    @NotBlank(message = "Clause is required")
    private String clauseUuid;

    /** Monotonic per clause; UNIQUE (clause_uuid, version_number). */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    /** S3 key of the .docx fragment. */
    @Column(name = "file_uuid", nullable = false, length = 36)
    @NotBlank(message = "Fragment file is required")
    private String fileUuid;

    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Column(name = "change_note", columnDefinition = "TEXT")
    private String changeNote;

    /** Set when the version becomes the clause's active version. */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "published_by", length = 36)
    private String publishedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, updatable = false)
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // --- Panache finder methods ---

    public static List<TemplateClauseVersionEntity> findByClause(String clauseUuid) {
        return list("clauseUuid = ?1 ORDER BY versionNumber DESC", clauseUuid);
    }

    public static int nextVersionNumber(String clauseUuid) {
        Integer max = getEntityManager()
                .createQuery("SELECT COALESCE(MAX(v.versionNumber), 0) FROM TemplateClauseVersionEntity v "
                        + "WHERE v.clauseUuid = :clauseUuid", Integer.class)
                .setParameter("clauseUuid", clauseUuid)
                .getSingleResult();
        return (max == null ? 0 : max) + 1;
    }
}
