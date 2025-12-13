package dk.trustworks.intranet.utils.dto.signing;

import java.util.List;

/**
 * Response containing preview documents as base64-encoded PDFs.
 * <p>
 * Each document is returned separately to avoid PDF merging issues (PDFBOX-3931)
 * that cause font corruption in Safari and other PDF viewers.
 * </p>
 *
 * @param documents List of preview documents with base64-encoded PDF content
 */
public record PreviewTemplateResponse(
    List<PreviewDocumentDTO> documents
) {
    /**
     * Individual preview document with base64-encoded PDF content.
     *
     * @param documentName  Display name of the document
     * @param pdfBase64     Base64-encoded PDF content
     * @param displayOrder  Order for display (0-based index from template)
     */
    public record PreviewDocumentDTO(
        String documentName,
        String pdfBase64,
        int displayOrder
    ) {}
}
