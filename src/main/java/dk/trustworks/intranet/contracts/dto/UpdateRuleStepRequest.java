package dk.trustworks.intranet.contracts.dto;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for updating an existing pricing rule step.
 * Note: Rule ID and contract type cannot be changed after creation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRuleStepRequest {

    /**
     * Display label for the rule.
     */
    @NotBlank(message = "Label is required")
    @Size(max = 255, message = "Label must not exceed 255 characters")
    private String label;

    /**
     * Type of pricing rule.
     */
    @NotNull(message = "Rule step type is required")
    private RuleStepType ruleStepType;

    /**
     * Calculation base.
     */
    @NotNull(message = "Step base is required")
    private StepBase stepBase;

    /**
     * Percentage value (0-100).
     */
    @DecimalMin(value = "0.0", message = "Percent must be non-negative")
    @DecimalMax(value = "100.0", message = "Percent must not exceed 100")
    private BigDecimal percent;

    /**
     * Fixed amount.
     */
    private BigDecimal amount;

    /**
     * Reference to contract_type_items key.
     */
    private String paramKey;

    /**
     * Rule activation date.
     */
    private LocalDate validFrom;

    /**
     * Rule expiration date.
     */
    private LocalDate validTo;

    /**
     * Execution order.
     */
    @NotNull(message = "Priority is required")
    @Min(value = 1, message = "Priority must be positive")
    private Integer priority;

    /**
     * Active status.
     */
    private boolean active = true;
}
