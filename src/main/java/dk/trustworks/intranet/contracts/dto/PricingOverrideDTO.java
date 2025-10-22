package dk.trustworks.intranet.contracts.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
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
 * DTO for pricing rule step overrides.
 *
 * <p>This DTO represents contract-specific pricing rule steps that override
 * pricing calculations defined at the contract type level.
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Override markup percentages for specific contracts</li>
 *   <li>Add custom deductions or additions</li>
 *   <li>Disable specific pricing steps</li>
 *   <li>Adjust pricing formulas for special agreements</li>
 * </ul>
 *
 * <p><b>Pricing Step Types:</b>
 * <ul>
 *   <li><b>PERCENTAGE</b> - Apply percentage markup/markdown (e.g., +20%)</li>
 *   <li><b>FIXED_AMOUNT</b> - Add/subtract fixed amount (e.g., +1000 DKK)</li>
 *   <li><b>PARAMETER</b> - Reference dynamic parameter (e.g., exchange rate)</li>
 * </ul>
 *
 * <p><b>Example JSON Payloads:</b>
 * <pre>
 * // REPLACE: Custom percentage markup
 * {
 *   "ruleId": "markup",
 *   "overrideType": "REPLACE",
 *   "label": "Reduced markup for volume contract",
 *   "ruleStepType": "PERCENTAGE",
 *   "stepBase": "CONSULTANT_RATE",
 *   "percent": 15.0,
 *   "validFrom": "2025-01-01",
 *   "priority": 50
 * }
 *
 * // MODIFY: Change only percentage
 * {
 *   "ruleId": "markup",
 *   "overrideType": "MODIFY",
 *   "percent": 18.0,
 *   "label": "Adjusted markup to 18%"
 * }
 *
 * // DISABLE: Remove pricing step
 * {
 *   "ruleId": "administrative-fee",
 *   "overrideType": "DISABLE"
 * }
 * </pre>
 *
 * @see dk.trustworks.intranet.contracts.model.PricingRuleOverride
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PricingOverrideDTO {

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
     * Rule ID from the base contract type pricing rules.
     * Must match an existing rule ID or be unique for new steps.
     *
     * <p>Examples: "markup", "administrative-fee", "volume-discount"
     */
    @NotBlank(message = "Rule ID is required")
    private String ruleId;

    /**
     * Override strategy.
     * - REPLACE: Create new pricing step with these values
     * - MODIFY: Update specific fields of base pricing step
     * - DISABLE: Deactivate the pricing step
     */
    @NotNull(message = "Override type is required")
    private OverrideType overrideType;

    /**
     * Human-readable label for the pricing step.
     * Required for REPLACE, optional for MODIFY.
     */
    private String label;

    /**
     * Type of pricing rule step.
     * Required for REPLACE, optional for MODIFY.
     *
     * <p>Valid values:
     * - PERCENTAGE: Percentage-based calculation
     * - FIXED_AMOUNT: Fixed amount addition/subtraction
     * - PARAMETER: Dynamic parameter reference
     */
    private RuleStepType ruleStepType;

    /**
     * Base value for calculation.
     * Required for REPLACE, optional for MODIFY.
     *
     * <p>Valid values:
     * - CONSULTANT_RATE: Base on consultant hourly rate
     * - ACCUMULATED: Base on accumulated pricing from previous steps
     * - HOURS: Base on number of hours
     * - CONTRACT_VALUE: Base on total contract value
     */
    private StepBase stepBase;

    /**
     * Percentage value for PERCENTAGE rule steps (e.g., 20.0 for 20%).
     * Required for REPLACE with PERCENTAGE type, optional for MODIFY.
     *
     * <p>Constraints:
     * - Range: -100.0 to +1000.0
     * - Negative values represent discounts
     * - Up to 4 decimal places for precision
     */
    @DecimalMin(value = "-100.0", message = "Percent cannot be less than -100%")
    @DecimalMax(value = "1000.0", message = "Percent cannot exceed 1000%")
    @Digits(integer = 4, fraction = 4, message = "Percent must have at most 4 decimal places")
    private BigDecimal percent;

    /**
     * Fixed amount for FIXED_AMOUNT rule steps.
     * Required for REPLACE with FIXED_AMOUNT type, optional for MODIFY.
     *
     * <p>Constraints:
     * - Range: -999999.99 to +999999.99
     * - Currency: DKK (Danish Kroner)
     */
    @DecimalMin(value = "-999999.99", message = "Amount cannot be less than -999999.99")
    @DecimalMax(value = "999999.99", message = "Amount cannot exceed 999999.99")
    private BigDecimal amount;

    /**
     * Parameter key for PARAMETER rule steps.
     * References dynamic configuration values.
     *
     * <p>Examples: "exchange-rate-usd", "vat-rate", "discount-tier-1"
     */
    @Size(max = 64, message = "Parameter key cannot exceed 64 characters")
    private String paramKey;

    /**
     * Date from which this pricing step is valid.
     * Required for REPLACE, optional for MODIFY.
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate validFrom;

    /**
     * Date until which this pricing step is valid (optional).
     * If null, the step remains valid indefinitely.
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate validTo;

    /**
     * Priority for step execution order (lower = executed first).
     * Default is 100. Range: 1-999.
     * Critical for correct pricing calculation sequence.
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
            if (ruleStepType == null) {
                throw new IllegalArgumentException("RuleStepType is required for REPLACE override");
            }
            if (stepBase == null) {
                throw new IllegalArgumentException("StepBase is required for REPLACE override");
            }
            if (validFrom == null) {
                throw new IllegalArgumentException("ValidFrom date is required for REPLACE override");
            }

            // Type-specific validation
            switch (ruleStepType) {
                case PERCENT_DISCOUNT_ON_SUM:
                case ADMIN_FEE_PERCENT:
                case GENERAL_DISCOUNT_PERCENT:
                    if (percent == null) {
                        throw new IllegalArgumentException("Percent is required for percentage-based rule step");
                    }
                    break;
                case FIXED_DEDUCTION:
                    if (amount == null) {
                        throw new IllegalArgumentException("Amount is required for FIXED_DEDUCTION rule step");
                    }
                    break;
                case ROUNDING:
                    // Rounding doesn't require specific parameters
                    break;
            }
        }
    }

    /**
     * Validation: Ensure validTo is after validFrom.
     */
    public void validateDates() {
        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("Valid-to date must be after valid-from date");
        }
    }

    /**
     * Check if this pricing step is valid on a specific date.
     */
    public boolean isValidOn(LocalDate date) {
        if (validFrom == null || date.isBefore(validFrom)) {
            return false;
        }
        if (validTo != null && date.isAfter(validTo)) {
            return false;
        }
        return active != null && active;
    }
}
