package dk.trustworks.intranet.documentservice.model;

import dk.trustworks.intranet.documentservice.model.enums.DataSource;
import dk.trustworks.intranet.documentservice.model.enums.FieldType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
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
 * JPA Entity for template placeholders.
 * Represents a single placeholder field within a document template.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "template_placeholders")
public class TemplatePlaceholderEntity extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_uuid", nullable = false)
    @NotNull(message = "Template is required")
    private DocumentTemplateEntity template;

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

    /**
     * Find all placeholders for a specific template.
     *
     * @param template The template entity
     * @return List of placeholders, sorted by displayOrder
     */
    public static List<TemplatePlaceholderEntity> findByTemplate(DocumentTemplateEntity template) {
        return find("template = ?1 ORDER BY displayOrder, label", template).list();
    }

    /**
     * Find a specific placeholder by template and key.
     *
     * @param template The template entity
     * @param placeholderKey The placeholder key
     * @return The placeholder, or null if not found
     */
    public static TemplatePlaceholderEntity findByTemplateAndKey(DocumentTemplateEntity template, String placeholderKey) {
        return find("template = ?1 AND placeholderKey = ?2", template, placeholderKey).firstResult();
    }
}
