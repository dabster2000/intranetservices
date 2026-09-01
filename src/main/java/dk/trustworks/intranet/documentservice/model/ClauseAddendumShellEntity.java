package dk.trustworks.intranet.documentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The single shared "Tillæg til ansættelsesaftale" wrapper document
 * (template-clauses spec §4.9): header, person/company/date placeholders
 * resolved from the merged value map, and the {@code {{CLAUSES}}} body
 * anchor. When no active shell is uploaded, the composition falls back to
 * a minimal built-in shell so the send never fails on missing ops setup.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "clause_addendum_shells")
@EntityListeners(AuditEntityListener.class)
public class ClauseAddendumShellEntity extends PanacheEntityBase implements Auditable {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    /** S3 key of the shell .docx. */
    @Column(name = "file_uuid", nullable = false, length = 36)
    @NotBlank(message = "Shell file is required")
    private String fileUuid;

    @Column(name = "original_filename", length = 500)
    private String originalFilename;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 36, updatable = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String createdBy;

    @Column(name = "modified_by", length = 36)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String modifiedBy;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
    }

    // --- Panache finder methods ---

    public static Optional<ClauseAddendumShellEntity> findActive() {
        return find("active = true ORDER BY updatedAt DESC").firstResultOptional();
    }
}
