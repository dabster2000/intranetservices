package dk.trustworks.intranet.contracts.model;

import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * JPA Entity for contract validation rules.
 * Stores business constraint rules that apply to contract types.
 *
 * <p>Examples:
 * <ul>
 *   <li>NOTES_REQUIRED - Time registration must include comments</li>
 *   <li>MIN_HOURS_PER_ENTRY - Minimum work duration threshold</li>
 *   <li>MAX_HOURS_PER_DAY - Maximum daily hours limit</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * ContractValidationRuleEntity rule = new ContractValidationRuleEntity();
 * rule.setContractTypeCode("SKI0217_2025");
 * rule.setRuleId("ski-notes-required");
 * rule.setLabel("Notes required for time registration");
 * rule.setValidationType(ValidationType.NOTES_REQUIRED);
 * rule.setRequired(true);
 * rule.setPriority(10);
 * rule.persist();
 * </pre>
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "contract_validation_rules")
public class ContractValidationRuleEntity extends PanacheEntityBase {

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
     * Stable identifier for the rule (e.g., "ski-notes-required").
     * Must be unique per contract type.
     */
    @Column(name = "rule_id", nullable = false, length = 64)
    @NotBlank(message = "Rule ID is required")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Rule ID must contain only lowercase letters, numbers, and hyphens")
    private String ruleId;

    /**
     * Display label for the rule (e.g., "Notes required for time registration").
     */
    @Column(nullable = false)
    @NotBlank(message = "Label is required")
    @Size(max = 255, message = "Label must not exceed 255 characters")
    private String label;

    /**
     * Type of validation rule.
     */
    @Column(name = "validation_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Validation type is required")
    private ValidationType validationType;

    /**
     * Boolean flag for validations that require a yes/no answer.
     * Used by: NOTES_REQUIRED, REQUIRE_TASK_SELECTION
     */
    @Column(nullable = false)
    private boolean required = false;

    /**
     * Numeric threshold for validations with limits.
     * Used by: MIN_HOURS_PER_ENTRY, MAX_HOURS_PER_DAY
     */
    @Column(name = "threshold_value", precision = 10, scale = 2)
    @DecimalMin(value = "0.0", message = "Threshold value must be non-negative")
    private BigDecimal thresholdValue;

    /**
     * Additional configuration in JSON format for complex rules.
     * Optional - used for future extensibility.
     */
    @Column(name = "config_json", columnDefinition = "JSON")
    private String configJson;

    /**
     * Evaluation order - lower numbers execute first (10, 20, 30...).
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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // --- Panache finder methods ---

    /**
     * Find all validation rules for a specific contract type (active only).
     *
     * @param contractTypeCode The contract type code
     * @return List of active rules, sorted by priority
     */
    public static List<ContractValidationRuleEntity> findByContractType(String contractTypeCode) {
        return find("contractTypeCode = ?1 AND active = true ORDER BY priority", contractTypeCode).list();
    }

    /**
     * Find all validation rules for a specific contract type (including inactive).
     *
     * @param contractTypeCode The contract type code
     * @return List of all rules, sorted by priority
     */
    public static List<ContractValidationRuleEntity> findByContractTypeIncludingInactive(String contractTypeCode) {
        return find("contractTypeCode = ?1 ORDER BY priority, active DESC", contractTypeCode).list();
    }

    /**
     * Find a specific validation rule by contract type and rule ID.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @return The rule, or null if not found
     */
    public static ContractValidationRuleEntity findByContractTypeAndRuleId(String contractTypeCode, String ruleId) {
        return find("contractTypeCode = ?1 AND ruleId = ?2", contractTypeCode, ruleId).firstResult();
    }

    /**
     * Find validation rules of a specific type for a contract.
     *
     * @param contractTypeCode The contract type code
     * @param validationType The validation type to filter by
     * @return List of matching active rules
     */
    public static List<ContractValidationRuleEntity> findByContractTypeAndValidationType(
            String contractTypeCode, ValidationType validationType) {
        return find("contractTypeCode = ?1 AND validationType = ?2 AND active = true",
                   contractTypeCode, validationType).list();
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
            .createQuery("SELECT MAX(r.priority) FROM ContractValidationRuleEntity r WHERE r.contractTypeCode = :code")
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
}
