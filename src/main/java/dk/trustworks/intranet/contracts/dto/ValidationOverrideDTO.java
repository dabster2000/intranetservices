package dk.trustworks.intranet.contracts.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.contracts.model.enums.OverrideType;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO for contract validation rule overrides.
 *
 * <p>This DTO is the contract between backend and frontend for validation rule overrides.
 * It represents a contract-specific override of validation rules defined at the contract type level.
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Override notes requirements for specific contracts</li>
 *   <li>Adjust minimum/maximum hours thresholds</li>
 *   <li>Disable specific validation rules for exceptions</li>
 * </ul>
 *
 * <p><b>Override Types:</b>
 * <ul>
 *   <li><b>REPLACE</b> - Completely replace the base rule (all fields required)</li>
 *   <li><b>MODIFY</b> - Partially override specific fields (non-null fields override base)</li>
 *   <li><b>DISABLE</b> - Deactivate the rule for this contract (only ruleId needed)</li>
 * </ul>
 *
 * <p><b>Example JSON Payloads:</b>
 * <pre>
 * // MODIFY: Change only threshold value
 * {
 *   "ruleId": "max-hours-per-day",
 *   "overrideType": "MODIFY",
 *   "thresholdValue": 12.0,
 *   "label": "Increased limit for overtime contract"
 * }
 *
 * // REPLACE: Complete replacement
 * {
 *   "ruleId": "notes-required",
 *   "overrideType": "REPLACE",
 *   "label": "Custom notes validation",
 *   "validationType": "NOTES_REQUIRED",
 *   "required": false,
 *   "priority": 50
 * }
 *
 * // DISABLE: Deactivate rule
 * {
 *   "ruleId": "min-hours-per-entry",
 *   "overrideType": "DISABLE"
 * }
 * </pre>
 *
 * @see dk.trustworks.intranet.contracts.model.ContractValidationOverride
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationOverrideDTO {

    /**
     * Primary key (read-only, set by backend).
     */
    private Integer id;

    /**
     * Contract UUID this override applies to.
     * Required for creation, validated against existing contracts.
     */
    @NotBlank(message = "Contract UUID is required")
    private String contractUuid;

    /**
     * Rule ID from the base contract type rules.
     * Must match an existing rule ID in the contract type.
     *
     * <p>Examples: "notes-required", "max-hours-per-day", "min-hours-per-entry"
     */
    @NotBlank(message = "Rule ID is required")
    private String ruleId;

    /**
     * Override strategy.
     * - REPLACE: Use override values entirely
     * - MODIFY: Merge non-null fields with base rule
     * - DISABLE: Deactivate the rule
     */
    @NotNull(message = "Override type is required")
    private OverrideType overrideType;

    /**
     * Human-readable label describing the override.
     * Optional for MODIFY/DISABLE, recommended for REPLACE.
     */
    private String label;

    /**
     * Type of validation rule.
     * Required for REPLACE, optional for MODIFY, ignored for DISABLE.
     *
     * <p>Valid values:
     * - NOTES_REQUIRED
     * - MIN_HOURS_PER_ENTRY
     * - MAX_HOURS_PER_DAY
     * - WEEKEND_WORK_ALLOWED
     * - etc.
     */
    private ValidationType validationType;

    /**
     * Boolean flag for validation rules (e.g., notes required).
     * Required for REPLACE with boolean rules, optional for MODIFY.
     */
    private Boolean required;

    /**
     * Numeric threshold for validation rules (e.g., max hours per day).
     * Required for REPLACE with numeric rules, optional for MODIFY.
     *
     * <p>Constraints:
     * - Must be >= 0.0
     * - Must be <= 24.0 for hour-based validations
     */
    @DecimalMin(value = "0.0", message = "Threshold must be non-negative")
    @DecimalMax(value = "24.0", message = "Threshold cannot exceed 24 hours")
    private BigDecimal thresholdValue;

    /**
     * Additional configuration in JSON format.
     * Used for complex validation rules with multiple parameters.
     *
     * <p>Example: {"allowWeekends": true, "blackoutDates": ["2025-12-24"]}
     */
    private String configJson;

    /**
     * Priority for rule ordering (lower = higher priority).
     * Default is 100. Range: 1-999.
     * Optional for MODIFY/DISABLE, recommended for REPLACE.
     */
    private Integer priority;

    /**
     * Whether the override is active.
     * Default is true. Set to false for soft delete.
     */
    @Builder.Default
    private Boolean active = true;

    /**
     * Email of user who created the override (read-only, set by backend).
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
            if (validationType == null) {
                throw new IllegalArgumentException("ValidationType is required for REPLACE override");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Label is required for REPLACE override");
            }
        }
    }

    /**
     * Validation: Ensure MODIFY has at least one override field.
     */
    public void validateForModify() {
        if (overrideType == OverrideType.MODIFY) {
            boolean hasOverrideField = label != null || validationType != null ||
                                       required != null || thresholdValue != null ||
                                       configJson != null || priority != null;
            if (!hasOverrideField) {
                throw new IllegalArgumentException(
                    "MODIFY override must specify at least one field to override");
            }
        }
    }
}
