package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Lifecycle status of a {@code RecruitmentCandidate}.
 * <p>
 * A candidate is born {@link #ACTIVE} and transitions exactly once into one of
 * the three terminal states ({@link #HIRED}, {@link #DECLINED}, {@link #WITHDRAWN}).
 * Terminal candidates are immutable from the domain's perspective.
 * <p>
 * Persisted as {@code VARCHAR(20)} via {@code @Enumerated(EnumType.STRING)};
 * the database also enforces the value set with {@code chk_rc_status_enum}.
 */
public enum CandidateStatus {
    ACTIVE,
    HIRED,
    DECLINED,
    WITHDRAWN
}
