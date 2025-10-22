package dk.trustworks.intranet.contracts.model;

import dk.trustworks.intranet.contracts.model.enums.OverrideType;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * JPA Entity for contract-level validation rule overrides.
 * Allows specific contracts to override validation rules defined at the contract type level.
 *
 * <p>Use cases:
 * <ul>
 *   <li>Customer-specific validation requirements (e.g., require notes for specific client)</li>
 *   <li>Temporarily disable a validation for a specific contract</li>
 *   <li>Adjust threshold values for specific contracts</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * // Modify threshold for a specific contract
 * ContractValidationOverride override = new ContractValidationOverride();
 * override.setContractUuid("contract-uuid-123");
 * override.setRuleId("max-hours-per-day");
 * override.setLabel("Increased daily hours limit for this contract");
 * override.setOverrideType(OverrideType.MODIFY);
 * override.setThresholdValue(BigDecimal.valueOf(12.0)); // Override only threshold
 * override.setCreatedBy("admin@trustworks.dk");
 * override.persist();
 * </pre>
 */
@Entity
@Table(
    name = "contract_validation_overrides",
    indexes = {
        @Index(name = "idx_cvo_contract", columnList = "contract_uuid"),
        @Index(name = "idx_cvo_contract_rule", columnList = "contract_uuid, rule_id"),
        @Index(name = "idx_cvo_active", columnList = "active")
    }
)
@Data
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ContractValidationOverride extends AbstractRuleOverrideEntity<ContractValidationRuleEntity> {

    @EqualsAndHashCode.Include
    @Column(name = "contract_uuid", nullable = false, length = 36)
    @NotBlank(message = "Contract UUID is required")
    private String contractUuid;

    @Column(name = "override_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Override type is required")
    private OverrideType overrideType;

    /**
     * Type of validation rule (optional for MODIFY/DISABLE, required for REPLACE).
     */
    @Column(name = "validation_type", length = 50)
    @Enumerated(EnumType.STRING)
    private ValidationType validationType;

    /**
     * Boolean flag for validations (optional for MODIFY, required for REPLACE).
     */
    @Column
    private Boolean required;

    /**
     * Numeric threshold for validations (optional for MODIFY, may be required for REPLACE).
     */
    @Column(name = "threshold_value", precision = 10, scale = 2)
    private BigDecimal thresholdValue;

    /**
     * Additional configuration in JSON format (optional).
     */
    @Column(name = "config_json", columnDefinition = "JSON")
    private String configJson;

    /**
     * Email of the user who created this override (for audit purposes).
     */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /**
     * Merge this override with a base validation rule according to the override strategy.
     *
     * @param baseRule The base rule to merge with (may be null for REPLACE strategy)
     * @return The merged rule, or null if the rule should be disabled
     * @throws IllegalArgumentException if baseRule is null for MODIFY/DISABLE strategies
     */
    @Override
    public ContractValidationRuleEntity merge(ContractValidationRuleEntity baseRule) {
        if (baseRule == null && overrideType != OverrideType.REPLACE) {
            throw new IllegalArgumentException(
                String.format("Cannot %s non-existent rule '%s' for contract '%s'",
                    overrideType.name().toLowerCase(), ruleId, contractUuid)
            );
        }

        switch (overrideType) {
            case REPLACE:
                return toValidationRule();
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
    private ContractValidationRuleEntity mergeAttributes(ContractValidationRuleEntity base) {
        ContractValidationRuleEntity merged = new ContractValidationRuleEntity();

        // Copy all base attributes
        merged.setContractTypeCode(base.getContractTypeCode());
        merged.setRuleId(base.getRuleId());
        merged.setLabel(base.getLabel());
        merged.setValidationType(base.getValidationType());
        merged.setRequired(base.isRequired());
        merged.setThresholdValue(base.getThresholdValue());
        merged.setConfigJson(base.getConfigJson());
        merged.setPriority(base.getPriority());
        merged.setActive(base.isActive());

        // Apply overrides (non-null values take precedence)
        if (this.label != null) {
            merged.setLabel(this.label);
        }
        if (this.validationType != null) {
            merged.setValidationType(this.validationType);
        }
        if (this.required != null) {
            merged.setRequired(this.required);
        }
        if (this.thresholdValue != null) {
            merged.setThresholdValue(this.thresholdValue);
        }
        if (this.configJson != null) {
            merged.setConfigJson(this.configJson);
        }
        if (this.priority != null) {
            merged.setPriority(this.priority);
        }

        return merged;
    }

    /**
     * Convert this override to a standalone validation rule entity.
     * Used for REPLACE strategy.
     *
     * @return A new validation rule entity populated from override values
     */
    private ContractValidationRuleEntity toValidationRule() {
        ContractValidationRuleEntity rule = new ContractValidationRuleEntity();
        rule.setRuleId(this.ruleId);
        rule.setLabel(this.label != null ? this.label : "Override for " + this.ruleId);
        rule.setValidationType(this.validationType);
        rule.setRequired(this.required != null ? this.required : false);
        rule.setThresholdValue(this.thresholdValue);
        rule.setConfigJson(this.configJson);
        rule.setPriority(this.priority != null ? this.priority : 100);
        rule.setActive(this.active);
        // Note: contractTypeCode is not set as this is a contract-specific rule
        return rule;
    }

    /**
     * Check if this override is applicable on a given date.
     * Validation overrides have no temporal constraints, only the active flag matters.
     *
     * @param date The date to check (ignored for validation overrides)
     * @return true if the override is active
     */
    @Override
    public boolean isApplicable(LocalDate date) {
        return active;
    }

    // --- Panache finder methods ---

    /**
     * Find all active validation overrides for a specific contract.
     *
     * @param contractUuid The contract UUID
     * @return List of active overrides, sorted by priority
     */
    public static List<ContractValidationOverride> findByContract(String contractUuid) {
        return find("contractUuid = ?1 AND active = true ORDER BY priority", contractUuid).list();
    }

    /**
     * Find all validation overrides for a contract (including inactive).
     *
     * @param contractUuid The contract UUID
     * @return List of all overrides, sorted by priority
     */
    public static List<ContractValidationOverride> findByContractIncludingInactive(String contractUuid) {
        return find("contractUuid = ?1 ORDER BY priority, active DESC", contractUuid).list();
    }

    /**
     * Find a specific validation override by contract and rule ID.
     *
     * @param contractUuid The contract UUID
     * @param ruleId The rule ID
     * @return The override, or null if not found
     */
    public static ContractValidationOverride findByContractAndRule(String contractUuid, String ruleId) {
        return find("contractUuid = ?1 AND ruleId = ?2", contractUuid, ruleId).firstResult();
    }

    /**
     * Find active overrides of a specific type for a contract.
     *
     * @param contractUuid The contract UUID
     * @param overrideType The override type to filter by
     * @return List of matching active overrides
     */
    public static List<ContractValidationOverride> findByContractAndType(
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
