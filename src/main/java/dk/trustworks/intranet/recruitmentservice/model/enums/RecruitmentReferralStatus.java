package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Lifecycle of a referral row (ATS plan §P6). Deliberately coarse: the
 * referral records what happened <em>at triage</em>; the candidate's later
 * pipeline journey is never mirrored back onto this row — "My referrals"
 * derives its milestone status live from candidate/application state.
 * <p>
 * Persisted as strings in {@code recruitment_referrals.status} — never
 * rename a value once rows exist.
 */
public enum RecruitmentReferralStatus {

    /** Awaiting recruiter triage — the only state triage accepts. */
    SUBMITTED,

    /** Triage created a candidate; no position was attached at triage. */
    TRIAGED,

    /** Triage created a candidate AND attached an application immediately. */
    CONVERTED,

    /** Dismissed at triage ({@code closed_reason} set); no candidate exists. */
    CLOSED
}
