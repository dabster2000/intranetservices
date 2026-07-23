package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * One-shot lifecycle of a review-before-send queue row (ATS plan §P15):
 * {@code PENDING} → {@code APPROVED} (mail row created + {@code EMAIL_SENT}
 * appended) or {@code PENDING} → {@code DISMISSED}. Resolved rows are
 * immutable. Persisted as {@code VARCHAR(12)}; DB guard {@code chk_rpe_status}.
 */
public enum RecruitmentPendingEmailStatus {
    PENDING,
    APPROVED,
    DISMISSED
}
