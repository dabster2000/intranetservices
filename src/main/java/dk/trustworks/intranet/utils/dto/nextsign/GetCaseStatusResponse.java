package dk.trustworks.intranet.utils.dto.nextsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * NextSign API response for case status query.
 * Returned by GET /api/v2/{company}/case/{caseId}/get
 *
 * On success: {"status": "case_found", "case": {_id, nextSignKey, title, recipients, ...}}
 * On error: {"status": "error", "message": "..."}
 *
 * Note: The API returns the case data in a field named "case", not "data".
 *
 * @param status Response status ("case_found" on success, "error" on failure)
 * @param message Error message (null on success)
 * @param caseDetails Case details including signer statuses (maps to API "case" field)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetCaseStatusResponse(
    String status,
    String message,
    @JsonProperty("case") CaseDetails caseDetails
) {

    /**
     * Case details including document and signer information.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseDetails(
        String nextSignKey,
        String title,
        String referenceId,
        @JsonProperty("case_status") String caseStatus,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        List<RecipientStatus> recipients,
        List<DocumentInfo> documents,
        @JsonProperty("signedDocuments") List<SignedDocumentInfo> signedDocuments
    ) {}

    /**
     * Status of an individual recipient/signer.
     *
     * Note: The NextSign API returns the signing status in a field named "signed"
     * with values "pending" or "signed". We map this to our "status" field.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipientStatus(
        String name,
        String email,
        int order,
        boolean signing,
        @JsonProperty("signed") String status,
        @JsonProperty("signedDateAndTime") String signedAt,
        @JsonProperty("rejected_at") String rejectedAt,
        String role,
        boolean needsCpr,
        SignerIdentityWrapper signer
    ) {
        /**
         * Checks if this recipient has signed.
         */
        public boolean hasSigned() {
            return "signed".equalsIgnoreCase(status);
        }

        /**
         * Gets CPR match status from signer identity.
         * @return true if CPR matched, false if mismatch, null if not applicable/not yet verified
         */
        public Boolean getCprIsMatch() {
            if (signer == null || signer.identity() == null) {
                return null;
            }
            return signer.identity().cprIsMatch();
        }
    }

    /**
     * Wrapper for signer identity information from NextSign response.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SignerIdentityWrapper(
        @JsonProperty("signer_type") String signerType,
        SignerIdentity identity
    ) {}

    /**
     * Identity verification details including CPR match status.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SignerIdentity(
        Boolean cprIsMatch,
        Boolean confirmed,
        String lastConfirmation,
        String session
    ) {}

    /**
     * Document information.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentInfo(
        String name,
        @JsonProperty("sign_obligated") boolean signObligated,
        @JsonProperty("document_id") String documentId
    ) {}

    /**
     * Signed document information returned after signing is complete.
     * Contains the download URL for the signed PDF in the "file" field.
     * The documentId field holds this URL despite its name (for code compatibility).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SignedDocumentInfo(
        String name,
        @JsonProperty("file") String documentId
    ) {}

    /**
     * Checks if the response indicates success.
     * Success is indicated by having valid case data.
     * Error responses have status="error".
     *
     * @return true if caseDetails is present with a valid nextSignKey, or status is "case_found"
     */
    public boolean isSuccess() {
        // Error responses have status="error"
        if ("error".equalsIgnoreCase(status)) {
            return false;
        }
        // Success responses have caseDetails with nextSignKey
        if (caseDetails != null && caseDetails.nextSignKey() != null && !caseDetails.nextSignKey().isBlank()) {
            return true;
        }
        // Fallback for explicit success statuses
        return "case_found".equalsIgnoreCase(status)
            || "OK".equalsIgnoreCase(status)
            || "success".equalsIgnoreCase(status);
    }

    /**
     * Gets the case details.
     * Alias for caseDetails() for backward compatibility with code using contract().
     * @return the case details
     */
    public CaseDetails contract() {
        return caseDetails;
    }
}
