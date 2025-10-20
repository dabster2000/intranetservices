package dk.trustworks.intranet.contracts.model.enums;

/**
 * Types of rate adjustments that can be applied to contract types.
 * These handle time-based rate modifications like annual increases and inflation adjustments.
 */
public enum AdjustmentType {
    /**
     * Annual percentage increase applied on anniversary date.
     * Example: 3% increase every year on contract start date
     */
    ANNUAL_INCREASE,

    /**
     * Rate linked to external inflation index.
     * Adjustment based on CPI or other economic indicators
     */
    INFLATION_LINKED,

    /**
     * Tiered increases based on contract duration or milestone.
     * Example: 2% after 1 year, 5% after 2 years
     */
    STEP_BASED,

    /**
     * One-time fixed adjustment to rates.
     * Example: One-time 5% increase effective specific date
     */
    FIXED_ADJUSTMENT
}
