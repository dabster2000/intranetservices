package dk.trustworks.intranet.contracts.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.contracts.model.enums.AdjustmentFrequency;
import dk.trustworks.intranet.contracts.model.enums.AdjustmentType;
import dk.trustworks.intranet.contracts.model.enums.OverrideType;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO for contract rate adjustment overrides.
 *
 * <p>This DTO represents contract-specific rate adjustments that override or extend
 * rate adjustments defined at the contract type level.
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Override annual rate increases for specific contracts</li>
 *   <li>Add contract-specific rate adjustments (e.g., one-time bonus)</li>
 *   <li>Disable inherited rate adjustments</li>
 *   <li>Modify adjustment percentages or frequencies</li>
 * </ul>
 *
 * <p><b>Example JSON Payloads:</b>
 * <pre>
 * // REPLACE: Complete replacement of rate adjustment
 * {
 *   "ruleId": "annual-increase-2025",
 *   "overrideType": "REPLACE",
 *   "label": "Custom annual increase",
 *   "adjustmentType": "ANNUAL_INCREASE",
 *   "adjustmentPercent": 4.5,
 *   "frequency": "YEARLY",
 *   "effectiveDate": "2025-01-01",
 *   "priority": 50
 * }
 *
 * // MODIFY: Change only percentage
 * {
 *   "ruleId": "annual-increase-2025",
 *   "overrideType": "MODIFY",
 *   "adjustmentPercent": 5.0,
 *   "label": "Increased to 5% for this contract"
 * }
 *
 * // DISABLE: Deactivate rate adjustment
 * {
 *   "ruleId": "inflation-adjustment",
 *   "overrideType": "DISABLE"
 * }
 * </pre>
 *
 * @see dk.trustworks.intranet.contracts.model.ContractRateAdjustmentOverride
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RateOverrideDTO {

    /**
     * Primary key (read-only, set by backend).
     */
    private Integer id;

    /**
     * Contract UUID this override applies to.
     * Required for creation.
     */
    @NotBlank(message = "Contract UUID is required")
    private String contractUuid;

    /**
     * Rule ID from the base contract type rules.
     * Must match an existing rule ID or be unique for new adjustments.
     *
     * <p>Examples: "annual-increase-2025", "inflation-adjustment", "project-bonus"
     */
    @NotBlank(message = "Rule ID is required")
    private String ruleId;

    /**
     * Override strategy.
     * - REPLACE: Create new rate adjustment with these values
     * - MODIFY: Update specific fields of base adjustment
     * - DISABLE: Deactivate the rate adjustment
     */
    @NotNull(message = "Override type is required")
    private OverrideType overrideType;

    /**
     * Human-readable label for the rate adjustment.
     * Required for REPLACE, optional for MODIFY.
     */
    private String label;

    /**
     * Type of rate adjustment.
     * Required for REPLACE, optional for MODIFY.
     *
     * <p>Valid values:
     * - ANNUAL_INCREASE: Yearly rate increase
     * - INFLATION_LINKED: CPI-based adjustment
     * - PROJECT_BONUS: One-time adjustment
     * - MARKET_ADJUSTMENT: Market rate correction
     */
    private AdjustmentType adjustmentType;

    /**
     * Percentage adjustment (e.g., 3.5 for 3.5% increase).
     * Required for REPLACE, optional for MODIFY.
     *
     * <p>Constraints:
     * - Range: -100.0 to +100.0
     * - Negative values represent rate decreases
     */
    @DecimalMin(value = "-100.0", message = "Adjustment percent cannot be less than -100%")
    @DecimalMax(value = "100.0", message = "Adjustment percent cannot exceed 100%")
    private BigDecimal adjustmentPercent;

    /**
     * Frequency of adjustment application.
     * Required for REPLACE with recurring adjustments, optional for MODIFY.
     *
     * <p>Valid values:
     * - ONCE: One-time adjustment
     * - MONTHLY: Applied every month
     * - QUARTERLY: Applied every quarter
     * - YEARLY: Applied annually
     */
    private AdjustmentFrequency frequency;

    /**
     * Date when the adjustment becomes effective.
     * Required for REPLACE, optional for MODIFY.
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effectiveDate;

    /**
     * Date when the adjustment ends (optional).
     * If null, the adjustment continues indefinitely.
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    /**
     * Priority for adjustment ordering (lower = applied first).
     * Default is 100. Range: 1-999.
     */
    private Integer priority;

    /**
     * Whether the override is active.
     * Default is true. Set to false for soft delete.
     */
    @Builder.Default
    private Boolean active = true;

    /**
     * Email of user who created the override (read-only).
     */
    private String createdBy;

    /**
     * Timestamp when override was created (read-only).
     */
    private LocalDateTime createdAt;

    /**
     * Timestamp when override was last updated (read-only).
     */
    private LocalDateTime updatedAt;

    /**
     * Validation: Ensure REPLACE has all required fields.
     */
    public void validateForReplace() {
        if (overrideType == OverrideType.REPLACE) {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Label is required for REPLACE override");
            }
            if (adjustmentType == null) {
                throw new IllegalArgumentException("AdjustmentType is required for REPLACE override");
            }
            if (adjustmentPercent == null) {
                throw new IllegalArgumentException("AdjustmentPercent is required for REPLACE override");
            }
            if (effectiveDate == null) {
                throw new IllegalArgumentException("EffectiveDate is required for REPLACE override");
            }
        }
    }

    /**
     * Validation: Ensure endDate is after effectiveDate.
     */
    public void validateDates() {
        if (effectiveDate != null && endDate != null && endDate.isBefore(effectiveDate)) {
            throw new IllegalArgumentException("End date must be after effective date");
        }
    }

    /**
     * Check if this adjustment is active on a specific date.
     */
    public boolean isActiveOn(LocalDate date) {
        if (effectiveDate == null || date.isBefore(effectiveDate)) {
            return false;
        }
        if (endDate != null && date.isAfter(endDate)) {
            return false;
        }
        return active != null && active;
    }
}
