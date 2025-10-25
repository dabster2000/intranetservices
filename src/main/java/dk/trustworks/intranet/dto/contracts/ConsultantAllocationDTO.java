package dk.trustworks.intranet.dto.contracts;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO for consultant allocation validation and tracking.
 *
 * <p>Used to validate consultant allocation across multiple contracts to prevent over-allocation (> 100%).
 * Returns breakdown of consultant's allocations during a specific time period with contract details.
 *
 * <p><b>Validation Logic:</b>
 * <ol>
 *   <li>Query all contracts where consultant is allocated during [startDate, endDate]</li>
 *   <li>Sum allocation percentages for overlapping periods</li>
 *   <li>Warn if total > 100% (over-allocation)</li>
 *   <li>Return detailed breakdown for user review</li>
 * </ol>
 *
 * <p><b>Cache:</b> 1 hour TTL, invalidated on consultant allocation changes
 *
 * @since 1.0
 */
@Value
public class ConsultantAllocationDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * User UUID (consultant being allocated)
     */
    @NotNull(message = "User UUID is required")
    @JsonProperty("userUuid")
    String userUuid;

    /**
     * Consultant full name (for display)
     */
    @NotNull(message = "Consultant name is required")
    @JsonProperty("consultantName")
    String consultantName;

    /**
     * Period start date for allocation check
     */
    @NotNull(message = "Start date is required")
    @JsonProperty("startDate")
    LocalDate startDate;

    /**
     * Period end date for allocation check
     */
    @NotNull(message = "End date is required")
    @JsonProperty("endDate")
    LocalDate endDate;

    /**
     * Total allocation percentage across all contracts during this period
     */
    @Min(value = 0, message = "Total allocation cannot be negative")
    @Max(value = 500, message = "Total allocation exceeds reasonable limit (500%)")
    @JsonProperty("totalAllocation")
    int totalAllocation;

    /**
     * List of active contract allocations during this period
     */
    @NotNull(message = "Allocations list is required")
    @JsonProperty("allocations")
    List<ContractAllocationDetail> allocations;

    /**
     * Is the consultant over-allocated (> 100%)?
     */
    @JsonProperty("isOverAllocated")
    boolean isOverAllocated;

    /**
     * Available capacity (100% - totalAllocation)
     */
    @JsonProperty("availableCapacity")
    int availableCapacity;

    /**
     * Nested DTO for individual contract allocation details
     */
    @Value
    public static class ContractAllocationDetail implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Contract UUID
         */
        @NotNull(message = "Contract UUID is required")
        @JsonProperty("contractUuid")
        String contractUuid;

        /**
         * Contract name (for display)
         */
        @NotNull(message = "Contract name is required")
        @JsonProperty("contractName")
        String contractName;

        /**
         * Client name (for context)
         */
        @NotNull(message = "Client name is required")
        @JsonProperty("clientName")
        String clientName;

        /**
         * Allocation percentage for this contract
         */
        @Min(value = 0, message = "Allocation cannot be negative")
        @Max(value = 100, message = "Allocation cannot exceed 100%")
        @JsonProperty("allocation")
        int allocation;

        /**
         * Allocation start date for this contract
         */
        @NotNull(message = "Start date is required")
        @JsonProperty("startDate")
        LocalDate startDate;

        /**
         * Allocation end date for this contract (nullable = ongoing)
         */
        @JsonProperty("endDate")
        LocalDate endDate;

        /**
         * Hourly rate for this allocation (DKK)
         */
        @Min(value = 0, message = "Rate cannot be negative")
        @JsonProperty("rate")
        double rate;

        /**
         * Contract status (for context)
         */
        @NotNull(message = "Contract status is required")
        @JsonProperty("contractStatus")
        String contractStatus; // SIGNED, BUDGET, etc.
    }

    /**
     * Get allocation status description for UI
     */
    public String getAllocationStatusDescription() {
        if (totalAllocation == 0) {
            return "Available (0% allocated)";
        } else if (totalAllocation <= 80) {
            return "Available (" + totalAllocation + "% allocated)";
        } else if (totalAllocation <= 100) {
            return "Near Capacity (" + totalAllocation + "% allocated)";
        } else {
            return "Over-Allocated (" + totalAllocation + "% allocated)";
        }
    }

    /**
     * Get color indicator for UI (green, yellow, red)
     */
    public String getAllocationColorIndicator() {
        if (totalAllocation <= 80) {
            return "green"; // Available
        } else if (totalAllocation <= 100) {
            return "yellow"; // Near capacity
        } else {
            return "red"; // Over-allocated
        }
    }
}
