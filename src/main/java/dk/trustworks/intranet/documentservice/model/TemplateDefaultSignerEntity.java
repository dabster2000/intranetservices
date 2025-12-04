package dk.trustworks.intranet.documentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JPA Entity for template default signers.
 * Represents a pre-configured signer for a document template.
 * When a template is selected for signing, these signers pre-populate the signer configuration dialog.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "template_default_signers")
public class TemplateDefaultSignerEntity extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_uuid", nullable = false)
    @NotNull(message = "Template is required")
    private DocumentTemplateEntity template;

    @Column(name = "signer_group", nullable = false)
    @Min(value = 1, message = "Signer group must be at least 1")
    private int signerGroup = 1;

    @Column(nullable = false)
    @NotBlank(message = "Name is required")
    private String name;

    @Column(nullable = false)
    @NotBlank(message = "Email is required")
    private String email;

    @Column(length = 100)
    private String role;

    @Column(name = "display_order", nullable = false)
    @Min(value = 1, message = "Display order must be at least 1")
    private int displayOrder = 1;

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
     * Find all default signers for a specific template.
     *
     * @param template The template entity
     * @return List of default signers, sorted by signerGroup and displayOrder
     */
    public static List<TemplateDefaultSignerEntity> findByTemplate(DocumentTemplateEntity template) {
        return find("template = ?1 ORDER BY signerGroup, displayOrder", template).list();
    }

    /**
     * Delete all default signers for a specific template UUID.
     *
     * @param templateUuid The template UUID
     * @return Number of deleted records
     */
    public static long deleteByTemplateUuid(String templateUuid) {
        return delete("template.uuid = ?1", templateUuid);
    }
}
