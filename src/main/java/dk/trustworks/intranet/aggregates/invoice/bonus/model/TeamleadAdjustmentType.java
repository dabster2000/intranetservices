package dk.trustworks.intranet.aggregates.invoice.bonus.model;

/**
 * Admin-entered adjustment kinds for the teamlead bonus (per leader, per fiscal year).
 *
 * <ul>
 *   <li>{@link #SPLIT_BONUS} — one-off amount added to the total (multiple rows allowed).</li>
 *   <li>{@link #PREPAID_DEDUCTION} — manual amount added to the auto-computed prepaid figure.</li>
 *   <li>{@link #UTIL_OVERRIDE} — decimal (0..1.5) replacing the computed team utilization
 *       (at most one per leader/FY — enforced in the service).</li>
 * </ul>
 */
public enum TeamleadAdjustmentType {
    SPLIT_BONUS,
    PREPAID_DEDUCTION,
    UTIL_OVERRIDE
}
