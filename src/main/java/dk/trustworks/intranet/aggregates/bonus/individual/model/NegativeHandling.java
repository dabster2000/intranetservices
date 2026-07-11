package dk.trustworks.intranet.aggregates.bonus.individual.model;

/**
 * Policy for a negative year-end true-up (advances exceeded FY earned).
 * <p>
 * Decision D1 default is {@link #WRITE_OFF} — the true-up is clamped to 0 (already-paid advances
 * are never clawed back). {@link #CLAWBACK} would emit a negative line (pending confirmation that
 * Danløn løntype 41 accepts negatives).
 */
public enum NegativeHandling {
    /** Clamp a negative true-up to 0 — default (D1). */
    WRITE_OFF,
    /** Keep the negative amount as a deduction. */
    CLAWBACK
}
