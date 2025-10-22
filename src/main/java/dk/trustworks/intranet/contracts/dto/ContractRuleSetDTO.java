package dk.trustworks.intranet.contracts.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO representing the complete effective rule set for a contract on a specific date.
 *
 * <p>This aggregated view includes:
 * <ul>
 *   <li>All validation rules (base + overrides applied)</li>
 *   <li>All rate adjustments (base + overrides applied)</li>
 *   <li>All pricing rules (base + overrides applied)</li>
 * </ul>
 *
 * <p>The rules returned are the <b>effective</b> rules after merge logic has been applied:
 * - REPLACE overrides completely replace base rules
 * - MODIFY overrides merge with base rules
 * - DISABLE overrides remove rules from the set
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Display effective rules in UI for a specific contract</li>
 *   <li>Apply validation rules during time registration</li>
 *   <li>Calculate adjusted rates during invoicing</li>
 *   <li>Apply pricing formulas during invoice generation</li>
 * </ul>
 *
 * <p><b>Example JSON Response:</b>
 * <pre>
 * {
 *   "contractUuid": "123e4567-e89b-12d3-a456-426614174000",
 *   "effectiveDate": "2025-01-20",
 *   "validationOverrides": [
 *     {
 *       "id": 1,
 *       "ruleId": "notes-required",
 *       "overrideType": "MODIFY",
 *       "required": false,
 *       "label": "Notes optional for this contract"
 *     }
 *   ],
 *   "rateOverrides": [
 *     {
 *       "id": 2,
 *       "ruleId": "annual-increase",
 *       "overrideType": "REPLACE",
 *       "adjustmentPercent": 5.0,
 *       "frequency": "YEARLY",
 *       "effectiveDate": "2025-01-01"
 *     }
 *   ],
 *   "pricingOverrides": [],
 *   "fromCache": true
 * }
 * </pre>
 *
 * @see ValidationOverrideDTO
 * @see RateOverrideDTO
 * @see PricingOverrideDTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContractRuleSetDTO {

    /**
     * UUID of the contract these rules apply to.
     */
    private String contractUuid;

    /**
     * Date for which the effective rules are calculated.
     * Temporal rules (rate adjustments, pricing) are filtered by this date.
     */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate effectiveDate;

    /**
     * List of validation rule overrides for this contract.
     * Empty list if no overrides exist.
     * These are the overrides only, not the merged effective rules.
     */
    @Builder.Default
    private List<ValidationOverrideDTO> validationOverrides = new ArrayList<>();

    /**
     * List of rate adjustment overrides for this contract.
     * Filtered to only include adjustments active on the effective date.
     * Empty list if no overrides exist.
     */
    @Builder.Default
    private List<RateOverrideDTO> rateOverrides = new ArrayList<>();

    /**
     * List of pricing rule overrides for this contract.
     * Filtered to only include pricing steps valid on the effective date.
     * Empty list if no overrides exist.
     */
    @Builder.Default
    private List<PricingOverrideDTO> pricingOverrides = new ArrayList<>();

    /**
     * List of ALL effective validation rules (framework rules + overrides merged).
     * This is the complete merged view of validation rules for this contract.
     * Empty list if contract has no contract type or no rules defined.
     */
    @Builder.Default
    private List<ValidationOverrideDTO> effectiveValidationRules = new ArrayList<>();

    /**
     * List of ALL effective rate adjustments (framework rules + overrides merged).
     * This is the complete merged view of rate adjustments for this contract.
     * Filtered to only include adjustments active on the effective date.
     * Empty list if contract has no contract type or no rules defined.
     */
    @Builder.Default
    private List<RateOverrideDTO> effectiveRateAdjustments = new ArrayList<>();

    /**
     * List of ALL effective pricing rules (framework rules + overrides merged).
     * This is the complete merged view of pricing rules for this contract.
     * Filtered to only include pricing steps valid on the effective date.
     * Empty list if contract has no contract type or no rules defined.
     */
    @Builder.Default
    private List<PricingOverrideDTO> effectivePricingRules = new ArrayList<>();

    /**
     * Indicates whether this data was served from cache.
     * True if retrieved from cache, false if freshly computed.
     * Useful for debugging and monitoring cache performance.
     */
    @Builder.Default
    private boolean fromCache = false;

    /**
     * Count of validation overrides.
     */
    public int getValidationOverrideCount() {
        return validationOverrides != null ? validationOverrides.size() : 0;
    }

    /**
     * Count of rate adjustment overrides.
     */
    public int getRateOverrideCount() {
        return rateOverrides != null ? rateOverrides.size() : 0;
    }

    /**
     * Count of pricing rule overrides.
     */
    public int getPricingOverrideCount() {
        return pricingOverrides != null ? pricingOverrides.size() : 0;
    }

    /**
     * Total count of all overrides.
     */
    public int getTotalOverrideCount() {
        return getValidationOverrideCount() + getRateOverrideCount() + getPricingOverrideCount();
    }

    /**
     * Check if this contract has any overrides.
     */
    public boolean hasOverrides() {
        return getTotalOverrideCount() > 0;
    }

    /**
     * Check if validation rules have been overridden.
     */
    public boolean hasValidationOverrides() {
        return validationOverrides != null && !validationOverrides.isEmpty();
    }

    /**
     * Check if rate adjustments have been overridden.
     */
    public boolean hasRateOverrides() {
        return rateOverrides != null && !rateOverrides.isEmpty();
    }

    /**
     * Check if pricing rules have been overridden.
     */
    public boolean hasPricingOverrides() {
        return pricingOverrides != null && !pricingOverrides.isEmpty();
    }

    /**
     * Get a specific validation override by rule ID.
     *
     * @param ruleId The rule ID to find
     * @return The override, or null if not found
     */
    public ValidationOverrideDTO getValidationOverride(String ruleId) {
        if (validationOverrides == null) {
            return null;
        }
        return validationOverrides.stream()
            .filter(o -> o.getRuleId().equals(ruleId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get a specific rate override by rule ID.
     *
     * @param ruleId The rule ID to find
     * @return The override, or null if not found
     */
    public RateOverrideDTO getRateOverride(String ruleId) {
        if (rateOverrides == null) {
            return null;
        }
        return rateOverrides.stream()
            .filter(o -> o.getRuleId().equals(ruleId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Get a specific pricing override by rule ID.
     *
     * @param ruleId The rule ID to find
     * @return The override, or null if not found
     */
    public PricingOverrideDTO getPricingOverride(String ruleId) {
        if (pricingOverrides == null) {
            return null;
        }
        return pricingOverrides.stream()
            .filter(o -> o.getRuleId().equals(ruleId))
            .findFirst()
            .orElse(null);
    }

    /**
     * Summary for logging/debugging.
     */
    @Override
    public String toString() {
        return String.format(
            "ContractRuleSetDTO[contract=%s, date=%s, overrides=%d (val:%d, rate:%d, pricing:%d), cached=%s]",
            contractUuid,
            effectiveDate,
            getTotalOverrideCount(),
            getValidationOverrideCount(),
            getRateOverrideCount(),
            getPricingOverrideCount(),
            fromCache
        );
    }
}
