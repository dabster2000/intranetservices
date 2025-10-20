package dk.trustworks.intranet.contracts.model.enums;

/**
 * Frequency at which rate adjustments are applied.
 * Determines how often the adjustment repeats after the effective date.
 */
public enum AdjustmentFrequency {
    /**
     * Applied once per year on the anniversary of the effective date.
     */
    YEARLY,

    /**
     * Applied once per quarter (every 3 months).
     */
    QUARTERLY,

    /**
     * Applied once per month.
     */
    MONTHLY,

    /**
     * Applied only once on the effective date, does not repeat.
     */
    ONE_TIME
}
