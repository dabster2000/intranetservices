package dk.trustworks.intranet.aggregates.invoice.model.enums;

/** Outcome of attempting to derive attribution for a single phantom. */
public enum PhantomDerivationStatus {
    ATTRIBUTED,        // AUTO rows written, sum to the phantom total
    UNRESOLVED_CLIENT, // no confirmed client mapping -> review queue
    EXCLUDED,          // label mapped excluded=1 (e.g. canteen) -> skipped, not in queue
    NO_WORK,           // resolved, but no registered work for client+month -> review queue
    SKIPPED_MANUAL,    // MANUAL override present -> left untouched (amount recalculated only)
    /** SELFBILLED_ASSIGNMENT rows present -> left fully untouched (AC10 skip-guard). */
    SKIPPED_SELFBILLED,
    OUT_OF_SCOPE       // not a CREATED PHANTOM in the current FY, or skip-flagged
}
