package dk.trustworks.intranet.contracts.model;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.model.enums.OverrideType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * JPA Entity for contract-level pricing rule overrides.
 * Allows specific contracts to override pricing rule steps defined at the contract type level.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Customer-specific pricing (e.g., different admin fee for specific client)</li>
 *   <li>Temporarily disable discount rules for a specific contract</li>
 *   <li>Apply contract-specific pricing adjustments with custom dates</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * // Override admin fee percentage for a specific contract
 * PricingRuleOverride override = new PricingRuleOverride();
 * override.setContractUuid("contract-uuid-123");
 * override.setRuleId("ski21726-admin");
 * override.setLabel("Custom 3% admin fee for this contract");
 * override.setOverrideType(OverrideType.MODIFY);
 * override.setPercent(BigDecimal.valueOf(3.0)); // Override only percentage
 * override.setValidFrom(LocalDate.of(2025, 1, 1));
 * override.setCreatedBy("admin@trustworks.dk");
 * override.persist();
 * </pre>
 */
@Entity
@Table(
    name = "pricing_rule_overrides",
    indexes = {
        @Index(name = "idx_pro_contract", columnList = "contract_uuid"),
        @Index(name = "idx_pro_contract_rule", columnList = "contract_uuid, rule_id"),
        @Index(name = "idx_pro_active", columnList = "active"),
        @Index(name = "idx_pro_valid_from", columnList = "valid_from")
    }
)
@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class PricingRuleOverride extends AbstractRuleOverrideEntity<PricingRuleStepEntity> {

    @EqualsAndHashCode.Include
    @Column(name = "contract_uuid", nullable = false, length = 36)
    @NotBlank(message = "Contract UUID is required")
    private String contractUuid;

    @Column(name = "override_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Override type is required")
    private OverrideType overrideType;

    /**
     * Type of pricing rule (optional for MODIFY/DISABLE, required for REPLACE).
     */
    @Column(name = "rule_step_type", length = 50)
    @Enumerated(EnumType.STRING)
    private RuleStepType ruleStepType;

    /**
     * Calculation base (optional for MODIFY/DISABLE, required for REPLACE).
     */
    @Column(name = "step_base", length = 50)
    @Enumerated(EnumType.STRING)
    private StepBase stepBase;

    /**
     * Percentage value for percentage-based rules (optional for MODIFY).
     */
    @Column(precision = 10, scale = 4)
    private BigDecimal percent;

    /**
     * Fixed amount for FIXED_DEDUCTION rules (optional for MODIFY).
     */
    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    /**
     * Reference to contract_type_items key (optional for MODIFY).
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
     * Email of the user who created this override (for audit purposes).
     */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /**
     * Merge this override with a base pricing rule according to the override strategy.
     *
     * @param baseRule The base rule to merge with (may be null for REPLACE strategy)
     * @return The merged rule, or null if the rule should be disabled
     * @throws IllegalArgumentException if baseRule is null for MODIFY/DISABLE strategies
     */
    @Override
    public PricingRuleStepEntity merge(PricingRuleStepEntity baseRule) {
        if (baseRule == null && overrideType != OverrideType.REPLACE) {
            throw new IllegalArgumentException(
                String.format("Cannot %s non-existent rule '%s' for contract '%s'",
                    overrideType.name().toLowerCase(), ruleId, contractUuid)
            );
        }

        switch (overrideType) {
            case REPLACE:
                return toPricingRuleStep();
            case DISABLE:
                return null;
            case MODIFY:
                return mergeAttributes(baseRule);
            default:
                throw new IllegalStateException("Unknown override type: " + overrideType);
        }
    }

    /**
     * Merge override attributes with base rule attributes.
     * Only non-null override fields replace base rule fields.
     *
     * @param base The base rule to merge with
     * @return A new rule entity with merged attributes
     */
    private PricingRuleStepEntity mergeAttributes(PricingRuleStepEntity base) {
        PricingRuleStepEntity merged = new PricingRuleStepEntity();

        // Copy all base attributes
        merged.setContractTypeCode(base.getContractTypeCode());
        merged.setRuleId(base.getRuleId());
        merged.setLabel(base.getLabel());
        merged.setRuleStepType(base.getRuleStepType());
        merged.setStepBase(base.getStepBase());
        merged.setPercent(base.getPercent());
        merged.setAmount(base.getAmount());
        merged.setParamKey(base.getParamKey());
        merged.setValidFrom(base.getValidFrom());
        merged.setValidTo(base.getValidTo());
        merged.setPriority(base.getPriority());
        merged.setActive(base.isActive());

        // Apply overrides (non-null values take precedence)
        if (this.label != null) {
            merged.setLabel(this.label);
        }
        if (this.ruleStepType != null) {
            merged.setRuleStepType(this.ruleStepType);
        }
        if (this.stepBase != null) {
            merged.setStepBase(this.stepBase);
        }
        if (this.percent != null) {
            merged.setPercent(this.percent);
        }
        if (this.amount != null) {
            merged.setAmount(this.amount);
        }
        if (this.paramKey != null) {
            merged.setParamKey(this.paramKey);
        }
        if (this.validFrom != null) {
            merged.setValidFrom(this.validFrom);
        }
        if (this.validTo != null) {
            merged.setValidTo(this.validTo);
        }
        if (this.priority != null) {
            merged.setPriority(this.priority);
        }

        return merged;
    }

    /**
     * Convert this override to a standalone pricing rule step entity.
     * Used for REPLACE strategy.
     *
     * @return A new pricing rule step entity populated from override values
     */
    private PricingRuleStepEntity toPricingRuleStep() {
        PricingRuleStepEntity rule = new PricingRuleStepEntity();
        rule.setRuleId(this.ruleId);
        rule.setLabel(this.label != null ? this.label : "Override for " + this.ruleId);
        rule.setRuleStepType(this.ruleStepType);
        rule.setStepBase(this.stepBase);
        rule.setPercent(this.percent);
        rule.setAmount(this.amount);
        rule.setParamKey(this.paramKey);
        rule.setValidFrom(this.validFrom);
        rule.setValidTo(this.validTo);
        rule.setPriority(this.priority != null ? this.priority : 100);
        rule.setActive(this.active);
        // Note: contractTypeCode is not set as this is a contract-specific rule
        return rule;
    }

    /**
     * Check if this override is applicable on a given date.
     * Checks both the active flag and the date range (validFrom and validTo).
     *
     * @param date The date to check applicability for
     * @return true if the override is active and the date is within the valid range
     */
    @Override
    public boolean isApplicable(LocalDate date) {
        if (!active) return false;
        if (validFrom != null && date.isBefore(validFrom)) return false;
        if (validTo != null && !date.isBefore(validTo)) return false;
        return true;
    }

    @PrePersist
    protected void onCreate() {
        super.onCreate();
        validateDateRange();
    }

    @PreUpdate
    protected void onUpdate() {
        super.onUpdate();
        validateDateRange();
    }

    /**
     * Validate that validFrom is before validTo (if both are set).
     */
    private void validateDateRange() {
        if (validFrom != null && validTo != null && !validFrom.isBefore(validTo)) {
            throw new IllegalArgumentException("validFrom must be before validTo");
        }
    }

    // --- Panache finder methods ---

    /**
     * Find all active pricing rule overrides for a specific contract.
     *
     * @param contractUuid The contract UUID
     * @return List of active overrides, sorted by priority
     */
    public static List<PricingRuleOverride> findByContract(String contractUuid) {
        return find("contractUuid = ?1 AND active = true ORDER BY priority", contractUuid).list();
    }

    /**
     * Find all pricing rule overrides for a contract (including inactive).
     *
     * @param contractUuid The contract UUID
     * @return List of all overrides, sorted by priority
     */
    public static List<PricingRuleOverride> findByContractIncludingInactive(String contractUuid) {
        return find("contractUuid = ?1 ORDER BY priority, active DESC", contractUuid).list();
    }

    /**
     * Find a specific pricing rule override by contract and rule ID.
     *
     * @param contractUuid The contract UUID
     * @param ruleId The rule ID
     * @return The override, or null if not found
     */
    public static PricingRuleOverride findByContractAndRule(String contractUuid, String ruleId) {
        return find("contractUuid = ?1 AND ruleId = ?2", contractUuid, ruleId).firstResult();
    }

    /**
     * Find pricing rule overrides active on a specific date for a contract.
     *
     * @param contractUuid The contract UUID
     * @param date The date to check
     * @return List of active overrides for the given date, sorted by priority
     */
    public static List<PricingRuleOverride> findByContractAndDate(String contractUuid, LocalDate date) {
        return find(
            "contractUuid = ?1 AND active = true AND " +
            "(validFrom IS NULL OR validFrom <= ?2) AND " +
            "(validTo IS NULL OR validTo > ?2) " +
            "ORDER BY priority",
            contractUuid, date
        ).list();
    }

    /**
     * Find active overrides of a specific type for a contract.
     *
     * @param contractUuid The contract UUID
     * @param overrideType The override type to filter by
     * @return List of matching active overrides
     */
    public static List<PricingRuleOverride> findByContractAndType(
        String contractUuid, OverrideType overrideType) {
        return find("contractUuid = ?1 AND overrideType = ?2 AND active = true",
            contractUuid, overrideType).list();
    }

    /**
     * Check if an override already exists for a contract and rule.
     *
     * @param contractUuid The contract UUID
     * @param ruleId The rule ID
     * @return true if an override exists (active or inactive)
     */
    public static boolean existsByContractAndRule(String contractUuid, String ruleId) {
        return count("contractUuid = ?1 AND ruleId = ?2", contractUuid, ruleId) > 0;
    }

    /**
     * Delete all overrides for a specific contract (soft delete).
     *
     * @param contractUuid The contract UUID
     * @return Number of overrides soft-deleted
     */
    public static long softDeleteByContract(String contractUuid) {
        return update("active = false WHERE contractUuid = ?1", contractUuid);
    }
}
