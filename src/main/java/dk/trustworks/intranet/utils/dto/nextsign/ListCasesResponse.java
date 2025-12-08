package dk.trustworks.intranet.utils.dto.nextsign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * NextSign API response for listing signing cases.
 * Returned by GET /api/v2/{company}/cases/get
 *
 * Supports pagination and filtering by status, folder, etc.
 *
 * Response format:
 * {
 *   "data": {
 *     "cases": [...],
 *     "total": 45,
 *     "index": 0,
 *     "limit": 50
 *   }
 * }
 *
 * @param status Response status ("OK" on success, "error" on failure)
 * @param message Error message (null on success)
 * @param data List of cases with pagination info
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ListCasesResponse(
    String status,
    String message,
    ListData data
) {

    /**
     * Wrapper for cases list with pagination metadata.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListData(
        List<CaseSummary> cases,
        int total,
        int index,
        int limit
    ) {}

    /**
     * Summary information for a signing case.
     * Contains minimal metadata; full details available via getCaseStatus.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseSummary(
        @JsonProperty("_id") String id,
        String nextSignKey,
        String title,
        String referenceId,
        @JsonProperty("case_status") String caseStatus,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        String folder
    ) {}

    /**
     * Checks if the response indicates success.
     * Success is indicated by having valid data or a non-error status.
     * Error responses have status="error".
     *
     * @return true if data is present or status indicates success
     */
    public boolean isSuccess() {
        // Error responses have status="error"
        if ("error".equalsIgnoreCase(status)) {
            return false;
        }
        // Success responses have data (status may be "company_found", "OK", etc.)
        if (data != null) {
            return true;
        }
        // Fallback for explicit success statuses without data
        return "OK".equalsIgnoreCase(status)
            || "success".equalsIgnoreCase(status)
            || "company_found".equalsIgnoreCase(status);
    }

    /**
     * Gets the list of case summaries.
     * Convenience method to access data.cases directly.
     *
     * @return list of case summaries, or empty list if data is null
     */
    public List<CaseSummary> getCases() {
        return data != null && data.cases() != null ? data.cases() : List.of();
    }
}
