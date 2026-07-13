package dk.trustworks.intranet.aggregates.bonus.individual.model;

/** Detailed lifecycle for monthly payouts; {@link PayoutStatus} remains the legacy compatibility status. */
public enum PayoutLifecycleStatus {
    PROJECTED,
    NO_PAYMENT,
    COMMITTED,
    BLOCKED,
    ADJUSTMENT_REQUIRED,
    ADJUSTMENT_COMMITTED,
    MANUAL_DEDUCTION_REQUIRED,
    MANUALLY_SETTLED,
    SUPERSEDED
}
