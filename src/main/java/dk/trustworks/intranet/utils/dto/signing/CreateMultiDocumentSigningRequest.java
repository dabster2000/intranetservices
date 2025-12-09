package dk.trustworks.intranet.utils.dto.signing;

import java.util.List;

/**
 * Request to create a signing case with multiple documents.
 * All documents will be bundled into a single signing case where
 * each signer signs all documents in sequence.
 *
 * @param documents        List of documents to be signed (all required)
 * @param signers          List of signers with group/order, name, email, and role
 * @param referenceId      Optional external reference ID for tracking
 * @param signingStoreUuid UUID of template_signing_stores for SharePoint auto-upload (optional)
 * @param signingSchemas   List of signing schema URNs (e.g., "urn:grn:authn:dk:mitid:substantial").
 *                         If null or empty, backend will use default schemas.
 */
public record CreateMultiDocumentSigningRequest(
    List<UploadedDocument> documents,
    List<SignerInfo> signers,
    String referenceId,
    String signingStoreUuid,
    List<String> signingSchemas
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
        for (int i = 0; i < documents.size(); i++) {
            UploadedDocument doc = documents.get(i);
            if (doc.documentName() == null || doc.documentName().isBlank()) {
                throw new IllegalArgumentException("Document name is required for document " + (i + 1));
            }
            if (doc.documentBase64() == null || doc.documentBase64().isBlank()) {
                throw new IllegalArgumentException("Document content is required for document " + (i + 1));
            }
        }
        if (signers == null || signers.isEmpty()) {
            throw new IllegalArgumentException("At least one signer is required");
        }
        for (SignerInfo signer : signers) {
            if (signer.name() == null || signer.name().isBlank()) {
                throw new IllegalArgumentException("Signer name is required");
            }
            if (signer.email() == null || signer.email().isBlank()) {
                throw new IllegalArgumentException("Signer email is required");
            }
            if (signer.group() < 1) {
                throw new IllegalArgumentException("Signer group must be 1 or greater");
            }
        }
    }

    /**
     * Returns a display name derived from the first document name.
     * Used for creating the signing case title.
     */
    public String getDisplayName() {
        if (documents == null || documents.isEmpty()) {
            return "Multi-Document Signing";
        }
        String firstName = documents.get(0).documentName();
        if (documents.size() == 1) {
            return firstName;
        }
        return firstName + " (+" + (documents.size() - 1) + " more)";
    }
}
