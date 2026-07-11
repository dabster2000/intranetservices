package dk.trustworks.intranet.aggregates.bonus.individual.model;

/**
 * Whether a projected payout has been materialised into a salary_lump_sum yet.
 */
public enum PayoutStatus {
    /** A salary_lump_sum exists for this payout's sourceReference. */
    COMMITTED,
    /** No row exists yet — read-time projection only. */
    PROJECTED
}
