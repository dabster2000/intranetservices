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
    /**
     * The application was re-filed onto another position — the SAME
     * pipeline run continues under a new req, so interviews, scorecards,
     * record checks and the timeline all follow it. Payload:
     * {@code from_position_uuid} / {@code to_position_uuid} (+ their
     * titles and hiring tracks), {@code from_stage} / {@code to_stage} and
     * {@code stage_clamped} — all structural, no pii.
     * <p>
     * Catalog addition made for the move-position command: a recruiter who
     * attached a candidate to the wrong position previously had no way to
     * correct it (attaching again left BOTH applications open), and spec
     * §6.2's rule is "every mutating endpoint = one command = ≥1 event".
     * Deliberately NOT a terminal: the application never left a pipeline,
     * it changed which pipeline it is in.
     */
    APPLICATION_POSITION_CHANGED,
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

    // --- SLA automation (P17) --------------------------------------------
    /**
     * The SLA sweep pinged the position's owner about an open application
     * idle in its stage beyond the configured threshold. Catalog addition
     * made in P17 — the spec §3.4 catalog covered only the scorecard nudge,
     * but every reactor side effect is an event (findings §P17).
     */
    CANDIDATE_IDLE_NUDGED,
    /**
     * The SLA sweep pinged the decision owner about a debrief-ready round
     * (all scorecards in) left unactioned beyond the configured threshold.
     * Catalog addition made in P17 (findings §P17).
     */
    DEBRIEF_STALLED_NUDGED,
    /**
     * The morning-brief batchlet DMed an interviewer about one of today's
     * scheduled interviews (Slack spec §5.8). One event per
     * (interviewer, interview, brief date) — the sweep's idempotency key;
     * a re-run only briefs pairs with no event for today. Catalog addition
     * made in P23 — a scheduled side effect is an event like every other
     * reactor side effect (findings §P23).
     */
    MORNING_BRIEF_SENT,
    /**
     * The digest batchlet DMed a DPO-role holder the weekly exception
     * digest (Slack spec §5.10). One event per (recipient, ISO week) —
     * the run's idempotency key; a re-run only DMs recipients with no
     * event for the week. {@code candidate_uuid} NULL; payload carries
     * structural counts only. Catalog addition made in P24 — a scheduled
     * side effect is an event like every other reactor side effect
     * (findings §P24).
     */
    DPO_DIGEST_SENT,

    // --- Communication & notes (P3, P15) --------------------------------
    EMAIL_SENT,
    NOTE_ADDED,
    DOCUMENT_UPLOADED,
    /**
     * A recruiter manually re-typed a stored candidate document whose
     * kind the system could not classify (P8 Documents tab). Payload:
     * {@code file_uuid}, {@code kind} (the new kind), {@code previous_kind};
     * all structural — no pii section. The Documents tab resolves a
     * file's kind as: newest {@code DOCUMENT_KIND_CHANGED} &gt; the
     * upload event's kind &gt; flow-derived (dossier snapshots /
     * appendices / onboarding submissions) &gt; OTHER. Catalog addition
     * made by the document-type classification feature — a manual
     * correction is an auditable action like every other mutation.
     */
    DOCUMENT_KIND_CHANGED,

    // --- Offer bridge to the existing dossier module (P10) ---------------
    OFFER_OPENED,
    SIGNING_COMPLETED,
    CANDIDATE_HIRED,
    TEAM_ASSIGNED,

    // --- ISAE 3000 record-check sampling (V481) --------------------------
    /**
     * The deterministic criminal-record draw performed on a candidate's
     * FIRST entry into OFFER. Payload: {@code selected},
     * {@code rate_applied}, {@code check_uuid} — all structural. One per
     * candidate ever (the draw row's uniqueness is the idempotency key);
     * together with {@code recruitment_record_checks} this is the audit
     * trail ISAE auditors read.
     */
    RECORD_CHECK_DRAWN,
    /**
     * HR recorded what they saw when a selected candidate presented
     * their straffeattest. Payload: {@code outcome}, {@code check_uuid}.
     * The attest itself is never stored anywhere (data minimization).
     */
    RECORD_CHECK_OUTCOME_RECORDED,

    // --- GDPR (P4 capture, P19 engine) ----------------------------------
    CONSENT_REQUESTED,
    CONSENT_GRANTED,
    CONSENT_WITHDRAWN,
    /**
     * A GRANTED consent ran past its {@code expires_at} and the nightly
     * GDPR sweep flipped it to EXPIRED. Catalog addition made in P19 —
     * the spec §4.1 consent lifecycle has an EXPIRED status but the
     * spec §3.4 catalog had no type for the sweep's expiry bookkeeping,
     * and "reactors' own side effects are recorded as events"
     * (findings §P19).
     */
    CONSENT_EXPIRED,
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

    // --- Method B candidate-option scheduling (plan 2026-08-12 §8.6) -----
    /** A recruiter started Method B on an application. Payload:
     * structural request parameters (kind, round, window, option count). */
    SCHEDULING_REQUEST_CREATED,
    /**
     * A recruiter changed a live request — extend-window or
     * replace-interviewer. Payload: what changed, old/new values, all
     * structural. Catalog addition beyond the plan §8.6 list — spec
     * §6.2's rule is "every mutating endpoint = one command = ≥1 event"
     * and the plan's REST section defines both endpoints.
     */
    SCHEDULING_REQUEST_UPDATED,
    /** A slot went out to the required interviewers. Payload: slot uuid,
     * option_no, start/end, room (structural; no candidate reference
     * needed beyond the event's own subject columns). */
    SLOT_PROPOSED,
    /** One required interviewer approved a slot (Slack button). */
    SLOT_APPROVED,
    /** One required interviewer declined a slot (Slack button). Free-text
     * reasons, when they exist, live in {@code pii} only. */
    SLOT_DECLINED,
    /**
     * The system rejected a slot: recheck conflict, hold failure
     * (compensation, spec §20) or a lost hold. Payload:
     * {@code reject_reason}. Catalog addition beyond the plan §8.6 list —
     * plan §8.2's rule is one audit event per transition, and this
     * transition has no human actor to emit SLOT_DECLINED for.
     */
    SLOT_REJECTED,
    /** One attendee-less hold event landed in a calendar (D5). Payload:
     * hold uuid, owner kind, mailbox kind — never the candidate name
     * (D12). */
    HOLD_CREATED,
    /** A hold's Graph event was deleted (release, expiry, compensation,
     * finalization cleanup). */
    HOLD_RELEASED,
    /** The reconciliation sweep found a hold's event gone (the owner
     * deleted it by hand) — the slot gets re-evaluated. */
    HOLD_MISSING,
    /**
     * The recruiter approved the secured options for sending (the D11
     * review action), possibly releasing some first. Payload: kept and
     * released slot uuids. Catalog addition beyond the plan §8.6 list —
     * the review gate is its own mutating endpoint (plan §8.5).
     */
    SCHEDULING_OPTIONS_APPROVED,
    /** The option batch went out to the candidate (Phase 11). */
    OPTIONS_SENT,
    /** The candidate picked an option on the public page (Phase 11). */
    OPTION_SELECTED,
    /** Finalization completed — the real interview exists; its own
     * INTERVIEW_SCHEDULED event carries the interview facts. */
    SCHEDULING_FINALIZED,
    /** Automation gave up and handed the request to the recruiter.
     * Payload: reason + structural attempt history (spec §19.4 — no
     * private evidence content). */
    SCHEDULING_HANDED_BACK,
    /** The recruiter cancelled the request. */
    SCHEDULING_CANCELLED,
    /**
     * The sweep nudged a silent interviewer or escalated to the
     * recruiter (defaults §29.16). One event per nudge. Catalog addition
     * beyond the plan §8.6 list — "reactors' own side effects are
     * recorded as events", the same rule that added SCORECARD_NUDGED.
     */
    SCHEDULING_REMINDER_SENT,
    /**
     * An interviewer routed a plain note to the recruiter from a
     * proposal card ("Foreslå anden tid" / "Spørg rekruttereren" —
     * plan §9.1's pre-NLU path). The note text lives in {@code pii}
     * only; payload carries the structural {@code note_kind}. Catalog
     * addition beyond the plan §8.6 list — an auditor asking "what did
     * the interviewer suggest, and when" reads it here (spec §3.4's
     * timeline rule).
     */
    SCHEDULING_NOTE_ROUTED,

    // --- AI assist (companion spec; P9 onward) ---------------------------
    AI_SUGGESTIONS_GENERATED,
    AI_SUGGESTION_RESOLVED,
    AI_BRIEF_GENERATED,
    AI_EMAIL_DRAFT_GENERATED,
    AI_DIGEST_GENERATED,
    /**
     * One @Recruiting-assistant exchange (Slack spec §5.11): a user
     * mentioned the bot and the assistant replied. The spot-review log —
     * the question text lives in {@code pii} (it can name a person), the
     * answer SKELETON (intent, outcome, which fact kinds were included —
     * never the reply prose) in {@code payload}. Candidate subject set
     * only on single-match answered exchanges, so the GDPR anonymizer
     * scrubs the question with the rest of that candidate's pii. Catalog
     * addition made in P25 — "reactors' own side effects are recorded as
     * events" applied to the conversational surface (findings §P25).
     */
    AI_ASSISTANT_EXCHANGE
}
