package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Lifecycle of a consent record (spec §4.1). P4 creates {@code REQUESTED}
 * rows (return-to-pool → silver medalist needs pool-retention consent);
 * granting/withdrawing/expiring is the P19 consent page + GdprClock.
 * Until P19 the recruiter/DPO handles pool consent manually — a documented
 * plan §P4 limitation.
 * <p>
 * Persisted as {@code VARCHAR(20)}; DB guard {@code chk_rcon_status_enum}.
 */
public enum RecruitmentConsentStatus {
    REQUESTED,
    GRANTED,
    WITHDRAWN,
    EXPIRED
}
