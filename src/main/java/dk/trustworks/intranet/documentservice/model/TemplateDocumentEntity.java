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
 * JPA Entity for template documents.
 * Represents a single Word document within a document template for multi-document signing.
 * Each template can have multiple documents that will all be included in a single signing case.
 *
 * <p>Word templates are stored in S3 (bucket: trustworksfiles) and referenced via fileUuid.
 * The actual document content is retrieved from S3 when needed.
 *
 * <p>Placeholder syntax in Word documents: {{PLACEHOLDER_KEY}}
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "template_documents")
public class TemplateDocumentEntity extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_uuid", nullable = false)
    @NotNull(message = "Template is required")
    private DocumentTemplateEntity template;

    @Column(name = "document_name", nullable = false)
    @NotBlank(message = "Document name is required")
    private String documentName;

    /**
     * UUID reference to the Word template file stored in S3 (files table).
     * The actual .docx binary is stored in the S3 bucket 'trustworksfiles'.
     */
    @Column(name = "file_uuid", length = 36)
    @NotBlank(message = "File UUID is required - upload a Word template")
    private String fileUuid;

    /**
     * Original filename of the uploaded Word document.
     * Preserved for user-friendly display and downloads.
     */
    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 1;

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
     * Find all documents for a specific template UUID.
     *
     * @param templateUuid The template UUID
     * @return List of documents, sorted by displayOrder
     */
    public static List<TemplateDocumentEntity> findByTemplateUuid(String templateUuid) {
        return find("template.uuid = ?1 ORDER BY displayOrder, documentName", templateUuid).list();
    }

    /**
     * Delete all documents for a specific template UUID.
     *
     * @param templateUuid The template UUID
     * @return Number of deleted documents
     */
    public static long deleteByTemplateUuid(String templateUuid) {
        return delete("template.uuid = ?1", templateUuid);
    }

    /**
     * Find a specific document by UUID.
     *
     * @param uuid The document UUID
     * @return The document, or null if not found
     */
    public static TemplateDocumentEntity findByUuid(String uuid) {
        return find("uuid = ?1", uuid).firstResult();
    }
}
