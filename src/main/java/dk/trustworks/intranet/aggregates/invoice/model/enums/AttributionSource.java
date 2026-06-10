package dk.trustworks.intranet.aggregates.invoice.model.enums;

public enum AttributionSource {
    AUTO,
    MANUAL,
    /** §6.3 mirror of a human self-billed assignment — the nightly estimator must never overwrite these. */
    SELFBILLED_ASSIGNMENT
}
