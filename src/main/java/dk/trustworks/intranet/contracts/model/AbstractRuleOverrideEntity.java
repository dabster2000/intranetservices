package dk.trustworks.intranet.contracts.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Abstract base class for all rule override entities.
 * Provides common fields and lifecycle management for contract-level rule overrides.
 *
 * <p>This class is extended by:
 * <ul>
 *   <li>{@link ContractValidationOverride} - Validation rule overrides</li>
 *   <li>{@link ContractRateAdjustmentOverride} - Rate adjustment overrides</li>
 *   <li>{@link PricingRuleOverride} - Pricing rule overrides</li>
 * </ul>
 *
 * <p>Subclasses must implement:
 * <ul>
 *   <li>{@link #merge(Object)} - Define merge logic for combining override with base rule</li>
 *   <li>{@link #isApplicable(LocalDate)} - Define temporal applicability logic</li>
 * </ul>
 *
 * @param <T> The type of base rule entity that this override applies to
 */
@MappedSuperclass
@Data
@EqualsAndHashCode(callSuper = false)
public abstract class AbstractRuleOverrideEntity<T> extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Integer id;

    /**
     * Stable identifier for the rule being overridden (e.g., "ski-notes-required").
     * Must match the ruleId in the base rule entity.
     */
    @Column(name = "rule_id", nullable = false, length = 64)
    protected String ruleId;

    /**
     * Display label for the override (e.g., "Contract-specific notes requirement").
     * Can differ from the base rule label to indicate it's an override.
     */
    @Column(nullable = false, length = 255)
    protected String label;

    /**
     * Evaluation order - lower numbers execute first (10, 20, 30...).
     * Allows controlling override precedence if multiple overrides exist.
     */
    @Column
    protected Integer priority;

    /**
     * Soft delete flag. When false, the override is ignored.
     */
    @Column(nullable = false)
    protected boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    protected LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    protected LocalDateTime updatedAt;

    /**
     * Merge this override with the base rule according to the override strategy.
     *
     * <p>Implementation guidelines:
     * <ul>
     *   <li><b>REPLACE</b> - Return a new rule entity populated entirely from override values</li>
     *   <li><b>DISABLE</b> - Return null to indicate the rule should not be applied</li>
     *   <li><b>MODIFY</b> - Copy base rule, then apply non-null override fields</li>
     * </ul>
     *
     * @param baseRule The base rule to merge with (may be null for REPLACE strategy)
     * @return The merged rule, or null if the rule should be disabled
     * @throws IllegalArgumentException if baseRule is null for MODIFY/DISABLE strategies
     */
    public abstract T merge(T baseRule);

    /**
     * Check if this override is applicable on a given date.
     *
     * <p>Default implementation checks only the active flag.
     * Subclasses with temporal fields (effectiveDate, validFrom, etc.) should override
     * to include date range validation.
     *
     * @param date The date to check applicability for
     * @return true if the override should be applied on the given date
     */
    public abstract boolean isApplicable(LocalDate date);

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Soft delete by setting active to false.
     * The override will be ignored but remains in the database for audit purposes.
     */
    public void softDelete() {
        this.active = false;
        this.persist();
    }

    /**
     * Reactivate a soft-deleted override.
     */
    public void activate() {
        this.active = true;
        this.persist();
    }
}
