package dk.trustworks.intranet.documentservice.model;

import dk.trustworks.intranet.documentservice.model.enums.SigningSchemaType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JPA Entity for template signing schemas.
 * Represents a configured signing schema (MitID authentication method) for a document template.
 * When a template is used for signing, these schemas define which authentication methods are allowed.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "template_signing_schemas")
public class TemplateSigningSchemaEntity extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_uuid", nullable = false)
    @NotNull(message = "Template is required")
    private DocumentTemplateEntity template;

    @Column(name = "schema_type", nullable = false)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Schema type is required")
    private SigningSchemaType schemaType;

    @Column(name = "display_order", nullable = false)
    @Min(value = 1, message = "Display order must be at least 1")
    private int displayOrder = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
        }
        createdAt = LocalDateTime.now();
    }

    // --- Panache finder methods ---

    /**
     * Find all signing schemas for a specific template.
     *
     * @param template The template entity
     * @return List of signing schemas, sorted by displayOrder
     */
    public static List<TemplateSigningSchemaEntity> findByTemplate(DocumentTemplateEntity template) {
        return find("template = ?1 ORDER BY displayOrder", template).list();
    }

    /**
     * Delete all signing schemas for a specific template UUID.
     *
     * @param templateUuid The template UUID
     * @return Number of deleted records
     */
    public static long deleteByTemplateUuid(String templateUuid) {
        return delete("template.uuid = ?1", templateUuid);
    }
}
