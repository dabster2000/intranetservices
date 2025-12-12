package dk.trustworks.intranet.documentservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for template documents.
 * Used for transferring document data within a template between backend and frontend.
 *
 * <p>Word templates are stored in S3 and referenced by fileUuid.
 * The actual document content is retrieved from S3 when needed for processing.
 *
 * <p>Placeholder syntax in Word documents: {{PLACEHOLDER_KEY}}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateDocumentDTO {

    /**
     * Unique identifier for the template document.
     */
    private String uuid;

    /**
     * Display name for the document (e.g., "Main Contract", "Appendix A").
     */
    private String documentName;

    /**
     * UUID reference to the Word template file stored in S3 (files table).
     * The actual .docx binary is stored in the S3 bucket 'trustworksfiles'.
     */
    private String fileUuid;

    /**
     * Original filename of the uploaded Word document.
     * Preserved for user-friendly display and downloads.
     */
    private String originalFilename;

    /**
     * Display order for multi-document templates.
     * Documents are processed in ascending order (1, 2, 3, ...).
     */
    private Integer displayOrder;

    /**
     * Timestamp when the document was created.
     */
    private LocalDateTime createdAt;

    /**
     * Timestamp when the document was last updated.
     */
    private LocalDateTime updatedAt;
}
