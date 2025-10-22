package dk.trustworks.intranet.contracts.model;

import dk.trustworks.intranet.contracts.model.enums.AdjustmentFrequency;
import dk.trustworks.intranet.contracts.model.enums.AdjustmentType;
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
 * JPA Entity for contract-level rate adjustment rule overrides.
 * Allows specific contracts to override rate adjustment rules defined at the contract type level.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Customer-specific rate adjustments (e.g., different inflation rate for specific client)</li>
 *   <li>Temporarily disable annual increase for a specific contract</li>
 *   <li>Apply contract-specific rate adjustments with custom dates</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * // Override annual increase percentage for a specific contract
 * ContractRateAdjustmentOverride override = new ContractRateAdjustmentOverride();
 * override.setContractUuid("contract-uuid-123");
 * override.setRuleId("annual-increase-2025");
 * override.setLabel("Custom 5% annual increase for this contract");
 * override.setOverrideType(OverrideType.MODIFY);
 * override.setAdjustmentPercent(BigDecimal.valueOf(5.0)); // Override only percentage
 * override.setEffectiveDate(LocalDate.of(2025, 1, 1));
 * override.setCreatedBy("admin@trustworks.dk");
 * override.persist();
 * </pre>
 */
@Entity
@Table(
    name = "contract_rate_adjustment_overrides",
    indexes = {
        @Index(name = "idx_crao_contract", columnList = "contract_uuid"),
        @Index(name = "idx_crao_contract_rule", columnList = "contract_uuid, rule_id"),
        @Index(name = "idx_crao_active", columnList = "active"),
        @Index(name = "idx_crao_effective", columnList = "effective_date")
    }
)
@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ContractRateAdjustmentOverride extends AbstractRuleOverrideEntity<ContractRateAdjustmentEntity> {

    @EqualsAndHashCode.Include
    @Column(name = "contract_uuid", nullable = false, length = 36)
    @NotBlank(message = "Contract UUID is required")
    private String contractUuid;

    @Column(name = "override_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Override type is required")
    private OverrideType overrideType;

    /**
     * Type of rate adjustment (optional for MODIFY/DISABLE, required for REPLACE).
     */
    @Column(name = "adjustment_type", length = 50)
    @Enumerated(EnumType.STRING)
    private AdjustmentType adjustmentType;

    /**
     * Percentage adjustment (optional for MODIFY, required for REPLACE with percentage-based types).
     */
    @Column(name = "adjustment_percent", precision = 5, scale = 2)
    private BigDecimal adjustmentPercent;

    /**
     * How often the adjustment repeats (optional for MODIFY/DISABLE, required for REPLACE).
     */
    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private AdjustmentFrequency frequency;

    /**
     * Date when this adjustment override takes effect (nullable for MODIFY/DISABLE).
     */
    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    /**
     * Date when this adjustment override stops (nullable = ongoing).
     */
    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * Email of the user who created this override (for audit purposes).
     */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /**
     * Merge this override with a base rate adjustment rule according to the override strategy.
     *
     * @param baseRule The base rule to merge with (may be null for REPLACE strategy)
     * @return The merged rule, or null if the rule should be disabled
     * @throws IllegalArgumentException if baseRule is null for MODIFY/DISABLE strategies
     */
    @Override
    public ContractRateAdjustmentEntity merge(ContractRateAdjustmentEntity baseRule) {
        if (baseRule == null && overrideType != OverrideType.REPLACE) {
            throw new IllegalArgumentException(
                String.format("Cannot %s non-existent rule '%s' for contract '%s'",
                    overrideType.name().toLowerCase(), ruleId, contractUuid)
            );
        }

        switch (overrideType) {
            case REPLACE:
                return toRateAdjustmentRule();
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
    private ContractRateAdjustmentEntity mergeAttributes(ContractRateAdjustmentEntity base) {
        ContractRateAdjustmentEntity merged = new ContractRateAdjustmentEntity();

        // Copy all base attributes
        merged.setContractTypeCode(base.getContractTypeCode());
        merged.setRuleId(base.getRuleId());
        merged.setLabel(base.getLabel());
        merged.setAdjustmentType(base.getAdjustmentType());
        merged.setAdjustmentPercent(base.getAdjustmentPercent());
        merged.setFrequency(base.getFrequency());
        merged.setEffectiveDate(base.getEffectiveDate());
        merged.setEndDate(base.getEndDate());
        merged.setPriority(base.getPriority());
        merged.setActive(base.isActive());

        // Apply overrides (non-null values take precedence)
        if (this.label != null) {
            merged.setLabel(this.label);
        }
        if (this.adjustmentType != null) {
            merged.setAdjustmentType(this.adjustmentType);
        }
        if (this.adjustmentPercent != null) {
            merged.setAdjustmentPercent(this.adjustmentPercent);
        }
        if (this.frequency != null) {
            merged.setFrequency(this.frequency);
        }
        if (this.effectiveDate != null) {
            merged.setEffectiveDate(this.effectiveDate);
        }
        if (this.endDate != null) {
            merged.setEndDate(this.endDate);
        }
        if (this.priority != null) {
            merged.setPriority(this.priority);
        }

        return merged;
    }

    /**
     * Convert this override to a standalone rate adjustment rule entity.
     * Used for REPLACE strategy.
     *
     * @return A new rate adjustment entity populated from override values
     */
    private ContractRateAdjustmentEntity toRateAdjustmentRule() {
        ContractRateAdjustmentEntity rule = new ContractRateAdjustmentEntity();
        rule.setRuleId(this.ruleId);
        rule.setLabel(this.label != null ? this.label : "Override for " + this.ruleId);
        rule.setAdjustmentType(this.adjustmentType);
        rule.setAdjustmentPercent(this.adjustmentPercent);
        rule.setFrequency(this.frequency);
        rule.setEffectiveDate(this.effectiveDate != null ? this.effectiveDate : LocalDate.now());
        rule.setEndDate(this.endDate);
        rule.setPriority(this.priority != null ? this.priority : 100);
        rule.setActive(this.active);
        // Note: contractTypeCode is not set as this is a contract-specific rule
        return rule;
    }

    /**
     * Check if this override is applicable on a given date.
     * Checks both the active flag and the date range (effectiveDate and endDate).
     *
     * @param date The date to check applicability for
     * @return true if the override is active and the date is within the effective range
     */
    @Override
    public boolean isApplicable(LocalDate date) {
        if (!active) return false;
        if (effectiveDate != null && date.isBefore(effectiveDate)) return false;
        if (endDate != null && !date.isBefore(endDate)) return false;
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
     * Validate that effectiveDate is before endDate (if both are set).
     */
    private void validateDateRange() {
        if (effectiveDate != null && endDate != null && !effectiveDate.isBefore(endDate)) {
            throw new IllegalArgumentException("effectiveDate must be before endDate");
        }
    }

    // --- Panache finder methods ---

    /**
     * Find all active rate adjustment overrides for a specific contract.
     *
     * @param contractUuid The contract UUID
     * @return List of active overrides, sorted by priority
     */
    public static List<ContractRateAdjustmentOverride> findByContract(String contractUuid) {
        return find("contractUuid = ?1 AND active = true ORDER BY priority", contractUuid).list();
    }

    /**
     * Find all rate adjustment overrides for a contract (including inactive).
     *
     * @param contractUuid The contract UUID
     * @return List of all overrides, sorted by priority
     */
    public static List<ContractRateAdjustmentOverride> findByContractIncludingInactive(String contractUuid) {
        return find("contractUuid = ?1 ORDER BY priority, active DESC", contractUuid).list();
    }

    /**
     * Find a specific rate adjustment override by contract and rule ID.
     *
     * @param contractUuid The contract UUID
     * @param ruleId The rule ID
     * @return The override, or null if not found
     */
    public static ContractRateAdjustmentOverride findByContractAndRule(String contractUuid, String ruleId) {
        return find("contractUuid = ?1 AND ruleId = ?2", contractUuid, ruleId).firstResult();
    }

    /**
     * Find rate adjustment overrides active on a specific date for a contract.
     *
     * @param contractUuid The contract UUID
     * @param date The date to check
     * @return List of active overrides for the given date, sorted by priority
     */
    public static List<ContractRateAdjustmentOverride> findByContractAndDate(String contractUuid, LocalDate date) {
        return find(
            "contractUuid = ?1 AND active = true AND " +
            "(effectiveDate IS NULL OR effectiveDate <= ?2) AND " +
            "(endDate IS NULL OR endDate > ?2) " +
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
    public static List<ContractRateAdjustmentOverride> findByContractAndType(
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
