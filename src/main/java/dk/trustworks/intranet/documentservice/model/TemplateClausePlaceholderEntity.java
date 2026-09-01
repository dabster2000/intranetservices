package dk.trustworks.intranet.documentservice.model;

import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A typed parameter on a clause (template-clauses spec §4.3). Parameters
 * live on the <b>clause</b> — the stable identity — not the version, and
 * mirror {@link TemplatePlaceholderEntity} exactly, plus:
 * <ul>
 *   <li>{@code registryField} — maps the parameter into a first-class
 *       Phase 3 registry column (AMOUNT, CURRENCY, VALID_FROM, VALID_TO,
 *       EFFECTIVE_DATE); NULL lands in {@code parameters_json} only.</li>
 *   <li>the prefix convention: keys are prefixed by clause
 *       ({@code GB_AMOUNT}) so they never collide with base-template
 *       keys — validated on save and on template link.</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "template_clause_placeholders")
public class TemplateClausePlaceholderEntity extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @Column(name = "clause_uuid", nullable = false, length = 36)
    @NotBlank(message = "Clause is required")
    private String clauseUuid;

    @Column(name = "placeholder_key", nullable = false, length = 100)
    @NotBlank(message = "Placeholder key is required")
    private String placeholderKey;

    @Column(nullable = false)
    @NotBlank(message = "Label is required")
    private String label;

    @Column(name = "field_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Field type is required")
    private FieldType fieldType;

    @Column(nullable = false)
    private boolean required = false;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "default_value", columnDefinition = "TEXT")
    private String defaultValue;

    @Column(name = "help_text", columnDefinition = "TEXT")
    private String helpText;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Source is required")
    private DataSource source = DataSource.MANUAL;

    /** Named field {@link #source} resolves; NULL = manual entry. */
    @Column(name = "source_field", length = 50)
    private String sourceField;

    /** Phase 3 registry column this parameter maps to; NULL = JSON only. */
    @Column(name = "registry_field", length = 50)
    private String registryField;

    @Column(name = "field_group", length = 100)
    private String fieldGroup;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_rules", columnDefinition = "JSON")
    private String validationRules;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "select_options", columnDefinition = "JSON")
    private String selectOptions;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // --- Panache finder methods ---

    public static List<TemplateClausePlaceholderEntity> findByClause(String clauseUuid) {
        return list("clauseUuid = ?1 ORDER BY displayOrder, label", clauseUuid);
    }
}
