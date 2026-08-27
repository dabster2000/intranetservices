package dk.trustworks.intranet.recruitmentservice.events;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the event catalog against accidental drift: names are persisted
 * verbatim in a VARCHAR(64) column and later phases reference them — a
 * rename or deletion after events exist would orphan history.
 */
class RecruitmentEventTypeCatalogTest {

    @Test
    void catalog_containsExactlyTheSpecTypes() {
        // APPLICATION_UPDATED is a deliberate P4 addition to the spec §3.4
        // catalog: structural application edits that are neither stage moves
        // nor terminals (expected start date) needed an event type and the
        // spec had none ("every mutating endpoint = one command = ≥1 event",
        // spec §6.2). Recorded in findings §P4.
        // REFERRAL_TRIAGED is the matching P6 addition: the triage decision
        // (create-candidate or dismiss) is a command with no spec §3.4 type
        // of its own. Recorded in findings §P6.
        // CANDIDATE_IDLE_NUDGED and DEBRIEF_STALLED_NUDGED are the P17
        // additions: the spec §3.4 catalog covered only SCORECARD_NUDGED,
        // but the idle and debrief pings are reactor side effects too and
        // "reactors' own side effects are recorded as events" (spec §3.4).
        // Recorded in findings §P17.
        // CONSENT_EXPIRED is the P19 addition: the consent lifecycle has an
        // EXPIRED status (spec §4.1) but the §3.4 catalog had no type for
        // the sweep's expiry bookkeeping. Recorded in findings §P19.
        // MORNING_BRIEF_SENT is the P23 addition: the morning-brief
        // batchlet's DMs are scheduled side effects and the sweep's
        // idempotency key ("reactors' own side effects are recorded as
        // events", spec §3.4). Recorded in findings §P23.
        // SCORECARD_PROMPTED and EVE_BRIEF_SENT are the V531 additions: the
        // end-of-meeting scorecard ask and the working-day-before prep brief
        // are scheduled side effects and their sweeps' idempotency keys, the
        // same rule that added SCORECARD_NUDGED and MORNING_BRIEF_SENT.
        // SCORECARD_PROMPTED deliberately shares the SCORECARD_NUDGED budget
        // (recruitment.sla.max-scorecard-nudges) so the pair count per
        // interviewer per interview did not double when it shipped.
        // DPO_DIGEST_SENT is the matching P24 addition: the DPO exception
        // digest's DMs are scheduled side effects and the run's per-
        // (recipient, week) idempotency key. Recorded in findings §P24.
        // AI_ASSISTANT_EXCHANGE is the P25 addition: every @Recruiting-
        // assistant exchange is logged for spot review (question in pii,
        // answer skeleton in payload). Recorded in findings §P25.
        // DOCUMENT_KIND_CHANGED is the document-type classification
        // addition: a recruiter manually re-typing a stored document is a
        // mutation like any other ("every mutating endpoint = one command
        // = ≥1 event", spec §6.2), and the Documents tab resolves a file's
        // kind from the newest such event.
        // APPLICATION_POSITION_CHANGED is the move-position addition: a
        // recruiter who filed a candidate against the wrong req previously
        // had no way to correct it (attaching again left BOTH applications
        // open), and the re-filing command is a mutation like any other
        // ("every mutating endpoint = one command = >=1 event", spec §6.2).
        // Deliberately not a terminal — the application never left a
        // pipeline, it changed which pipeline it is in.
        // RECORD_CHECK_DRAWN and RECORD_CHECK_OUTCOME_RECORDED are the
        // V481 ISAE 3000 sampling additions: the deterministic draw on
        // first OFFER entry and the HR outcome verification are auditable
        // actions ("every mutating endpoint = one command = ≥1 event",
        // spec §6.2); together with recruitment_record_checks they form
        // the ISAE audit trail. The attest itself is never stored.
        // The SCHEDULING_* / SLOT_* / HOLD_* / OPTION* block is the Method
        // B addition (plan 2026-08-12 §8.6): the candidate-option
        // scheduling lifecycle. Beyond the plan's own list,
        // SCHEDULING_REQUEST_UPDATED (extend-window/replace-interviewer),
        // SLOT_REJECTED (system rejection has no human decline event),
        // SCHEDULING_OPTIONS_APPROVED (the D11 review action) and
        // SCHEDULING_REMINDER_SENT (sweep side effects are events) follow
        // the same catalog-addition rules as the paragraphs above.
        // DOSSIER_CREATED is the create-offer-dossier addition: opening the
        // contract dossier for an existing candidate is a mutating command
        // ("every mutating endpoint = one command = ≥1 event", spec §6.2)
        // and was the one offer-flow act the timeline could not show —
        // OFFER_OPENED only ever reported whether a dossier already existed.
        // INTERVIEW_DECISION_RECORDED / INTERVIEW_DECISION_CLEARED are the
        // pipeline sub-status additions (V519): the opt-in pending go/no-go
        // on an interview round — the state the board shows as "Inform
        // candidate" — and its explicit undo. Both are mutating commands
        // per spec §6.2; the consuming stage move clears the pending state
        // under its own APPLICATION_* event, deliberately without a second
        // CLEARED event.
        // NOTE_EDITED is the editable-comments addition (change request
        // 2026-08-22): the author's correction of their own discussion
        // note — a mutating command per spec §6.2, folded into the
        // displayed note by the timeline read path while the original
        // text stays in the stream as audit history.
        // INTERVIEW_CANDIDATE_INVITE_SENT / _FAILED are the V533
        // candidate-invite robustness additions (production 2026-08-24: a
        // Graph 504 dropped a candidate's only Outlook invitation with
        // nothing but a WARN): the moment the candidate's own calendar
        // invitation actually went out (or was re-issued), and the terminal
        // moment automation gave up and HR was alerted. Both are side
        // effects of scheduling commands or the calendar repair sweep —
        // events like every other reactor/sweep side effect.
        // UNSOLICITED_APPLICATION_RECEIVED / DUPLICATE_APPLICATION_RECEIVED
        // are the candidate-email remediation additions (F6/F7, 2026-08-22):
        // the two public-form paths that deliberately create no application —
        // unsolicited submissions and repeat submissions onto an open
        // process — previously produced no event at all, so the candidate
        // mailer had nothing to acknowledge and the applicant heard nothing.
        Set<String> expected = Set.of(
                "CANDIDATE_CREATED", "CANDIDATE_UPDATED", "CANDIDATE_POOLED", "CANDIDATE_UNPOOLED",
                "CANDIDATE_MERGED",
                "APPLICATION_CREATED", "APPLICATION_UPDATED", "APPLICATION_STAGE_CHANGED",
                "APPLICATION_POSITION_CHANGED",
                "APPLICATION_REJECTED", "APPLICATION_WITHDRAWN",
                "UNSOLICITED_APPLICATION_RECEIVED", "DUPLICATE_APPLICATION_RECEIVED",
                "REFERRAL_SUBMITTED", "REFERRAL_TRIAGED", "REFERRAL_OUTCOME_NOTIFIED",
                "INTERVIEW_SCHEDULED", "INTERVIEW_RESCHEDULED", "INTERVIEW_CANCELLED",
                "INTERVIEW_CANDIDATE_INVITE_SENT", "INTERVIEW_CANDIDATE_INVITE_FAILED",
                "INTERVIEW_DECISION_RECORDED", "INTERVIEW_DECISION_CLEARED",
                "SCORECARD_SUBMITTED", "SCORECARD_NUDGED", "SCORECARD_PROMPTED",
                "CANDIDATE_IDLE_NUDGED", "DEBRIEF_STALLED_NUDGED", "MORNING_BRIEF_SENT",
                "EVE_BRIEF_SENT",
                "DPO_DIGEST_SENT",
                "EMAIL_SENT", "NOTE_ADDED", "NOTE_EDITED", "DOCUMENT_UPLOADED", "DOCUMENT_KIND_CHANGED",
                "OFFER_OPENED", "DOSSIER_CREATED", "SIGNING_COMPLETED", "CANDIDATE_HIRED",
                "TEAM_ASSIGNED",
                "RECORD_CHECK_DRAWN", "RECORD_CHECK_OUTCOME_RECORDED",
                "CONSENT_REQUESTED", "CONSENT_GRANTED", "CONSENT_WITHDRAWN", "CONSENT_EXPIRED",
                "ART14_NOTICE_SENT", "DSAR_RECEIVED", "DSAR_EXPORTED", "CANDIDATE_ANONYMIZED",
                "POSITION_OPENED", "POSITION_UPDATED", "POSITION_CLOSED",
                "CIRCLE_MEMBER_ADDED", "CIRCLE_MEMBER_REMOVED",
                "AI_SUGGESTIONS_GENERATED", "AI_SUGGESTION_RESOLVED", "AI_BRIEF_GENERATED",
                "AI_EMAIL_DRAFT_GENERATED", "AI_DIGEST_GENERATED", "AI_ASSISTANT_EXCHANGE",
                "SCHEDULING_REQUEST_CREATED", "SCHEDULING_REQUEST_UPDATED",
                "SLOT_PROPOSED", "SLOT_APPROVED", "SLOT_DECLINED", "SLOT_REJECTED",
                "HOLD_CREATED", "HOLD_RELEASED", "HOLD_MISSING",
                "SCHEDULING_OPTIONS_APPROVED", "OPTIONS_SENT", "OPTION_SELECTED",
                "SCHEDULING_FINALIZED", "SCHEDULING_HANDED_BACK", "SCHEDULING_CANCELLED",
                "SCHEDULING_EXPIRED", "SCHEDULING_REMINDER_SENT", "SCHEDULING_NOTE_ROUTED",
                "AVAILABILITY_EVIDENCE_RECEIVED", "AVAILABILITY_EVIDENCE_CONFIRMED",
                "AVAILABILITY_EVIDENCE_CANCELLED", "AVAILABILITY_IMAGE_DELETED",
                "AI_SCHEDULING_EXCHANGE",
                "AI_NOTES_TIDIED");

        Set<String> actual = Set.of(RecruitmentEventType.values()).stream()
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertEquals(expected, actual,
                "event catalog must match spec §3.4 + the P4 APPLICATION_UPDATED, "
                        + "P6 REFERRAL_TRIAGED, P17 *_NUDGED, P19 CONSENT_EXPIRED, "
                        + "P23 MORNING_BRIEF_SENT, P24 DPO_DIGEST_SENT, "
                        + "V531 SCORECARD_PROMPTED/EVE_BRIEF_SENT, "
                        + "P25 AI_ASSISTANT_EXCHANGE, DOCUMENT_KIND_CHANGED, "
                        + "V481 RECORD_CHECK_*, APPLICATION_POSITION_CHANGED "
                        + "and the Method B SCHEDULING_*/SLOT_*/HOLD_*/OPTION*/"
                        + "AVAILABILITY_EVIDENCE_* additions, DOSSIER_CREATED "
                        + "and the V519 INTERVIEW_DECISION_* pair exactly, plus the "
                        + "Interview Room's AI_NOTES_TIDIED (room spec 2026-08-26 \u00a73.4: "
                        + "the structural Tidy log the AI Act obligation reads)");
        assertEquals(86, RecruitmentEventType.values().length);
    }

    @Test
    void aiTypes_fiveUpfrontPlusTheP25Exchange() {
        long aiTypes = java.util.Arrays.stream(RecruitmentEventType.values())
                .filter(t -> t.name().startsWith("AI_"))
                .count();
        // The five AI_* types exist since P1 (plan §P1 scope);
        // AI_ASSISTANT_EXCHANGE is the P25 spot-review log (findings §P25);
        // AI_SCHEDULING_EXCHANGE is Method B Phase 12's clarifying-question
        // log (plan 2026-08-12 §12.4, D6); AI_NOTES_TIDIED is the Interview
        // Room's structural Tidy log (room spec 2026-08-26 §3.4).
        assertEquals(8, aiTypes,
                "five P1 AI_* types + the P25/Method B exchanges + AI_NOTES_TIDIED");
    }

    @Test
    void everyName_fitsTheVarchar64Column() {
        for (RecruitmentEventType type : RecruitmentEventType.values()) {
            assertTrue(type.name().length() <= 64,
                    type.name() + " exceeds the event_type VARCHAR(64) column");
        }
    }
}
