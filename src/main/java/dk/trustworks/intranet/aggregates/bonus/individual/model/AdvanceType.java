package dk.trustworks.intranet.aggregates.bonus.individual.model;

/**
 * How a monthly advance amount is derived.
 */
public enum AdvanceType {
    /** A flat gross DKK amount per month. */
    FIXED,
    /** A fraction (0..1) of the projected FY bonus, spread across employed months. */
    PERCENT_OF_PROJECTED
}
