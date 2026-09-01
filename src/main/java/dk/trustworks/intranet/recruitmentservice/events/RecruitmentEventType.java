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
    /**
     * An unsolicited application arrived through the public form
     * ({@code PublicApplyService.submitUnsolicited}). That path deliberately
     * creates no application row (recruiter triage attaches one later), so
     * {@code APPLICATION_CREATED} never fires — and before this type existed
     * the candidate mailer had no fact to hang a receipt on: the applicant
     * heard nothing. Emitted for NEW and REUSED candidates alike — the
     * submission is the fact being recorded, not the candidate row's birth
     * ({@code CANDIDATE_CREATED} only fires for new rows). Payload:
     * {@code origin=public_form}, {@code candidate_reused}; the CV's own
     * {@code DOCUMENT_UPLOADED} carries the file facts. Catalog addition per
     * spec §6.2 ("every mutating endpoint = one command = ≥1 event") and the
     * trigger for the {@code UNSOLICITED_ACKNOWLEDGEMENT} template
     * (candidate-email remediation F6, 2026-08-22).
     */
    UNSOLICITED_APPLICATION_RECEIVED,
    /**
     * A returning candidate submitted the public position form while already
     * holding an open application. The submission stores their documents but
     * deliberately creates no second application
     * ({@code PublicApplyService.submitForPosition}'s duplicate branch — the
     * one-open-application invariant), so again no {@code APPLICATION_CREATED}
     * and, before this type, no receipt for someone who had now applied
     * twice. Payload: {@code origin=public_form},
     * {@code reason=DUPLICATE_PUBLIC_SUBMISSION}; position subject set. The
     * trigger for the {@code DUPLICATE_APPLICATION_NOTICE} template
     * (candidate-email remediation F7, 2026-08-22).
     */
    DUPLICATE_APPLICATION_RECEIVED,

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
    /**
     * The candidate's OWN Outlook invitation (the Phase 6 two-event
     * split's candidate event) was actually created — or re-issued after
     * a reschedule ({@code payload.invite_kind = CREATED | UPDATED}).
     * Appended by the scheduling command when Graph answers inline, or by
     * {@code RecruitmentCalendarRepairJob} when a transient Graph failure
     * was retried to success. Until this type existed the timeline's only
     * calendar fact was {@code INTERVIEW_SCHEDULED.payload.calendar_synced},
     * which tracks the INTERNAL event — the 2026-08-24 Graph 504 left a
     * candidate uninvited under a timeline that said "synced". Payload:
     * {@code interview_uuid}, {@code invite_kind}, {@code scheduled_at};
     * structural only (the candidate's address lives on their row).
     */
    INTERVIEW_CANDIDATE_INVITE_SENT,
    /**
     * The candidate's Outlook invitation could NOT be delivered and
     * automation has given up: a permanent (non-retryable) Graph error, the
     * retry cap, or the interview time passing with the invite still
     * missing. Appended in the same moment HR is alerted on Slack — a
     * terminal fact a recruiter must resolve by hand, never a WARN in a
     * log nobody reads. Payload: {@code interview_uuid}, {@code reason},
     * {@code attempts}, {@code graph_request_id} (Graph's correlation id,
     * when one was returned); structural only.
     */
    INTERVIEW_CANDIDATE_INVITE_FAILED,
    SCORECARD_SUBMITTED,
    SCORECARD_NUDGED,
    /**
     * The prompt sweep DMed an assigned interviewer shortly after their
     * round actually ended (start + booked duration), while the impression
     * is still fresh. One event per (interviewer, interview) — the sweep's
     * idempotency key. Distinct from {@link #SCORECARD_NUDGED} only in
     * timing and tone: it is the FIRST ask, not a chase, and it is the
     * reason the 24 h chase usually never has to fire. It counts toward the
     * same {@code recruitment.sla.max-scorecard-nudges} cap, so adding it
     * cannot raise the total pressure on one interviewer for one interview.
     */
    SCORECARD_PROMPTED,
    /**
     * The owner recorded a pending go/no-go for one interview round
     * (pipeline sub-status feature, V519) — the state the board renders
     * as "Inform candidate" until the stage move or terminal that
     * completes it. Payload: {@code decision} (ADVANCE | REJECT),
     * {@code previous_decision} (re-records overwrite), {@code stage} —
     * all structural, no pii. Catalog addition per spec §6.2 ("every
     * mutating endpoint = one command = ≥1 event"): recording a decision
     * ahead of informing the candidate was previously not an act the
     * system could represent at all.
     */
    INTERVIEW_DECISION_RECORDED,
    /**
     * A pending interview decision was explicitly withdrawn (the undo
     * path — NOT the normal completion: the consuming stage move clears
     * the columns as part of its own event). Payload:
     * {@code previous_decision}, {@code stage}; structural only.
     */
    INTERVIEW_DECISION_CLEARED,

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
     * The eve brief batchlet DMed an interviewer their preparation pack the
     * afternoon before the interview — the same facts as
     * {@link #MORNING_BRIEF_SENT} but sent while there is still a working
     * day left to read the CV, skim the answers and move something. One
     * event per (interviewer, interview, interview date); the two briefs
     * key independently, so an interviewer briefed on the eve still gets
     * the short day-of logistics line.
     */
    EVE_BRIEF_SENT,
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
    /**
     * The author corrected their own discussion note (change request
     * 2026-08-22). Payload: {@code edited_event_id} (the {@code NOTE_ADDED}
     * event this supersedes — structural); pii: {@code text} (the new
     * text). Visibility is copied from the original note so a private note
     * stays private. The timeline read path folds the newest edit into the
     * displayed note (with an "edited" marker) and hides the edit events
     * themselves; the original text stays in the stream as audit history.
     */
    NOTE_EDITED,
    /**
     * A fact-bearing {@code NOTE_ADDED} was withdrawn (change request
     * 2026-08-28). Payload, structural only: {@code redacted_event_id} (the
     * note this withdraws), {@code field}, {@code origin}
     * ({@code interview_room} | {@code candidate_profile}) and
     * {@code interview_uuid} when it came from a room. No pii section — the
     * withdrawn VALUE is not restated here.
     * <p>
     * It exists because the ledger is append-only and a fact the AI sweep
     * recorded unprompted (2026-08-27) could otherwise only ever be
     * superseded, never taken back: writing a corrected value works when
     * the model read the wrong NUMBER, and does nothing when it read a fact
     * that was never said at all. Redaction is the second case.
     * <p>
     * The original event is NOT deleted or emptied. It stays in the stream
     * with its pii intact for the audit trail; every read path — the fact
     * ledger, the fact-state projection and the timeline — folds the
     * redaction in and stops both counting and showing it. That asymmetry
     * is the point: what a hiring team sees changes, what happened does not.
     */
    FACT_REDACTED,
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
    /**
     * HR opened the offer dossier for a candidate from the profile's Offer
     * &amp; Contract tab — the manual step {@code OFFER_OPENED}'s
     * {@code dossier_linked:false} has always implied but that had no
     * endpoint until now. Payload is structural only:
     * {@code template_uuid}, {@code dossier_uuid}, {@code reopened} (true
     * when a CLOSED dossier on the same template was reactivated rather
     * than a row inserted) and, when the candidate has an open application,
     * {@code application_uuid} + {@code stage}. Never salary, names or
     * email — those belong in {@code pii}, and this command has none.
     * <p>
     * Catalog addition made by the create-offer-dossier command: spec §6.2's
     * rule is "every mutating endpoint = one command = ≥1 event", and until
     * this type existed the birth of a contract dossier was the one
     * offer-flow act the timeline could not show.
     */
    DOSSIER_CREATED,
    /**
     * HR swapped the template of an OPEN, never-sent dossier — the
     * misclick escape hatch (the create picker has no default, and a
     * wrong pick used to lock the candidate onto the wrong contract:
     * {@code DOSSIER_EXISTS} blocks a second dossier and reopen only
     * matches the SAME template). Refused once the first revision
     * exists. Payload is structural only: {@code dossier_uuid},
     * {@code old_template_uuid}, {@code template_uuid} and, when the
     * candidate has an open application, {@code application_uuid}; the
     * PARTNER-track CIRCLE pairing matches {@code DOSSIER_CREATED}.
     * Catalog addition per spec §6.2 ("every mutating endpoint = one
     * command = ≥1 event").
     */
    DOSSIER_TEMPLATE_CHANGED,
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
     * The candidate deadline passed without an answer — options released,
     * request terminal EXPIRED (plan §11.3's expiry sweep). Catalog
     * addition beyond the plan §8.6 list: spec §15's Expired state is a
     * distinct ending ("never answered") and the timeline must say so.
     */
    SCHEDULING_EXPIRED,
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
    /**
     * One availability submission was interpreted and stored as an
     * evidence row (plan §12.2/§12.3) — text now, images in Phase 13.
     * The interviewer's message text lives in {@code pii} ONLY (plan
     * §12.2: "event pii blocks and the extraction call, nowhere else");
     * payload carries the skeleton: evidence uuid, source type, the
     * allowlisted intent, constraint count, lowest confidence, whether
     * confirmation is required. UNKNOWN/REJECTED submissions are
     * recorded too — they are the Phase 14 manual-review feed.
     */
    AVAILABILITY_EVIDENCE_RECEIVED,
    /**
     * Evidence became scheduling input (plan §12.4): the interviewer
     * pressed Bekræft, or the extraction marked the statement
     * unambiguous ({@code requiresConfirmation=false} auto-confirm —
     * payload {@code auto} distinguishes the two). Any older
     * overlapping evidence from the same interviewer it superseded is
     * listed in payload.
     */
    AVAILABILITY_EVIDENCE_CONFIRMED,
    /**
     * Evidence left the pipeline unconsumed: Ret pressed (payload
     * {@code reason=CORRECTION_REQUESTED}), 48 h unanswered or past its
     * covered period ({@code reason=EXPIRED}) — the D9 rule's negative
     * space, so the timeline shows WHY an interval never influenced
     * planning.
     */
    AVAILABILITY_EVIDENCE_CANCELLED,
    /**
     * One evidence image was deleted from S3 (Phase 13, D10): confirm,
     * cancel and the 48 h timeout all shed the original — this event is
     * the deletion's audit anchor, paired with the retained
     * {@code file_sha256} that proves WHAT was sent without keeping
     * content. Payload carries evidence uuid + the trigger status.
     */
    AVAILABILITY_IMAGE_DELETED,

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
    AI_ASSISTANT_EXCHANGE,
    /**
     * One AI-authored clarifying question in the Method B availability
     * loop (plan §12.4, D6): the ONLY place model prose reaches an
     * interviewer, always prefixed "🤖 " on the Slack surface. The
     * question AND the source message text live in {@code pii}; payload
     * carries intent, evidence uuid and prompt version — the spot-review
     * log mirroring AI_ASSISTANT_EXCHANGE.
     */
    AI_SCHEDULING_EXCHANGE,

    /**
     * The Interview Room's Tidy pass ran over an interviewer's draft
     * (room spec 2026-08-26 §3.4/§9). STRUCTURAL ONLY: line counts in and
     * out, model, latency — never the prose, which reaches the store only
     * via {@code SCORECARD_SUBMITTED.pii} when the interviewer lands.
     * Exists so the AI Act logging obligation is satisfied by the event
     * store rather than by application logs.
     */
    AI_NOTES_TIDIED
}
