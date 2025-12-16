package dk.trustworks.intranet.utils.dto.signing;

import dk.trustworks.intranet.documentservice.dto.TemplateDocumentDTO;

import java.util.List;
import java.util.Map;

/**
 * Request to generate a preview PDF from template documents.
 * <p>
 * This DTO allows clients to preview documents with placeholders replaced
 * BEFORE sending them for signing. The preview is generated using the same
 * PDF generation logic as actual signing, but without creating a signing case.
 * </p>
 * <p>
 * The frontend sends documents directly (already loaded from the template),
 * eliminating the need for an additional lookup by templateUuid.
 * </p>
 *
 * @param documents    List of template documents to preview (each must have fileUuid referencing S3 Word template)
 * @param formValues   Key-value pairs for template placeholder substitution
 * @param templateUuid UUID of the parent document template for placeholder type lookup.
 *                     Optional - if provided, enables type-aware formatting (e.g., Danish currency format for CURRENCY fields).
 */
public record PreviewTemplateRequest(
    List<TemplateDocumentDTO> documents,
    Map<String, String> formValues,
    String templateUuid
) {
    /**
     * Validates that required fields are present and valid.
     *
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document is required");
        }
        // Validate each document has a fileUuid (Word template file reference)
        for (TemplateDocumentDTO doc : documents) {
            if (doc.getFileUuid() == null || doc.getFileUuid().isBlank()) {
                String docName = doc.getDocumentName() != null ? doc.getDocumentName() : "Unknown";
                throw new IllegalArgumentException(
                    "Document '" + docName + "' has no Word template file (fileUuid is required)"
                );
            }
        }
    }
}
