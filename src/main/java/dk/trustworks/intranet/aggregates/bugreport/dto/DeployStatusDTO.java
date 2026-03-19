package dk.trustworks.intranet.aggregates.bugreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for the deploy status polling endpoint.
 * Returns GitHub Actions workflow run status after a merge to staging.
 *
 * <p>Status values: "queued", "in_progress", "completed", "failed", "unknown".
 * Conclusion values (when completed): "success", "failure", "cancelled", etc.
 */
public record DeployStatusDTO(
    String status,
    String conclusion,
    String url,
    @JsonProperty("head_branch") String headBranch
) {}
