package dk.trustworks.intranet.agreementservice.model.enums;

/**
 * Lifecycle of a registry row (template-clauses spec §4.7). The nightly
 * expiry sweep flips ACTIVE → EXPIRED past {@code valid_to}; confirming a
 * new agreement can mark a prior one SUPERSEDED; TERMINATED is a manual
 * HR ending (the term stopped applying before its natural end).
 */
public enum AgreementStatus {
    ACTIVE,
    EXPIRED,
    SUPERSEDED,
    TERMINATED
}
