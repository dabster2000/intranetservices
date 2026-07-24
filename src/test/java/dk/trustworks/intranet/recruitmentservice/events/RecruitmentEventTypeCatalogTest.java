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
        // DPO_DIGEST_SENT is the matching P24 addition: the DPO exception
        // digest's DMs are scheduled side effects and the run's per-
        // (recipient, week) idempotency key. Recorded in findings §P24.
        // AI_ASSISTANT_EXCHANGE is the P25 addition: every @Recruiting-
        // assistant exchange is logged for spot review (question in pii,
        // answer skeleton in payload). Recorded in findings §P25.
        Set<String> expected = Set.of(
                "CANDIDATE_CREATED", "CANDIDATE_UPDATED", "CANDIDATE_POOLED", "CANDIDATE_UNPOOLED",
                "CANDIDATE_MERGED",
                "APPLICATION_CREATED", "APPLICATION_UPDATED", "APPLICATION_STAGE_CHANGED",
                "APPLICATION_REJECTED", "APPLICATION_WITHDRAWN",
                "REFERRAL_SUBMITTED", "REFERRAL_TRIAGED", "REFERRAL_OUTCOME_NOTIFIED",
                "INTERVIEW_SCHEDULED", "INTERVIEW_RESCHEDULED", "INTERVIEW_CANCELLED",
                "SCORECARD_SUBMITTED", "SCORECARD_NUDGED",
                "CANDIDATE_IDLE_NUDGED", "DEBRIEF_STALLED_NUDGED", "MORNING_BRIEF_SENT",
                "DPO_DIGEST_SENT",
                "EMAIL_SENT", "NOTE_ADDED", "DOCUMENT_UPLOADED",
                "OFFER_OPENED", "SIGNING_COMPLETED", "CANDIDATE_HIRED", "TEAM_ASSIGNED",
                "CONSENT_REQUESTED", "CONSENT_GRANTED", "CONSENT_WITHDRAWN", "CONSENT_EXPIRED",
                "ART14_NOTICE_SENT", "DSAR_RECEIVED", "DSAR_EXPORTED", "CANDIDATE_ANONYMIZED",
                "POSITION_OPENED", "POSITION_UPDATED", "POSITION_CLOSED",
                "CIRCLE_MEMBER_ADDED", "CIRCLE_MEMBER_REMOVED",
                "AI_SUGGESTIONS_GENERATED", "AI_SUGGESTION_RESOLVED", "AI_BRIEF_GENERATED",
                "AI_EMAIL_DRAFT_GENERATED", "AI_DIGEST_GENERATED", "AI_ASSISTANT_EXCHANGE");

        Set<String> actual = Set.of(RecruitmentEventType.values()).stream()
                .map(Enum::name)
                .collect(Collectors.toSet());
        assertEquals(expected, actual,
                "event catalog must match spec §3.4 + the P4 APPLICATION_UPDATED, "
                        + "P6 REFERRAL_TRIAGED, P17 *_NUDGED, P19 CONSENT_EXPIRED, "
                        + "P23 MORNING_BRIEF_SENT, P24 DPO_DIGEST_SENT and "
                        + "P25 AI_ASSISTANT_EXCHANGE additions exactly");
        assertEquals(48, RecruitmentEventType.values().length);
    }

    @Test
    void aiTypes_fiveUpfrontPlusTheP25Exchange() {
        long aiTypes = java.util.Arrays.stream(RecruitmentEventType.values())
                .filter(t -> t.name().startsWith("AI_"))
                .count();
        // The five AI_* types exist since P1 (plan §P1 scope);
        // AI_ASSISTANT_EXCHANGE is the P25 spot-review log (findings §P25).
        assertEquals(6, aiTypes, "five P1 AI_* types + the P25 AI_ASSISTANT_EXCHANGE");
    }

    @Test
    void everyName_fitsTheVarchar64Column() {
        for (RecruitmentEventType type : RecruitmentEventType.values()) {
            assertTrue(type.name().length() <= 64,
                    type.name() + " exceeds the event_type VARCHAR(64) column");
        }
    }
}
