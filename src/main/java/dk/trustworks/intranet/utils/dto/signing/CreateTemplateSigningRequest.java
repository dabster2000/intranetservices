package dk.trustworks.intranet.utils.dto.signing;

import java.util.List;
import java.util.Map;

/**
 * Request to generate PDF from template and send for signing.
 * <p>
 * This DTO allows clients to submit an HTML/Thymeleaf template with placeholder values,
 * which will be rendered into a PDF document and sent for digital signing.
 * </p>
 *
 * @param documentName    Name of the document (used in signing case metadata)
 * @param templateContent HTML/Thymeleaf template content with placeholders (e.g., [[${fieldName}]])
 * @param formValues      Key-value pairs for template placeholders
 * @param signers         List of signers with group/order, name, email, and role
 * @param referenceId     Optional external reference ID for tracking
 * @param signingSchemas  List of signing schema URNs (e.g., "urn:grn:authn:dk:mitid:substantial").
 *                        If null or empty, backend will use default schemas.
 */
public record CreateTemplateSigningRequest(
    String documentName,
    String templateContent,
    Map<String, String> formValues,
    List<SignerInfo> signers,
    String referenceId,
    List<String> signingSchemas
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
        if (templateContent == null || templateContent.isBlank()) {
            throw new IllegalArgumentException("Template content is required");
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
