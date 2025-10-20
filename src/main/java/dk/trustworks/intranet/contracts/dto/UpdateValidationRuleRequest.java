package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request DTO for updating an existing validation rule.
 * Note: Rule ID and contract type code cannot be changed after creation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateValidationRuleRequest {

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
     */
    private boolean required;

    /**
     * Numeric threshold for limit validations.
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
     * Whether the rule is active.
     */
    private boolean active;
}
