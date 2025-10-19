package dk.trustworks.intranet.contracts.model;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Entity for pricing rule steps.
 * Stores individual pricing rules that apply to contract types.
 *
 * Example usage:
 * <pre>
 * PricingRuleStepEntity rule = new PricingRuleStepEntity();
 * rule.setContractTypeCode("SKI0217_2026");
 * rule.setRuleId("ski21726-admin");
 * rule.setLabel("5% SKI administrationsgebyr");
 * rule.setRuleStepType(RuleStepType.ADMIN_FEE_PERCENT);
 * rule.setStepBase(StepBase.CURRENT_SUM);
 * rule.setPercent(BigDecimal.valueOf(5.0));
 * rule.setPriority(20);
 * rule.persist();
 * </pre>
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "pricing_rule_steps")
public class PricingRuleStepEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Integer id;

    /**
     * Foreign key to contract_type_definitions.code
     */
    @Column(name = "contract_type_code", nullable = false, length = 50)
    @NotBlank(message = "Contract type code is required")
    private String contractTypeCode;

    /**
     * Stable identifier for the rule (e.g., "ski21726-admin").
     * Must be unique per contract type.
     */
    @Column(name = "rule_id", nullable = false, length = 64)
    @NotBlank(message = "Rule ID is required")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Rule ID must contain only lowercase letters, numbers, and hyphens")
    private String ruleId;

    /**
     * Display label for the rule (e.g., "5% SKI administrationsgebyr").
     */
    @Column(nullable = false)
    @NotBlank(message = "Label is required")
    @Size(max = 255, message = "Label must not exceed 255 characters")
    private String label;

    /**
     * Type of pricing rule.
     */
    @Column(name = "rule_step_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Rule step type is required")
    private RuleStepType ruleStepType;

    /**
     * Calculation base (SUM_BEFORE_DISCOUNTS or CURRENT_SUM).
     */
    @Column(name = "step_base", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Step base is required")
    private StepBase stepBase;

    /**
     * Percentage value for percentage-based rules (0-100).
     * Required for PERCENT_DISCOUNT_ON_SUM and ADMIN_FEE_PERCENT rules.
     */
    @Column(precision = 10, scale = 4)
    @DecimalMin(value = "0.0", message = "Percent must be non-negative")
    @DecimalMax(value = "100.0", message = "Percent must not exceed 100")
    private BigDecimal percent;

    /**
     * Fixed amount for FIXED_DEDUCTION rules.
     * Should be positive for fees, negative for deductions.
     */
    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    /**
     * Reference to contract_type_items key (e.g., "trapperabat").
     * Used for PERCENT_DISCOUNT_ON_SUM rules to get dynamic percentages.
     */
    @Column(name = "param_key", length = 64)
    private String paramKey;

    /**
     * Rule activation date (nullable = always active).
     */
    @Column(name = "valid_from")
    private LocalDate validFrom;

    /**
     * Rule expiration date (nullable = never expires).
     */
    @Column(name = "valid_to")
    private LocalDate validTo;

    /**
     * Execution order - lower numbers execute first (10, 20, 30...).
     */
    @Column(nullable = false)
    @NotNull(message = "Priority is required")
    @Min(value = 1, message = "Priority must be positive")
    private Integer priority;

    /**
     * Soft delete flag.
     */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();

        // Validate date range
        if (validFrom != null && validTo != null && !validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException("validFrom must be before validTo");
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();

        // Validate date range
        if (validFrom != null && validTo != null && !validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException("validFrom must be before validTo");
        }
    }

    // --- Panache finder methods ---

    /**
     * Find all rules for a specific contract type (active only).
     *
     * @param contractTypeCode The contract type code
     * @return List of active rules, sorted by priority
     */
    public static List<PricingRuleStepEntity> findByContractType(String contractTypeCode) {
        return find("contractTypeCode = ?1 AND active = true ORDER BY priority", contractTypeCode).list();
    }

    /**
     * Find all rules for a specific contract type (including inactive).
     *
     * @param contractTypeCode The contract type code
     * @return List of all rules, sorted by priority
     */
    public static List<PricingRuleStepEntity> findByContractTypeIncludingInactive(String contractTypeCode) {
        return find("contractTypeCode = ?1 ORDER BY priority, active DESC", contractTypeCode).list();
    }

    /**
     * Find a specific rule by contract type and rule ID.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @return The rule, or null if not found
     */
    public static PricingRuleStepEntity findByContractTypeAndRuleId(String contractTypeCode, String ruleId) {
        return find("contractTypeCode = ?1 AND ruleId = ?2", contractTypeCode, ruleId).firstResult();
    }

    /**
     * Find rules active on a specific date.
     *
     * @param contractTypeCode The contract type code
     * @param date The date to check
     * @return List of active rules for the given date, sorted by priority
     */
    public static List<PricingRuleStepEntity> findByContractTypeAndDate(String contractTypeCode, LocalDate date) {
        return find(
            "contractTypeCode = ?1 AND active = true AND " +
            "(validFrom IS NULL OR validFrom <= ?2) AND " +
            "(validTo IS NULL OR validTo > ?2) " +
            "ORDER BY priority",
            contractTypeCode, date
        ).list();
    }

    /**
     * Check if a rule ID already exists for a contract type.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID to check
     * @return true if the rule ID exists, false otherwise
     */
    public static boolean existsByContractTypeAndRuleId(String contractTypeCode, String ruleId) {
        return count("contractTypeCode = ?1 AND ruleId = ?2", contractTypeCode, ruleId) > 0;
    }

    /**
     * Get the highest priority number for a contract type.
     * Useful for auto-incrementing priorities.
     *
     * @param contractTypeCode The contract type code
     * @return The highest priority, or 0 if no rules exist
     */
    public static int getMaxPriority(String contractTypeCode) {
        Integer max = (Integer) getEntityManager()
            .createQuery("SELECT MAX(r.priority) FROM PricingRuleStepEntity r WHERE r.contractTypeCode = :code")
            .setParameter("code", contractTypeCode)
            .getSingleResult();
        return max != null ? max : 0;
    }

    /**
     * Soft delete by setting active to false.
     */
    public void softDelete() {
        this.active = false;
        this.persist();
    }

    /**
     * Reactivate a soft-deleted rule.
     */
    public void activate() {
        this.active = true;
        this.persist();
    }

    /**
     * Check if this rule is active on a specific date.
     *
     * @param date The date to check
     * @return true if the rule is active on the given date
     */
    public boolean isActiveOn(LocalDate date) {
        if (!active) return false;
        if (validFrom != null && date.isBefore(validFrom)) return false;
        if (validTo != null && !date.isBefore(validTo)) return false;
        return true;
    }
}
