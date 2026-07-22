package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Where a talent-pool candidate stands (spec §4.1). Only meaningful while
 * the candidate's outer status is {@link CandidateStatus#POOLED}; cleared
 * on unpool.
 * <p>
 * {@code SILVER_MEDALIST} is set by the P4 return-to-pool flow (a strong
 * candidate who lost to another hire); the other values describe manual
 * pool curation.
 * <p>
 * Persisted as {@code VARCHAR(20)}; DB guard {@code chk_rc_pool_status_enum}.
 */
public enum CandidatePoolStatus {
    PROSPECT,
    CONTACTED,
    INTERESTED,
    NOT_NOW,
    SILVER_MEDALIST
}
