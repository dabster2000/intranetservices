package dk.trustworks.intranet.agreementservice.model.enums;

/**
 * How a registry row entered the system (template-clauses spec §4.7).
 */
public enum AgreementSource {
    /** Written automatically when a NextSign case with clauses completed. */
    SIGNED_CASE,
    /** Confirmed from the AI backfill review queue (Phase 4). */
    BACKFILL,
    /** Entered by HR by hand for terms that never passed through signing. */
    MANUAL
}
