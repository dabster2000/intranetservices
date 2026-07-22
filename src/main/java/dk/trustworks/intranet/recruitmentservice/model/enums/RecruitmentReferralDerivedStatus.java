package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * The milestone-level status a referrer sees on "My referrals" (ATS plan
 * §P6, aligned with P12's notification milestones). Computed server-side
 * on every read from the referral row + the candidate/application state —
 * never persisted, never mirrored onto {@code recruitment_referrals}.
 * <p>
 * Deliberately coarse: the referrer learns the milestone, never stage
 * codes, position names or a handle to the candidate record.
 */
public enum RecruitmentReferralDerivedStatus {

    /** Referral submitted, no recruiter has triaged it yet. */
    AWAITING_TRIAGE,

    /** Candidate created at triage; not yet on any position's pipeline. */
    UNDER_REVIEW,

    /** An open application sits in SCREENING. */
    IN_SCREENING,

    /** An open application sits in an interview round. */
    INTERVIEWING,

    /** An open application reached OFFER. */
    OFFER,

    /** The candidate rests in the talent pool. */
    IN_TALENT_POOL,

    /** The candidate was hired. */
    HIRED,

    /** Every application ended without a hire. */
    NOT_PROCEEDING,

    /** Dismissed at triage, or the candidate record no longer exists. */
    CLOSED
}
