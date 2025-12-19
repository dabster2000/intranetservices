package dk.trustworks.intranet.utils.dto.signing;

/**
 * Information about a document to be signed.
 * Used internally when preparing multiple documents for a signing case.
 *
 * @param name          Document filename (e.g., "contract.pdf")
 * @param pdfBytes      Binary PDF content
 * @param signObligated Whether this document requires signature (true) or is attachment-only (false)
 */
public record DocumentInfo(
    String name,
    byte[] pdfBytes,
    boolean signObligated
) {
    /**
     * Creates a DocumentInfo with validation. Defaults to requiring signature.
     *
     * @param name     Document filename
     * @param pdfBytes Binary PDF content
     */
    public DocumentInfo(String name, byte[] pdfBytes) {
        this(name, pdfBytes, true);
    }

    /**
     * Creates a DocumentInfo with validation.
     *
     * @throws IllegalArgumentException if name is null/empty or pdfBytes is null/empty
     */
    public DocumentInfo {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Document name is required");
        }
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("Document content is required");
        }
    }
}
