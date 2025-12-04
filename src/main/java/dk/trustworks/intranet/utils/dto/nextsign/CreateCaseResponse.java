package dk.trustworks.intranet.utils.dto.nextsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Nextsign API create case response.
 *
 * On success: {"data": {nextSignKey, title, referenceId, ...}}
 * On error: {"status": "error", "message": "..."}
 *
 * @param status Response status (null on success, "error" on failure)
 * @param message Error message (null on success)
 * @param data Created case data on success (maps to API "data" field)
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
     * @param nextSignKey Unique case identifier for tracking and status queries
     * @param title Case title
     * @param referenceId Internal reference provided in request
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseData(
        String nextSignKey,
        String title,
        String referenceId
    ) {}

    /**
     * Checks if the response indicates success.
     * Success is indicated by having valid case data with a nextSignKey.
     * Error responses have status="error".
     *
     * @return true if data is present with a valid nextSignKey, or status is explicitly "OK"
     */
    public boolean isSuccess() {
        // Error responses have status="error"
        if ("error".equalsIgnoreCase(status)) {
            return false;
        }
        // Success responses have data with nextSignKey (status is null)
        if (data != null && data.nextSignKey() != null && !data.nextSignKey().isBlank()) {
            return true;
        }
        // Fallback for explicit "OK" or "success" status
        return "OK".equalsIgnoreCase(status) || "success".equalsIgnoreCase(status);
    }

    /**
     * Gets the case data (alias for contract() for backward compatibility).
     * @return the case data
     */
    public CaseData contract() {
        return data;
    }
}
