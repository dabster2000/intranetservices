package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Outcome of an ISAE 3000 criminal-record check draw. Persisted as
 * {@code VARCHAR(20)}; DB guard {@code chk_rrc_outcome_enum} (V481).
 * The attest itself is NEVER stored — outcome only (data minimization).
 */
public enum RecordCheckOutcome {
    /** Awaiting verification (or the candidate was not selected — then forever). */
    PENDING,
    /** HR saw a clean straffeattest. */
    VERIFIED_CLEAN,
    /** The presented straffeattest was not clean. */
    NOT_CLEAN
}
