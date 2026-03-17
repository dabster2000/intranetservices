package dk.trustworks.intranet.aggregates.bugreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

/**
 * DTO for auto-fix task API responses.
 * Maps from the autofix_tasks table (not a JPA entity -- accessed via native queries).
 * Uses snake_case JSON serialization to match frontend expectations.
 */
public class AutoFixTaskDTO {

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("bug_report_uuid")
    private String bugReportUuid;

    private String status;

    @JsonProperty("repo_name")
    private String repoName;

    @JsonProperty("branch_name")
    private String branchName;

    @JsonProperty("pr_url")
    private String prUrl;

    @JsonProperty("pr_number")
    private Integer prNumber;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("retry_count")
    private Integer retryCount;

    @JsonProperty("max_retries")
    private Integer maxRetries;

    @JsonProperty("requested_by")
    private String requestedBy;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("started_at")
    private LocalDateTime startedAt;

    @JsonProperty("completed_at")
    private LocalDateTime completedAt;

    // Getters and setters

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getBugReportUuid() { return bugReportUuid; }
    public void setBugReportUuid(String bugReportUuid) { this.bugReportUuid = bugReportUuid; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRepoName() { return repoName; }
    public void setRepoName(String repoName) { this.repoName = repoName; }

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }

    public Integer getPrNumber() { return prNumber; }
    public void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }

    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
