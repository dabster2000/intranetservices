package dk.trustworks.intranet.aggregates.bugreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO for auto-fix task API responses.
 * Maps from the autofix_tasks table (not a JPA entity -- accessed via native queries).
 * Uses snake_case JSON serialization to match frontend expectations.
 *
 * <p>Includes computed getters that extract values from the {@code result} and
 * {@code usage_info} JSON columns. All computed getters handle null and
 * malformed JSON gracefully, returning null or empty defaults.
 */
@JBossLog
public class AutoFixTaskDTO {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    // --- New fields from V249 columns ---

    /** Raw JSON string from the result column (Claude Code structured output). */
    private String result;

    /** Raw JSON string from the usage_info column (Anthropic API usage metrics). */
    @JsonProperty("usage_info")
    private String usageInfo;

    @JsonProperty("heartbeat_at")
    private LocalDateTime heartbeatAt;

    // --- Getters and setters ---

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

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getUsageInfo() { return usageInfo; }
    public void setUsageInfo(String usageInfo) { this.usageInfo = usageInfo; }

    public LocalDateTime getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(LocalDateTime heartbeatAt) { this.heartbeatAt = heartbeatAt; }

    // --- Computed getters (derived from raw fields, null-safe) ---

    /**
     * Duration in seconds between started_at and completed_at (or now if still processing).
     */
    @JsonProperty("duration_seconds")
    public Long getDurationSeconds() {
        if (startedAt == null) {
            return null;
        }
        LocalDateTime end = completedAt != null ? completedAt : LocalDateTime.now();
        return Duration.between(startedAt, end).getSeconds();
    }

    /**
     * Total cost in USD from usage_info JSON.
     * Worker stores: {@code {"cost_usd": 0.42}} (primary) or
     * legacy {@code {"cost": 0.42}} / {@code {"total_cost": 0.42}}.
     */
    @JsonProperty("cost_usd")
    public BigDecimal getCostUsd() {
        return readBigDecimalFromJson(usageInfo, "cost_usd", "cost", "total_cost");
    }

    /**
     * Diff scanner verdict from result JSON.
     * Worker stores: {@code {"diff_scanner": {"decision": "approve_candidate"}}}.
     */
    @JsonProperty("verdict")
    public String getVerdict() {
        return readNestedStringFromJson(result, "diff_scanner", "decision");
    }

    /**
     * Human-readable summary of what the auto-fix did.
     * Extracted from result JSON {@code diagnosis} field (worker stores the
     * parsed Claude output summary there).
     */
    @JsonProperty("result_summary")
    public String getResultSummary() {
        return readStringFromJson(result, "diagnosis");
    }

    /**
     * Root cause diagnosis from result JSON.
     */
    @JsonProperty("diagnosis")
    public String getDiagnosis() {
        return readStringFromJson(result, "root_cause");
    }

    /**
     * Number of files changed from the diff scanner result.
     * Worker stores: {@code {"diff_scanner": {"files_changed": 3}}}.
     */
    @JsonProperty("files_changed")
    public Integer getFilesChanged() {
        return readNestedIntFromJson(result, "diff_scanner", "files_changed");
    }

    /**
     * Security or quality flags from result JSON. Expects:
     * {@code {"diff_scanner": {"flagged_patterns": ["auth_change"]}}}.
     * Falls back to top-level flagged_patterns for legacy data.
     */
    @JsonProperty("flagged_patterns")
    public List<String> getFlaggedPatterns() {
        List<String> nested = readNestedStringListFromJson(result, "diff_scanner", "flagged_patterns");
        if (!nested.isEmpty()) {
            return nested;
        }
        return readStringListFromJson(result, "flagged_patterns");
    }

    /**
     * Root cause analysis from result JSON. Expects: {@code {"root_cause": "..."}}.
     */
    @JsonProperty("root_cause")
    public String getRootCause() {
        return readStringFromJson(result, "root_cause");
    }

    /**
     * Confidence level from result JSON. Expects: {@code {"confidence": "high"}}.
     */
    @JsonProperty("confidence")
    public String getConfidence() {
        return readStringFromJson(result, "confidence");
    }

    /**
     * Manual follow-up items from result JSON. Expects:
     * {@code {"manual_followups": ["Review auth logic", "Add integration test"]}}.
     */
    @JsonProperty("manual_followups")
    public List<String> getManualFollowups() {
        return readStringListFromJson(result, "manual_followups");
    }

    /**
     * Files examined by Claude during analysis.
     * Worker stores: {@code {"files_examined": ["src/App.tsx", "src/utils.ts"]}}.
     */
    @JsonProperty("files_examined")
    public List<String> getFilesExamined() {
        return readStringListFromJson(result, "files_examined");
    }

    // --- JSON parsing helpers (all null/malformed safe) ---

    private String readStringFromJson(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode field = node.get(fieldName);
            return field != null && !field.isNull() ? field.asText() : null;
        } catch (Exception e) {
            log.debugf("Failed to parse JSON field '%s': %s", fieldName, e.getMessage());
            return null;
        }
    }

    private BigDecimal readBigDecimalFromJson(String json, String... fieldNames) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            for (String fieldName : fieldNames) {
                JsonNode field = node.get(fieldName);
                if (field != null && !field.isNull() && field.isNumber()) {
                    return field.decimalValue();
                }
            }
            return null;
        } catch (Exception e) {
            log.debugf("Failed to parse JSON for cost fields: %s", e.getMessage());
            return null;
        }
    }

    private List<String> readStringListFromJson(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode field = node.get(fieldName);
            if (field != null && field.isArray()) {
                return MAPPER.convertValue(field, new TypeReference<List<String>>() {});
            }
            return List.of();
        } catch (Exception e) {
            log.debugf("Failed to parse JSON list field '%s': %s", fieldName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Read a string from a nested JSON object: {@code json.parent.child}.
     */
    private String readNestedStringFromJson(String json, String parentField, String childField) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode parent = node.get(parentField);
            if (parent != null && parent.isObject()) {
                JsonNode child = parent.get(childField);
                return child != null && !child.isNull() ? child.asText() : null;
            }
            return null;
        } catch (Exception e) {
            log.debugf("Failed to parse nested JSON field '%s.%s': %s", parentField, childField, e.getMessage());
            return null;
        }
    }

    /**
     * Read an integer from a nested JSON object: {@code json.parent.child}.
     */
    private Integer readNestedIntFromJson(String json, String parentField, String childField) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode parent = node.get(parentField);
            if (parent != null && parent.isObject()) {
                JsonNode child = parent.get(childField);
                return child != null && !child.isNull() && child.isNumber() ? child.intValue() : null;
            }
            return null;
        } catch (Exception e) {
            log.debugf("Failed to parse nested JSON int '%s.%s': %s", parentField, childField, e.getMessage());
            return null;
        }
    }

    /**
     * Read a string list from a nested JSON object: {@code json.parent.child}.
     */
    private List<String> readNestedStringListFromJson(String json, String parentField, String childField) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode parent = node.get(parentField);
            if (parent != null && parent.isObject()) {
                JsonNode child = parent.get(childField);
                if (child != null && child.isArray()) {
                    return MAPPER.convertValue(child, new TypeReference<List<String>>() {});
                }
            }
            return List.of();
        } catch (Exception e) {
            log.debugf("Failed to parse nested JSON list '%s.%s': %s", parentField, childField, e.getMessage());
            return List.of();
        }
    }
}
