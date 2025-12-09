package dk.trustworks.intranet.documentservice.model;

import dk.trustworks.intranet.documentservice.model.enums.TemplateCategory;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA Entity for document templates.
 * Stores document templates with dynamic placeholders.
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "document_templates")
public class DocumentTemplateEntity extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @Column(nullable = false)
    @NotBlank(message = "Name is required")
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Category is required")
    private TemplateCategory category;

    @Column(name = "template_content", nullable = false, columnDefinition = "LONGTEXT")
    @NotBlank(message = "Template content is required")
    private String templateContent;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "modified_by", length = 36)
    private String modifiedBy;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TemplatePlaceholderEntity> placeholders = new ArrayList<>();

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TemplateDefaultSignerEntity> defaultSigners = new ArrayList<>();

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TemplateSigningSchemaEntity> signingSchemas = new ArrayList<>();

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<TemplateSigningStoreEntity> signingStores = new ArrayList<>();

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
     * Find all templates by category (active only) with placeholders, defaultSigners, signingSchemas, and signingStores eagerly loaded.
     *
     * @param category The template category
     * @return List of active templates for the category with placeholders, defaultSigners, signingSchemas, and signingStores
     */
    public static List<DocumentTemplateEntity> findByCategory(TemplateCategory category) {
        // First fetch with placeholders
        List<DocumentTemplateEntity> templates = find(
            "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
            "LEFT JOIN FETCH t.placeholders " +
            "WHERE t.category = ?1 AND t.active = true " +
            "ORDER BY t.name",
            category
        ).list();
        // Then fetch defaultSigners in a separate query to avoid cartesian product
        if (!templates.isEmpty()) {
            find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.defaultSigners " +
                "WHERE t IN ?1",
                templates
            ).list();
            // Then fetch signingSchemas in a separate query to avoid cartesian product
            find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.signingSchemas " +
                "WHERE t IN ?1",
                templates
            ).list();
            // Then fetch signingStores in a separate query to avoid cartesian product
            find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.signingStores " +
                "WHERE t IN ?1",
                templates
            ).list();
        }
        return templates;
    }

    /**
     * Find all templates (including inactive) with placeholders, defaultSigners, signingSchemas, and signingStores eagerly loaded.
     *
     * @param includeInactive Whether to include inactive templates
     * @return List of all templates with placeholders, defaultSigners, signingSchemas, and signingStores, sorted by active status and name
     */
    public static List<DocumentTemplateEntity> findAllIncludingInactive(boolean includeInactive) {
        List<DocumentTemplateEntity> templates;
        if (includeInactive) {
            templates = find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.placeholders " +
                "ORDER BY t.active DESC, t.name"
            ).list();
        } else {
            templates = find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.placeholders " +
                "WHERE t.active = true " +
                "ORDER BY t.name"
            ).list();
        }
        // Then fetch defaultSigners in a separate query to avoid cartesian product
        if (!templates.isEmpty()) {
            find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.defaultSigners " +
                "WHERE t IN ?1",
                templates
            ).list();
            // Then fetch signingSchemas in a separate query to avoid cartesian product
            find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.signingSchemas " +
                "WHERE t IN ?1",
                templates
            ).list();
            // Then fetch signingStores in a separate query to avoid cartesian product
            find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.signingStores " +
                "WHERE t IN ?1",
                templates
            ).list();
        }
        return templates;
    }

    /**
     * Find a template by UUID with placeholders, defaultSigners, signingSchemas, and signingStores eagerly loaded.
     *
     * @param uuid The template UUID
     * @return The template with placeholders, defaultSigners, signingSchemas, and signingStores, or null if not found
     */
    public static DocumentTemplateEntity findByUuidWithPlaceholders(String uuid) {
        // First fetch with placeholders
        DocumentTemplateEntity template = find(
            "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
            "LEFT JOIN FETCH t.placeholders " +
            "WHERE t.uuid = ?1 " +
            "ORDER BY t.uuid",
            uuid
        ).firstResult();
        // Then fetch defaultSigners in a separate query to avoid cartesian product
        if (template != null) {
            find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.defaultSigners " +
                "WHERE t.uuid = ?1",
                uuid
            ).firstResult();
            // Then fetch signingSchemas in a separate query to avoid cartesian product
            find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.signingSchemas " +
                "WHERE t.uuid = ?1",
                uuid
            ).firstResult();
            // Then fetch signingStores in a separate query to avoid cartesian product
            find(
                "SELECT DISTINCT t FROM DocumentTemplateEntity t " +
                "LEFT JOIN FETCH t.signingStores " +
                "WHERE t.uuid = ?1",
                uuid
            ).firstResult();
        }
        return template;
    }

    // --- Business methods ---

    /**
     * Soft delete by setting active to false.
     */
    public void softDelete() {
        this.active = false;
        this.persist();
    }

    /**
     * Reactivate a soft-deleted template.
     */
    public void activate() {
        this.active = true;
        this.persist();
    }

    /**
     * Add a placeholder to this template.
     *
     * @param placeholder The placeholder to add
     */
    public void addPlaceholder(TemplatePlaceholderEntity placeholder) {
        placeholders.add(placeholder);
        placeholder.setTemplate(this);
    }

    /**
     * Clear all placeholders from this template.
     * Used when syncing placeholders during updates.
     */
    public void clearPlaceholders() {
        placeholders.clear();
    }

    /**
     * Add a default signer to this template.
     *
     * @param signer The default signer to add
     */
    public void addDefaultSigner(TemplateDefaultSignerEntity signer) {
        defaultSigners.add(signer);
        signer.setTemplate(this);
    }

    /**
     * Clear all default signers from this template.
     * Used when syncing default signers during updates.
     */
    public void clearDefaultSigners() {
        defaultSigners.clear();
    }

    /**
     * Add a signing schema to this template.
     *
     * @param signingSchema The signing schema to add
     */
    public void addSigningSchema(TemplateSigningSchemaEntity signingSchema) {
        signingSchemas.add(signingSchema);
        signingSchema.setTemplate(this);
    }

    /**
     * Clear all signing schemas from this template.
     * Used when syncing signing schemas during updates.
     */
    public void clearSigningSchemas() {
        signingSchemas.clear();
    }

    /**
     * Add a signing store to this template.
     *
     * @param signingStore The signing store to add
     */
    public void addSigningStore(TemplateSigningStoreEntity signingStore) {
        signingStores.add(signingStore);
        signingStore.setTemplate(this);
    }

    /**
     * Clear all signing stores from this template.
     * Used when syncing signing stores during updates.
     */
    public void clearSigningStores() {
        signingStores.clear();
    }
}
