package dk.trustworks.intranet.contracts.model.enums;

/**
 * Enum defining the three strategies for rule overrides at the contract level.
 *
 * <p>Override strategies:
 * <ul>
 *   <li><b>REPLACE</b> - Completely replace the base rule with override values</li>
 *   <li><b>DISABLE</b> - Deactivate the base rule for this contract</li>
 *   <li><b>MODIFY</b> - Merge specific override fields with the base rule</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * ContractValidationOverride override = new ContractValidationOverride();
 * override.setOverrideType(OverrideType.MODIFY);
 * override.setThresholdValue(BigDecimal.valueOf(10.0)); // Only override threshold
 * </pre>
 */
public enum OverrideType {
    /**
     * Replace the entire base rule with override values.
     * All fields from the override are used.
     */
    REPLACE,

    /**
     * Disable the base rule for this specific contract.
     * The rule will not be applied during validation/calculation.
     */
    DISABLE,

    /**
     * Modify specific fields of the base rule.
     * Only non-null override fields are merged with base rule fields.
     */
    MODIFY
}
