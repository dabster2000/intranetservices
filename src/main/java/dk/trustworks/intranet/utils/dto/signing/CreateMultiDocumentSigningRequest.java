package dk.trustworks.intranet.utils.dto.signing;

import java.util.List;

/**
 * Request to create a signing case with multiple documents.
 * All documents will be bundled into a single signing case where
 * each signer signs all documents in sequence.
 * <p>
 * At completion the signed documents are archived into the employee's
 * document store — the same mechanism used by template-based cases, with
 * {@code archiveCategory} standing in for the template's category.
 * </p>
 *
 * @param documents        List of documents to be signed (all required)
 * @param signers          List of signers with group/order, name, email, and role
 * @param referenceId      Optional external reference ID for tracking
 * @param signingSchemas   List of signing schema URNs (e.g., "urn:grn:authn:dk:mitid:substantial").
 *                         If null or empty, backend will use default schemas.
 * @param archiveCategory  Optional sender-chosen archival category
 *                         ({@code EmployeeDocumentCategory} enum name, e.g. "CONTRACT",
 *                         "SALARY"). Template-less cases have no template to map a
 *                         category from — this choice drives the S3 archival category
 *                         at completion. Null/blank falls back to OTHER.
 */
public record CreateMultiDocumentSigningRequest(
    List<UploadedDocument> documents,
    List<SignerInfo> signers,
    String referenceId,
    List<String> signingSchemas,
    String archiveCategory
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
        if (archiveCategory != null && !archiveCategory.isBlank()) {
            try {
                dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory
                        .valueOf(archiveCategory);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "archiveCategory must be one of the employee document categories: "
                                + java.util.Arrays.toString(
                                dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory.values()));
            }
        }
    }

    /** The validated archive category, or null when the sender chose none. */
    public String normalizedArchiveCategory() {
        return archiveCategory == null || archiveCategory.isBlank() ? null : archiveCategory;
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
