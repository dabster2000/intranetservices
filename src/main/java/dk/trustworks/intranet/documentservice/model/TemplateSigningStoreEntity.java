package dk.trustworks.intranet.documentservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JPA Entity for template signing stores.
 * Represents a configured SharePoint folder where signed documents are automatically saved.
 * Each template can have multiple signing stores configured to support different storage locations.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "template_signing_stores")
public class TemplateSigningStoreEntity extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_uuid", nullable = false)
    @NotNull(message = "Template is required")
    private DocumentTemplateEntity template;

    @Column(name = "site_url", nullable = false, length = 500)
    @NotBlank(message = "Site URL is required")
    private String siteUrl;

    @Column(name = "drive_name", nullable = false, length = 255)
    @NotBlank(message = "Drive name is required")
    private String driveName;

    @Column(name = "folder_path", length = 500)
    private String folderPath;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 1;

    @Column(name = "user_directory", nullable = false)
    private Boolean userDirectory = false;

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
     * Find all signing stores for a specific template UUID.
     *
     * @param templateUuid The template UUID
     * @return List of signing stores, sorted by displayOrder
     */
    public static List<TemplateSigningStoreEntity> findByTemplate(String templateUuid) {
        return find("template.uuid = ?1 ORDER BY displayOrder", templateUuid).list();
    }

    /**
     * Find all active signing stores for a specific template UUID.
     *
     * @param templateUuid The template UUID
     * @return List of active signing stores, sorted by displayOrder
     */
    public static List<TemplateSigningStoreEntity> findActiveByTemplate(String templateUuid) {
        return find("template.uuid = ?1 AND isActive = true ORDER BY displayOrder", templateUuid).list();
    }

    /**
     * Delete all signing stores for a specific template UUID.
     *
     * @param templateUuid The template UUID
     * @return Number of deleted records
     */
    public static long deleteByTemplateUuid(String templateUuid) {
        return delete("template.uuid = ?1", templateUuid);
    }
}
