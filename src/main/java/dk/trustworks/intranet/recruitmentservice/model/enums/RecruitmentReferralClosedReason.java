package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Why a referral was dismissed at triage — coded for reporting, mandatory
 * on the DISMISS leg (ATS plan §P6). Free-text elaboration, if ever needed,
 * belongs in event {@code pii} blocks, never on the row.
 * <p>
 * Persisted as strings in {@code recruitment_referrals.closed_reason} —
 * append new codes, never rename persisted ones.
 */
public enum RecruitmentReferralClosedReason {

    /** The person is already a candidate (or employee) in the system. */
    DUPLICATE,

    /** Profile does not match any current or foreseeable need. */
    NOT_RELEVANT,

    /** Anything else. */
    OTHER
}
