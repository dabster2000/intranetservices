package dk.trustworks.intranet.aggregates.bugreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for the PR merge endpoint.
 * Returns merge status and the updated bug report status.
 */
public record MergeResultDTO(
    @JsonProperty("task_id") String taskId,
    @JsonProperty("pr_number") Integer prNumber,
    @JsonProperty("merge_status") String mergeStatus,
    @JsonProperty("bug_report_status") String bugReportStatus,
    String message
) {}
