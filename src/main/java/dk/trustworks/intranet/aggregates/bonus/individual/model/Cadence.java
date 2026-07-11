package dk.trustworks.intranet.aggregates.bonus.individual.model;

/**
 * Payout cadence of an individual bonus rule.
 */
public enum Cadence {
    /** One payout, {@code yearly.payMonthOffsetFromFyEnd} months after FY end. */
    YEARLY,
    /** A payout every employed month; no year-end true-up. */
    MONTHLY,
    /** Monthly on-account advances plus a year-end true-up = FY earned − Σ advances. */
    MONTHLY_ADVANCE_PLUS_YEARLY_TRUEUP
}
