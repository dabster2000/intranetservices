package dk.trustworks.intranet.aggregates.crm.signal.model.enums;

/**
 * Where a signal stands in the owner's queue (CRM spec §3.4).
 *
 * <p>Every signal is created {@link #NEW}. The three decisions are specified but not
 * built in this cut — this capture-only release saves the row and stops there — so no
 * code transitions a signal out of {@code NEW} yet. The values exist so the decide
 * surface can be added without a schema change.
 */
public enum SignalStatus {

    /** Captured, waiting for the account owner. The only status this cut writes. */
    NEW,

    /** The owner turned it into a lead; {@code leadUuid} then points at it. */
    LEAD_CREATED,

    /** Real, but nothing to do yet. */
    PARKED,

    /** Not worth acting on. */
    NOT_RELEVANT
}
