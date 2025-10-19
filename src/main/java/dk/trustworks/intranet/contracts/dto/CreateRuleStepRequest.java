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
 * Request DTO for creating a new pricing rule step.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateRuleStepRequest {

    /**
     * Stable identifier for the rule (e.g., "ski21726-admin").
     * Must be unique per contract type.
     */
    @NotBlank(message = "Rule ID is required")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Rule ID must contain only lowercase letters, numbers, and hyphens")
    private String ruleId;

    /**
     * Display label for the rule (e.g., "5% SKI administrationsgebyr").
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
     * Calculation base (SUM_BEFORE_DISCOUNTS or CURRENT_SUM).
     */
    @NotNull(message = "Step base is required")
    private StepBase stepBase;

    /**
     * Percentage value for percentage-based rules (0-100).
     * Required for PERCENT_DISCOUNT_ON_SUM and ADMIN_FEE_PERCENT rules.
     */
    @DecimalMin(value = "0.0", message = "Percent must be non-negative")
    @DecimalMax(value = "100.0", message = "Percent must not exceed 100")
    private BigDecimal percent;

    /**
     * Fixed amount for FIXED_DEDUCTION rules.
     * Should be positive for fees, negative for deductions.
     */
    private BigDecimal amount;

    /**
     * Reference to contract_type_items key (e.g., "trapperabat").
     * Used for PERCENT_DISCOUNT_ON_SUM rules to get dynamic percentages.
     */
    private String paramKey;

    /**
     * Rule activation date (nullable = always active).
     */
    private LocalDate validFrom;

    /**
     * Rule expiration date (nullable = never expires).
     */
    private LocalDate validTo;

    /**
     * Execution order - lower numbers execute first (10, 20, 30...).
     * If not provided, will be auto-incremented from the highest existing priority.
     */
    @Min(value = 1, message = "Priority must be positive")
    private Integer priority;
}
