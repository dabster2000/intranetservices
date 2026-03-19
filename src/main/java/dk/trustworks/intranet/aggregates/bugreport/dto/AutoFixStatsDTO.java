package dk.trustworks.intranet.aggregates.bugreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for the auto-fix monitoring dashboard KPI cards.
 * Aggregated from autofix_tasks via native queries.
 * Uses snake_case JSON serialization to match frontend expectations.
 */
public record AutoFixStatsDTO(
    @JsonProperty("queue_depth") int queueDepth,
    @JsonProperty("worker_status") String workerStatus,
    @JsonProperty("active_task_id") String activeTaskId,
    @JsonProperty("active_task_elapsed_seconds") Long activeTaskElapsedSeconds,
    @JsonProperty("last_successful_fix_at") LocalDateTime lastSuccessfulFixAt,
    @JsonProperty("last_successful_pr_url") String lastSuccessfulPrUrl,
    @JsonProperty("last_successful_pr_number") Integer lastSuccessfulPrNumber,
    @JsonProperty("success_rate") double successRate,
    @JsonProperty("success_count") int successCount,
    @JsonProperty("total_count") int totalCount,
    @JsonProperty("monthly_cost") BigDecimal monthlyCost
) {}
