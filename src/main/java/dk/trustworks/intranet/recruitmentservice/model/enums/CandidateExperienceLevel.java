package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Seniority band for pool rediscovery filters (spec §4.1: "find a cleared
 * senior PM in the pool").
 * <p>
 * Persisted as {@code VARCHAR(20)}; DB guard {@code chk_rc_experience_enum}.
 */
public enum CandidateExperienceLevel {
    GRADUATE,
    JUNIOR,
    MID,
    SENIOR,
    PRINCIPAL
}
