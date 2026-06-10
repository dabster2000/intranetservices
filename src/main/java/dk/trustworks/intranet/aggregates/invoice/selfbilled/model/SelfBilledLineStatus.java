package dk.trustworks.intranet.aggregates.invoice.selfbilled.model;

/** Voucher-level workflow status stamped onto each captured line (V365 vocabulary). */
public enum SelfBilledLineStatus {
    /** Legacy parse-state values — removed in the capture-retarget task. */
    RESOLVED, UNMAPPED_CODE, UNPARSEABLE,
    /** Captured, no human decision yet. */
    UNASSIGNED,
    /** Human-assigned to >=1 cross-company (consultant, work-period); awaiting settle. */
    ASSIGNED,
    /** Needs no internal: issuer == debtor (computed from assignments, or sticky human mark). */
    SAME_COMPANY,
    /** Every cross-company group of the voucher's assignments has |delta| <= 1 kr. */
    SETTLED,
    /** Out of scope (net-zero voucher, or sticky human mark). Reversible until settled. */
    IGNORED
}
