package dk.trustworks.intranet.aggregates.bonus.individual.model;

/**
 * The nature of a projected/materialised payout.
 */
public enum PayoutKind {
    ADVANCE,
    MONTHLY,
    YEARLY,
    TRUEUP,
    FINAL_SETTLEMENT
}
