package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Lifecycle status of a {@code RecruitmentCandidate}.
 * <p>
 * A candidate is born {@link #ACTIVE}. The ATS talent pool (plan §P3) adds
 * the non-terminal {@link #POOLED} state — a candidate can move
 * ACTIVE ⇄ POOLED any number of times before ending in one of the terminal
 * states ({@link #HIRED}, {@link #DECLINED}, {@link #WITHDRAWN}, and — from
 * P19 — {@link #ANONYMIZED}). Terminal candidates are immutable from the
 * domain's perspective.
 * <p>
 * {@code DECLINED} keeps its original name for wire/DB backward
 * compatibility (the ATS spec calls the concept REJECTED — spec §4.1).
 * <p>
 * Persisted as {@code VARCHAR(20)} via {@code @Enumerated(EnumType.STRING)};
 * the database also enforces the value set with {@code chk_rc_status_enum}.
 */
public enum CandidateStatus {
    ACTIVE,
    /** In the talent pool — not in an active process, not terminal. */
    POOLED,
    HIRED,
    DECLINED,
    WITHDRAWN,
    /** PII irreversibly rewritten by the P19 anonymizer. Terminal. */
    ANONYMIZED
}
