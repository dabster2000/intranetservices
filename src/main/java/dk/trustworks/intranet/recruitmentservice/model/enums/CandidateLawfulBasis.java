package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * GDPR lawful basis for processing this candidate's data (spec §4.1).
 * {@code LEGITIMATE_INTEREST} is set at ATS create; {@code CONSENT} is set
 * by the P19 consent flows (talent-pool retention beyond the 6-month
 * legitimate-interest window requires explicit consent).
 * <p>
 * System-maintained — never accepted from API clients.
 * Persisted as {@code VARCHAR(30)}; DB guard {@code chk_rc_lawful_basis_enum}.
 */
public enum CandidateLawfulBasis {
    LEGITIMATE_INTEREST,
    CONSENT
}
