package dk.trustworks.intranet.documentservice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.documentservice.model.enums.ClauseRenderMode;
import dk.trustworks.intranet.documentservice.model.enums.ClauseStatus;
import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import dk.trustworks.intranet.model.Auditable;
import dk.trustworks.intranet.security.AuditEntityListener;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One reusable negotiated term (template-clauses spec §4.1): standard
 * Danish wording as a versioned Word fragment, typed parameters, a render
 * mode (D1) and an agreement type that tells the Phase 3 registry what to
 * record. Wording history lives in {@link TemplateClauseVersionEntity};
 * parameters on {@link TemplateClausePlaceholderEntity} (stable clause
 * identity, not the version).
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "template_clauses")
@EntityListeners(AuditEntityListener.class)
public class TemplateClauseEntity extends PanacheEntityBase implements Auditable {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @Column(name = "clause_key", nullable = false, length = 100)
    @NotBlank(message = "Clause key is required")
    private String clauseKey;

    @Column(nullable = false)
    @NotBlank(message = "Name is required")
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Registry vocabulary key (Phase 3), e.g. {@code GARANTIBONUS}. */
    @Column(name = "agreement_type", length = 50)
    private String agreementType;

    @Column(name = "render_mode", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Render mode is required")
    private ClauseRenderMode renderMode = ClauseRenderMode.ADDENDUM;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Category is required")
    private TemplateCategory category;

    /** Offer on every template of {@link #category} without an explicit link. */
    @Column(name = "offer_on_category", nullable = false)
    private boolean offerOnCategory = false;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Status is required")
    private ClauseStatus status = ClauseStatus.DRAFT;

    /** {@code template_clause_versions.uuid} used for new documents. */
    @Column(name = "active_version_uuid", length = 36)
    private String activeVersionUuid;

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

    public static Optional<TemplateClauseEntity> findByClauseKey(String clauseKey) {
        return find("clauseKey = ?1", clauseKey).firstResultOptional();
    }

    public static List<TemplateClauseEntity> findAllOrdered() {
        return list("ORDER BY status, name");
    }

    /** ACTIVE clauses offered category-wide (no explicit link needed). */
    public static List<TemplateClauseEntity> findActiveByCategoryOffer(TemplateCategory category) {
        return list("category = ?1 AND offerOnCategory = true AND status = ?2",
                category, ClauseStatus.ACTIVE);
    }
}
