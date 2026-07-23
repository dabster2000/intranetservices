package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Why an automatically triggered candidate email required recruiter review
 * instead of sending directly (ATS plan §P15).
 */
public enum RecruitmentPendingEmailReason {
    /** The matched template is configured {@code auto_send = false}. */
    REVIEW_FIRST_TEMPLATE,
    /**
     * The candidate is a partner referral — partner-referral rejections
     * never auto-send, regardless of the template's {@code auto_send}
     * setting (plan §P15 DoD, server-enforced in the reactor).
     */
    PARTNER_REFERRAL
}
