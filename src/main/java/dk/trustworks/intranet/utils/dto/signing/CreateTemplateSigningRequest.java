package dk.trustworks.intranet.utils.dto.signing;

import dk.trustworks.intranet.documentservice.dto.TemplateDocumentDTO;

import java.util.List;
import java.util.Map;

/**
 * Request to generate PDF from template and send for signing.
 * <p>
 * This DTO allows clients to submit template documents with placeholder values,
 * which will be rendered into PDF documents and sent for digital signing.
 * Multi-document support is the only supported pattern.
 * </p>
 *
 * @param documentName    Name of the document (used in signing case metadata)
 * @param documents       List of template documents with their content (REQUIRED)
 * @param formValues      Key-value pairs for template placeholders
 * @param signers         List of signers with group/order, name, email, and role
 * @param referenceId     Optional external reference ID for tracking
 * @param signingSchemas  List of signing schema URNs (e.g., "urn:grn:authn:dk:mitid:substantial").
 *                        If null or empty, backend will use default schemas.
 * @param signingStoreUuid UUID of template_signing_stores for SharePoint auto-upload after signing completes.
 *                         Optional - if null, no auto-upload will occur.
 */
public record CreateTemplateSigningRequest(
    String documentName,
    List<TemplateDocumentDTO> documents,
    Map<String, String> formValues,
    List<SignerInfo> signers,
    String referenceId,
    List<String> signingSchemas,
    String signingStoreUuid
) {
    /**
     * Validates that required fields are present and valid.
     *
     * @throws IllegalArgumentException if validation fails
     */
    public void validate() {
        if (documentName == null || documentName.isBlank()) {
            throw new IllegalArgumentException("Document name is required");
        }
        // Documents must be provided (multi-document pattern is the only supported pattern)
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("At least one document is required");
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
}
