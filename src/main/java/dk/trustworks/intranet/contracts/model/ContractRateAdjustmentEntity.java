package dk.trustworks.intranet.contracts.model;

import dk.trustworks.intranet.contracts.model.enums.AdjustmentFrequency;
import dk.trustworks.intranet.contracts.model.enums.AdjustmentType;
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
 * JPA Entity for contract rate adjustment rules.
 * Stores rate modification rules that apply to contract types over time.
 *
 * <p>Examples:
 * <ul>
 *   <li>ANNUAL_INCREASE - 3% yearly rate increase</li>
 *   <li>INFLATION_LINKED - Adjust rates based on CPI</li>
 *   <li>STEP_BASED - Tiered increases at milestones</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * ContractRateAdjustmentEntity rule = new ContractRateAdjustmentEntity();
 * rule.setContractTypeCode("CONSULTING_2025");
 * rule.setRuleId("annual-increase-2025");
 * rule.setLabel("3% annual rate increase");
 * rule.setAdjustmentType(AdjustmentType.ANNUAL_INCREASE);
 * rule.setAdjustmentPercent(BigDecimal.valueOf(3.0));
 * rule.setFrequency(AdjustmentFrequency.YEARLY);
 * rule.setEffectiveDate(LocalDate.of(2025, 1, 1));
 * rule.setPriority(10);
 * rule.persist();
 * </pre>
 */
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "contract_rate_adjustments")
public class ContractRateAdjustmentEntity extends PanacheEntityBase {

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
     * Stable identifier for the rule (e.g., "annual-increase-2025").
     * Must be unique per contract type.
     */
    @Column(name = "rule_id", nullable = false, length = 64)
    @NotBlank(message = "Rule ID is required")
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Rule ID must contain only lowercase letters, numbers, and hyphens")
    private String ruleId;

    /**
     * Display label for the rule (e.g., "3% annual rate increase").
     */
    @Column(nullable = false)
    @NotBlank(message = "Label is required")
    @Size(max = 255, message = "Label must not exceed 255 characters")
    private String label;

    /**
     * Type of rate adjustment.
     */
    @Column(name = "adjustment_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @NotNull(message = "Adjustment type is required")
    private AdjustmentType adjustmentType;

    /**
     * Percentage adjustment (-100 to 1000).
     * Positive values increase rates, negative values decrease them.
     */
    @Column(name = "adjustment_percent", precision = 5, scale = 2)
    @DecimalMin(value = "-100.0", message = "Adjustment percent must be >= -100")
    @DecimalMax(value = "1000.0", message = "Adjustment percent must be <= 1000")
    private BigDecimal adjustmentPercent;

    /**
     * How often the adjustment repeats.
     */
    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private AdjustmentFrequency frequency;

    /**
     * Date when this adjustment takes effect (required).
     */
    @Column(name = "effective_date", nullable = false)
    @NotNull(message = "Effective date is required")
    private LocalDate effectiveDate;

    /**
     * Date when this adjustment stops (nullable = ongoing).
     */
    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * Application order - lower numbers execute first (10, 20, 30...).
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
        if (effectiveDate != null && endDate != null && !effectiveDate.isBefore(endDate)) {
            throw new IllegalArgumentException("effective_date must be before end_date");
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();

        // Validate date range
        if (effectiveDate != null && endDate != null && !effectiveDate.isBefore(endDate)) {
            throw new IllegalArgumentException("effective_date must be before end_date");
        }
    }

    // --- Panache finder methods ---

    /**
     * Find all rate adjustments for a specific contract type (active only).
     *
     * @param contractTypeCode The contract type code
     * @return List of active adjustments, sorted by priority
     */
    public static List<ContractRateAdjustmentEntity> findByContractType(String contractTypeCode) {
        return find("contractTypeCode = ?1 AND active = true ORDER BY priority", contractTypeCode).list();
    }

    /**
     * Find all rate adjustments for a specific contract type (including inactive).
     *
     * @param contractTypeCode The contract type code
     * @return List of all adjustments, sorted by priority
     */
    public static List<ContractRateAdjustmentEntity> findByContractTypeIncludingInactive(String contractTypeCode) {
        return find("contractTypeCode = ?1 ORDER BY priority, active DESC", contractTypeCode).list();
    }

    /**
     * Find a specific rate adjustment by contract type and rule ID.
     *
     * @param contractTypeCode The contract type code
     * @param ruleId The rule ID
     * @return The adjustment, or null if not found
     */
    public static ContractRateAdjustmentEntity findByContractTypeAndRuleId(String contractTypeCode, String ruleId) {
        return find("contractTypeCode = ?1 AND ruleId = ?2", contractTypeCode, ruleId).firstResult();
    }

    /**
     * Find adjustments active on a specific date.
     *
     * @param contractTypeCode The contract type code
     * @param date The date to check
     * @return List of active adjustments for the given date, sorted by priority
     */
    public static List<ContractRateAdjustmentEntity> findByContractTypeAndDate(String contractTypeCode, LocalDate date) {
        return find(
            "contractTypeCode = ?1 AND active = true AND " +
            "effectiveDate <= ?2 AND " +
            "(endDate IS NULL OR endDate > ?2) " +
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
     * @return The highest priority, or 0 if no adjustments exist
     */
    public static int getMaxPriority(String contractTypeCode) {
        Integer max = (Integer) getEntityManager()
            .createQuery("SELECT MAX(r.priority) FROM ContractRateAdjustmentEntity r WHERE r.contractTypeCode = :code")
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
     * Reactivate a soft-deleted adjustment.
     */
    public void activate() {
        this.active = true;
        this.persist();
    }

    /**
     * Check if this adjustment is active on a specific date.
     *
     * @param date The date to check
     * @return true if the adjustment is active on the given date
     */
    public boolean isActiveOn(LocalDate date) {
        if (!active) return false;
        if (effectiveDate != null && date.isBefore(effectiveDate)) return false;
        if (endDate != null && !date.isBefore(endDate)) return false;
        return true;
    }
}
