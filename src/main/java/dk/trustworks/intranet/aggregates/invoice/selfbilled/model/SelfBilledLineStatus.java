package dk.trustworks.intranet.aggregates.invoice.selfbilled.model;

/** Voucher-level resolution state stamped onto each captured line. */
public enum SelfBilledLineStatus {
    /** Voucher parsed and its code mapped to a consultant. */
    RESOLVED,
    /** Voucher parsed, but the code is not in selfbilled_code_map. */
    UNMAPPED_CODE,
    /** Voucher has no parseable line at all (a pure-correction orphan). */
    UNPARSEABLE
}
