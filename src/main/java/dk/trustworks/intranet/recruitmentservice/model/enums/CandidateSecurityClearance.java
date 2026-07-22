package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Security-clearance status — Cyber Security practice relevance (spec §4.1).
 * The companion boolean {@code security_relevant} records whether the
 * candidate is open to clearance-required work at all.
 * <p>
 * Persisted as {@code VARCHAR(10)}; DB guard {@code chk_rc_clearance_enum}.
 */
public enum CandidateSecurityClearance {
    NONE,
    PENDING,
    CLEARED
}
