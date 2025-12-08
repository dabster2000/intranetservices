package dk.trustworks.intranet.utils.dto.nextsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Nextsign API create case response.
 *
 * On success: {"data": {_id, nextSignKey, title, referenceId, ...}}
 * On error: {"status": "error", "message": "..."}
 *
 * Note: Success responses have null status/message - only the "data" field is present.
 *
 * @param status Response status (null on success, "error" on failure)
 * @param message Error message (null on success)
 * @param data Created case data on success
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateCaseResponse(
    String status,
    String message,
    CaseData data
) {

    /**
     * Created case data from Nextsign.
     *
     * @param id MongoDB ObjectId - use this for API calls like getCaseStatus
     * @param nextSignKey Human-readable case key - for display and signing links
     * @param title Case title
     * @param referenceId Internal reference provided in request
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseData(
        @JsonProperty("_id") String id,
        String nextSignKey,
        String title,
        String referenceId
    ) {}

    /**
     * Checks if the response indicates success.
     * Success is indicated by having valid case data (with nextSignKey).
     * Error responses have status="error".
     *
     * Note: The _id field should be checked separately when needed for API calls.
     *
     * @return true if data is present with a valid nextSignKey, or status is explicitly "OK"
     */
    public boolean isSuccess() {
        // Error responses have status="error"
        if ("error".equalsIgnoreCase(status)) {
            return false;
        }
        // Success responses have data with nextSignKey - status is null on success
        // Note: _id may also be present for API calls
        if (data != null && data.nextSignKey() != null && !data.nextSignKey().isBlank()) {
            return true;
        }
        // Fallback for explicit "OK" or "success" status
        return "OK".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status);
    }

    /**
     * Gets the case data (alias for backward compatibility with code using contract()).
     * @return the case data
     */
    public CaseData contract() {
        return data;
    }
}
