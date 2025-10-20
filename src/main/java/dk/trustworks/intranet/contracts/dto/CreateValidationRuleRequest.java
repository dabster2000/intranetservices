package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for creating a new validation rule.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateValidationRuleRequest {

    /**
     * Stable identifier for the rule (e.g., "ski-notes-required").
     * Must be unique per contract type, lowercase with hyphens.
     */
    @NotBlank(message = "Rule ID is required")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Rule ID must contain only lowercase letters, numbers, and hyphens")
    private String ruleId;

    /**
     * Display label for the rule.
     */
    @NotBlank(message = "Label is required")
    @Size(max = 255, message = "Label must not exceed 255 characters")
    private String label;

    /**
     * Type of validation rule.
     */
    @NotNull(message = "Validation type is required")
    private ValidationType validationType;

    /**
     * Boolean flag for yes/no validations.
     * Used by: NOTES_REQUIRED, REQUIRE_TASK_SELECTION
     */
    private boolean required = false;

    /**
     * Numeric threshold for limit validations.
     * Used by: MIN_HOURS_PER_ENTRY, MAX_HOURS_PER_DAY
     */
    @DecimalMin(value = "0.0", message = "Threshold value must be non-negative")
    private BigDecimal thresholdValue;

    /**
     * Additional configuration in JSON format (optional).
     */
    private String configJson;

    /**
     * Evaluation order - lower numbers execute first (10, 20, 30...).
     */
    @NotNull(message = "Priority is required")
    @Min(value = 1, message = "Priority must be positive")
    private Integer priority;

    /**
     * Whether the rule is active (defaults to true).
     */
    private boolean active = true;
}
