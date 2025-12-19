package dk.trustworks.intranet.utils.dto.signing;

/**
 * Information about an uploaded document for signing.
 * Used in multi-document signing requests where documents are base64 encoded.
 *
 * @param documentName   Document filename (e.g., "contract.pdf")
 * @param documentBase64 Base64 encoded document content
 * @param contentType    MIME type of the document (e.g., "application/pdf")
 * @param signObligated  Whether this document requires signature (true) or is attachment-only (false)
 */
public record UploadedDocument(
    String documentName,
    String documentBase64,
    String contentType,
    boolean signObligated
) {
    /**
     * Creates an UploadedDocument with validation.
     */
    public UploadedDocument {
        if (documentName == null || documentName.isBlank()) {
            throw new IllegalArgumentException("Document name is required");
        }
        if (documentBase64 == null || documentBase64.isBlank()) {
            throw new IllegalArgumentException("Document content (base64) is required");
        }
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/pdf"; // Default to PDF
        }
    }

    /**
     * Creates an UploadedDocument with default content type (application/pdf) that requires signature.
     */
    public static UploadedDocument pdf(String documentName, String documentBase64) {
        return new UploadedDocument(documentName, documentBase64, "application/pdf", true);
    }

    /**
     * Creates an UploadedDocument with default content type (application/pdf) and specified signing requirement.
     *
     * @param documentName   Document filename
     * @param documentBase64 Base64 encoded content
     * @param signObligated  true if signature required, false for attachment-only
     */
    public static UploadedDocument pdf(String documentName, String documentBase64, boolean signObligated) {
        return new UploadedDocument(documentName, documentBase64, "application/pdf", signObligated);
    }
}
