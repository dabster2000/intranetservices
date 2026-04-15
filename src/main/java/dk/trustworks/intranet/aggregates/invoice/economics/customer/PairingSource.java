package dk.trustworks.intranet.aggregates.invoice.economics.customer;

/**
 * How a {@link ClientEconomicsCustomer} row was established.
 * SPEC-INV-001 §3.3.1, §5.2.
 */
public enum PairingSource {
    /** Auto-matched by CVR (high confidence). */
    AUTO_CVR,
    /** Auto-matched by normalised name (medium confidence — admin should verify). */
    AUTO_NAME,
    /** Manually paired by an admin via the pairing UI. */
    MANUAL,
    /** New e-conomic customer created by POST /Customers. */
    CREATED,
    /** No pairing yet — awaiting manual resolution. */
    UNMATCHED
}
