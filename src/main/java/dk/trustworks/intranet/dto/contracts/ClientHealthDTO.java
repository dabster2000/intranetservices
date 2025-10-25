package dk.trustworks.intranet.dto.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

import java.io.Serializable;

/**
 * DTO for client health status and contract counts.
 *
 * <p>Represents aggregated health metrics for a client based on:
 * - Active contract count (SIGNED status, end date >= today)
 * - Budget contract count (BUDGET status)
 * - Expired contract count (end date < today)
 * - Overdue invoices (> 90 days past due)
 *
 * <p><b>Health Status Logic:</b>
 * <ul>
 *   <li>HEALTHY: Active > 0, no overdue invoices</li>
 *   <li>AT_RISK: No active BUT budget exists, or 1-2 overdue invoices</li>
 *   <li>CRITICAL: No active/budget, or 3+ overdue invoices</li>
 * </ul>
 *
 * <p><b>Cache:</b> 1 hour TTL, invalidated on contract/invoice changes
 *
 * @since 1.0
 */
@Value
public class ClientHealthDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Client UUID (foreign key to Client entity)
     */
    @NotNull(message = "Client UUID is required")
    @JsonProperty("clientUuid")
    String clientUuid;

    /**
     * Health status enum: HEALTHY, AT_RISK, CRITICAL
     */
    @NotNull(message = "Health status is required")
    @JsonProperty("health")
    HealthStatus health;

    /**
     * Count of active contracts (SIGNED, end date >= today)
     */
    @Min(value = 0, message = "Active count cannot be negative")
    @JsonProperty("activeCount")
    int activeCount;

    /**
     * Count of budget contracts (BUDGET status)
     */
    @Min(value = 0, message = "Budget count cannot be negative")
    @JsonProperty("budgetCount")
    int budgetCount;

    /**
     * Count of expired contracts (end date < today)
     */
    @Min(value = 0, message = "Expired count cannot be negative")
    @JsonProperty("expiredCount")
    int expiredCount;

    /**
     * Count of paused contracts (PAUSED status)
     */
    @Min(value = 0, message = "Paused count cannot be negative")
    @JsonProperty("pausedCount")
    int pausedCount;

    /**
     * Count of completed contracts (COMPLETED status)
     */
    @Min(value = 0, message = "Completed count cannot be negative")
    @JsonProperty("completedCount")
    int completedCount;

    /**
     * Count of overdue invoices (> 90 days past due)
     */
    @Min(value = 0, message = "Overdue invoices count cannot be negative")
    @JsonProperty("overdueInvoicesCount")
    int overdueInvoicesCount;

    /**
     * Total amount of overdue invoices (DKK)
     */
    @Min(value = 0, message = "Overdue amount cannot be negative")
    @JsonProperty("overdueAmount")
    double overdueAmount;

    /**
     * Health status enum
     */
    public enum HealthStatus {
        /**
         * 🟢 Healthy: Active contracts exist, no critical issues
         */
        HEALTHY,

        /**
         * 🟡 At Risk: No active contracts but budget exists, or moderate overdue invoices
         */
        AT_RISK,

        /**
         * 🔴 Critical: No contracts or severe overdue invoices
         */
        CRITICAL
    }

    /**
     * Get emoji representation of health status
     */
    public String getHealthEmoji() {
        return switch (health) {
            case HEALTHY -> "🟢";
            case AT_RISK -> "🟡";
            case CRITICAL -> "🔴";
        };
    }

    /**
     * Get human-readable description of health status
     */
    public String getHealthDescription() {
        return switch (health) {
            case HEALTHY -> "Client is healthy with active contracts";
            case AT_RISK -> "Client requires attention - no active contracts or overdue payments";
            case CRITICAL -> "Immediate action required - no contracts or severe payment issues";
        };
    }

    /**
     * Get total contract count (all statuses)
     */
    public int getTotalContractCount() {
        return activeCount + budgetCount + expiredCount + pausedCount + completedCount;
    }
}
