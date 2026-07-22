package dk.trustworks.intranet.recruitmentservice.events;

/**
 * The full recruitment event catalog (ATS expansion spec §3.4), defined
 * upfront in Phase 1 so later phases never need forward references. Types
 * are emitted incrementally: the first emitters arrive in P2 (positions),
 * the first {@code AI_*} emitters in P9.
 * <p>
 * Rule of thumb (spec §3.4): if a human would want to see it on the
 * timeline or an auditor would ask "who did that, when" — it is an event.
 * Reactors' own side effects are recorded as events too.
 * <p>
 * Enum names are persisted verbatim in {@code recruitment_events.event_type}
 * (VARCHAR(64)) — never rename a value once it has been emitted.
 */
public enum RecruitmentEventType {

    // --- Candidate lifecycle (P3) ---------------------------------------
    CANDIDATE_CREATED,
    CANDIDATE_UPDATED,
    CANDIDATE_POOLED,
    CANDIDATE_UNPOOLED,
    CANDIDATE_MERGED,

    // --- Applications (P4) ----------------------------------------------
    APPLICATION_CREATED,
    /**
     * Structural application edits that are neither stage moves nor
     * terminals (e.g. expected start date). Catalog addition made in P4 —
     * the spec §3.4 catalog had no type for "every mutating endpoint = one
     * command = ≥1 event" on plain application updates (findings §P4).
     */
    APPLICATION_UPDATED,
    APPLICATION_STAGE_CHANGED,
    APPLICATION_REJECTED,
    APPLICATION_WITHDRAWN,

    // --- Referrals (P6) -------------------------------------------------
    REFERRAL_SUBMITTED,
    /**
     * A recruiter triaged a referral — either into a candidate
     * ({@code payload.outcome=CANDIDATE_CREATED}, candidate subject set;
     * the candidate itself arrives via its own {@code CANDIDATE_CREATED})
     * or dismissed ({@code payload.outcome=DISMISSED} with
     * {@code payload.dismiss_reason}). Catalog addition made in P6 — the
     * spec §3.4 catalog had no type for the triage decision itself, and
     * spec §6.2's rule is "every mutating endpoint = one command = ≥1
     * event" (findings §P6).
     */
    REFERRAL_TRIAGED,
    REFERRAL_OUTCOME_NOTIFIED,

    // --- Interviews & scorecards (P11) ----------------------------------
    INTERVIEW_SCHEDULED,
    INTERVIEW_RESCHEDULED,
    INTERVIEW_CANCELLED,
    SCORECARD_SUBMITTED,
    SCORECARD_NUDGED,

    // --- Communication & notes (P3, P15) --------------------------------
    EMAIL_SENT,
    NOTE_ADDED,
    DOCUMENT_UPLOADED,

    // --- Offer bridge to the existing dossier module (P10) ---------------
    OFFER_OPENED,
    SIGNING_COMPLETED,
    CANDIDATE_HIRED,
    TEAM_ASSIGNED,

    // --- GDPR (P4 capture, P19 engine) ----------------------------------
    CONSENT_REQUESTED,
    CONSENT_GRANTED,
    CONSENT_WITHDRAWN,
    ART14_NOTICE_SENT,
    DSAR_RECEIVED,
    DSAR_EXPORTED,
    CANDIDATE_ANONYMIZED,

    // --- Positions & circles (P2) ---------------------------------------
    POSITION_OPENED,
    POSITION_UPDATED,
    POSITION_CLOSED,
    CIRCLE_MEMBER_ADDED,
    CIRCLE_MEMBER_REMOVED,

    // --- AI assist (companion spec; P9 onward) ---------------------------
    AI_SUGGESTIONS_GENERATED,
    AI_SUGGESTION_RESOLVED,
    AI_BRIEF_GENERATED,
    AI_EMAIL_DRAFT_GENERATED,
    AI_DIGEST_GENERATED
}
