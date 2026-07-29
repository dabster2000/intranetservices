package dk.trustworks.intranet.expenseservice.services;

/**
 * Blast-radius limit for the nightly expense sync's DELETED marking. Deletions
 * are deferred during the run (collected as candidates) and only applied at the
 * end when the total stays within both the absolute cap and the
 * percentage-of-selected cap. A run that exceeds either cap applies NO deletions
 * at all — "0 deleted + 1 alert" — so a repeat of the 2026-07-28 incident (224
 * false DELETEs after the accountant's year-end journal reshuffle) damages
 * nothing and forces a human review instead.
 */
class DeletionCircuitBreaker {

    private final int absoluteThreshold;   // <= 0 disables the absolute cap
    private final double percentThreshold; // <= 0 disables the percentage cap
    private final int totalSelected;

    DeletionCircuitBreaker(int absoluteThreshold, double percentThreshold, int totalSelected) {
        this.absoluteThreshold = absoluteThreshold;
        this.percentThreshold = percentThreshold;
        this.totalSelected = totalSelected;
    }

    /** True when marking {@code count} expenses DELETED would exceed either cap. */
    boolean exceedsCap(int count) {
        boolean exceedsAbsolute = absoluteThreshold > 0 && count > absoluteThreshold;
        // Percentage cap is floored at 1 so a tiny run can still process a single
        // legitimate deletion instead of being blocked entirely.
        boolean exceedsPercent = percentThreshold > 0 && totalSelected > 0
                && count > Math.max(1.0, totalSelected * (percentThreshold / 100.0));
        return exceedsAbsolute || exceedsPercent;
    }
}
