package dk.trustworks.intranet.utils.dto.signing;

import java.util.List;

/**
 * Request to create a new signing case.
 *
 * @param documentName Name/filename of the document (e.g., "contract.pdf")
 * @param documentBase64 Base64 encoded PDF content
 * @param contentType MIME type of the document (typically "application/pdf")
 * @param signers List of signers with their order and role
 * @param referenceId Optional internal tracking ID (can be null)
 * @param signingStoreUuid Optional UUID of template_signing_stores for SharePoint auto-upload (can be null)
 */
public record CreateSigningCaseRequest(
    String documentName,
    String documentBase64,
    String contentType,
    List<SignerInfo> signers,
    String referenceId,
    String signingStoreUuid
) {
    /**
     * Validates that required fields are present and signers list is not empty.
     *
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (documentName == null || documentName.isBlank()) {
            throw new IllegalArgumentException("Document name is required");
        }
        if (documentBase64 == null || documentBase64.isBlank()) {
            throw new IllegalArgumentException("Document content (base64) is required");
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
                throw new IllegalArgumentException("Signer group must be 1 or higher");
            }
        }
    }
}
