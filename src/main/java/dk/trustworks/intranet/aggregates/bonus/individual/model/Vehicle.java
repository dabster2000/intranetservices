package dk.trustworks.intranet.aggregates.bonus.individual.model;

/**
 * The pay vehicle used to deliver a monthly / advance amount.
 */
public enum Vehicle {
    /** One salary_lump_sum per month (used when the amount can vary). */
    MONTHLY_LUMP_SUM,
    /** A single native SalarySupplement over fromMonth..toMonth (flat amounts). Phase 3. */
    PREPAID_SUPPLEMENT
}
