package dk.trustworks.intranet.utils.dto.nextsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * NextSign API response for case status query.
 * Returned by GET /api/v2/{company}/case/{caseKey}
 *
 * On success: {"data": {nextSignKey, title, recipients, ...}}
 * On error: {"status": "error", "message": "..."}
 *
 * @param status Response status (null on success, "error" on failure)
 * @param message Error message (null on success)
 * @param data Case details including signer statuses (maps to API "data" field)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetCaseStatusResponse(
    String status,
    String message,
    CaseDetails data
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
        List<DocumentInfo> documents
    ) {}

    /**
     * Status of an individual recipient/signer.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipientStatus(
        String name,
        String email,
        int order,
        boolean signing,
        String status,
        @JsonProperty("signed_at") String signedAt,
        @JsonProperty("rejected_at") String rejectedAt,
        String role
    ) {
        /**
         * Checks if this recipient has signed.
         */
        public boolean hasSigned() {
            return "signed".equalsIgnoreCase(status);
        }
    }

    /**
     * Document information.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DocumentInfo(
        String name,
        @JsonProperty("sign_obligated") boolean signObligated
    ) {}

    /**
     * Checks if the response indicates success.
     * Success is indicated by having valid case data.
     * Error responses have status="error".
     *
     * @return true if data is present with a valid nextSignKey, or status is explicitly "OK"
     */
    public boolean isSuccess() {
        // Error responses have status="error"
        if ("error".equalsIgnoreCase(status)) {
            return false;
        }
        // Success responses have data with nextSignKey
        if (data != null && data.nextSignKey() != null && !data.nextSignKey().isBlank()) {
            return true;
        }
        // Fallback for explicit "OK" or "success" status
        return "OK".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status);
    }

    /**
     * Gets the case details (alias for contract() for backward compatibility).
     * @return the case details
     */
    public CaseDetails contract() {
        return data;
    }
}
