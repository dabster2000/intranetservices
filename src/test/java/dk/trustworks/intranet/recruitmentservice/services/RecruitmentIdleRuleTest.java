package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "My tasks" noise fix (2026-08-22): a candidate is a task only when
 * nothing has moved <em>and</em> nothing is queued to move.
 *
 * <p>Each case is one of the production rows that made the list untrustworthy
 * — a booked round two, a round waiting on a colleague's scorecard, a
 * candidate listed twice — plus the two ways the rule could over-correct
 * (muting a genuinely stuck candidate, or silencing itself with its own
 * nudges). Pure: no Quarkus, no DB, so it runs in the fast tier that gates
 * deploys.
 */
class RecruitmentIdleRuleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 22, 12, 0);
    /** The production threshold: recruitment.sla.candidate-idle-days = 4. */
    private static final LocalDateTime CUTOFF = NOW.minusDays(4);

    private static final LocalDateTime LONG_STILL = NOW.minusDays(11);

    // ---- the baseline: nothing moved, nothing queued ----------------------

    @Test
    void nothingMovedAndNothingQueued_isATask() {
        assertNull(RecruitmentIdleRule.suppressedBecause(stuck(), CUTOFF),
                "Victor Scheike: 11 days in Screening, no interview, no draft — a real task");
        assertTrue(RecruitmentIdleRule.isIdleTask(stuck(), CUTOFF));
    }

    @Test
    void progressInsideTheThreshold_isNotATask() {
        RecruitmentIdleRule.Facts moving = facts(b -> b.lastProgressAt = NOW.minusDays(3));

        assertEquals(RecruitmentIdleRule.Suppression.STILL_MOVING,
                RecruitmentIdleRule.suppressedBecause(moving, CUTOFF));
    }

    @Test
    void exactlyAtTheCutoff_isNotYetIdle() {
        RecruitmentIdleRule.Facts onTheLine = facts(b -> b.lastProgressAt = CUTOFF);

        assertEquals(RecruitmentIdleRule.Suppression.STILL_MOVING,
                RecruitmentIdleRule.suppressedBecause(onTheLine, CUTOFF),
                "the threshold must be exclusive — a row appearing a millisecond "
                        + "early is the flicker that makes people stop reading the list");
    }

    @Test
    void nullFacts_areNeverATask() {
        assertFalse(RecruitmentIdleRule.isIdleTask(null, CUTOFF),
                "fail closed: a missing fact row must not invent work");
    }

    // ---- the ball is somewhere else --------------------------------------

    @Test
    void bookedNextStep_isNotATask() {
        RecruitmentIdleRule.Facts booked = facts(b -> b.futureInterviewBooked = true);

        assertEquals(RecruitmentIdleRule.Suppression.NEXT_STEP_BOOKED,
                RecruitmentIdleRule.suppressedBecause(booked, CUTOFF),
                "Alexander Wichmann: round 2 in the calendar for 26 Aug, "
                        + "so 'move them along' asks for something already done");
    }

    @Test
    void schedulingAutomationInFlight_isNotATask() {
        RecruitmentIdleRule.Facts scheduling = facts(b -> b.schedulingInFlight = true);

        assertEquals(RecruitmentIdleRule.Suppression.SCHEDULING_IN_FLIGHT,
                RecruitmentIdleRule.suppressedBecause(scheduling, CUTOFF));
    }

    @Test
    void waitingOnAColleaguesScorecard_isNotTheOwnersTask() {
        RecruitmentIdleRule.Facts awaiting = facts(b -> b.awaitingScorecards = true);

        assertEquals(RecruitmentIdleRule.Suppression.AWAITING_SCORECARDS,
                RecruitmentIdleRule.suppressedBecause(awaiting, CUTOFF),
                "Rasmus Helmer-Villadsen: 1 of 2 cards in — the blocker is a named "
                        + "colleague who already carries the OVERDUE_SCORECARD row");
    }

    @Test
    void debriefReady_belongsToTheDecisionRowNotTheIdleRow() {
        RecruitmentIdleRule.Facts ready = facts(b -> b.debriefReady = true);

        assertEquals(RecruitmentIdleRule.Suppression.DEBRIEF_READY,
                RecruitmentIdleRule.suppressedBecause(ready, CUTOFF),
                "Kim Petersen appeared twice: 'Decide on Kim Petersen (2 days)' and "
                        + "'Kim Petersen is waiting in Interview 2 (8 days)' — two ages, "
                        + "one candidate, read as two problems");
    }

    @Test
    void queuedEmail_belongsToTheReviewQueueRow() {
        RecruitmentIdleRule.Facts queued = facts(b -> b.emailAwaitingReview = true);

        assertEquals(RecruitmentIdleRule.Suppression.EMAIL_AWAITING_REVIEW,
                RecruitmentIdleRule.suppressedBecause(queued, CUTOFF));
    }

    @Test
    void pausedOrClosedRequisition_isNotACandidateTask() {
        RecruitmentIdleRule.Facts onHold = facts(b -> b.positionOpen = false);

        assertEquals(RecruitmentIdleRule.Suppression.POSITION_NOT_OPEN,
                RecruitmentIdleRule.suppressedBecause(onHold, CUTOFF),
                "chasing a candidate on a paused req asks the owner to reopen the req");
    }

    // ---- ordering: the answer is the most useful sentence, not the first --

    @Test
    void stillMovingOutranksEverySuppression() {
        RecruitmentIdleRule.Facts recentAndBooked = facts(b -> {
            b.lastProgressAt = NOW.minusHours(2);
            b.futureInterviewBooked = true;
            b.positionOpen = false;
        });

        assertEquals(RecruitmentIdleRule.Suppression.STILL_MOVING,
                RecruitmentIdleRule.suppressedBecause(recentAndBooked, CUTOFF),
                "the clock is checked first: a moving candidate is never 'suppressed'");
    }

    @Test
    void aMissingScorecardOutranksASiblingCompleteRound() {
        RecruitmentIdleRule.Facts both = facts(b -> {
            b.awaitingScorecards = true;
            b.debriefReady = true;
        });

        assertEquals(RecruitmentIdleRule.Suppression.AWAITING_SCORECARDS,
                RecruitmentIdleRule.suppressedBecause(both, CUTOFF),
                "the outstanding card is the live blocker, so it is the honest answer");
    }

    // ---- the clock --------------------------------------------------------

    @Test
    void theClockIsTheLaterOfStageEntryAndRealProgress() {
        LocalDateTime stage = NOW.minusDays(11);
        LocalDateTime rescheduled = NOW.minusDays(2);

        assertEquals(rescheduled, RecruitmentIdleRule.lastProgressAt(stage, rescheduled),
                "an interview rescheduled two days ago is not eleven days of silence");
        assertEquals(stage, RecruitmentIdleRule.lastProgressAt(stage, NOW.minusDays(30)),
                "an Airtable import writes stage_entered_at without a matching event — "
                        + "preferring the older timestamp would resurrect settled rows");
        assertEquals(stage, RecruitmentIdleRule.lastProgressAt(stage, null));
        assertEquals(rescheduled, RecruitmentIdleRule.lastProgressAt(null, rescheduled));
        assertNull(RecruitmentIdleRule.lastProgressAt(null, null));
    }

    @Test
    void theSystemsOwnNudgesAreNotProgress() {
        for (RecruitmentEventType nagging : List.of(
                RecruitmentEventType.CANDIDATE_IDLE_NUDGED,
                RecruitmentEventType.SCORECARD_NUDGED,
                RecruitmentEventType.DEBRIEF_STALLED_NUDGED,
                RecruitmentEventType.MORNING_BRIEF_SENT)) {
            assertFalse(RecruitmentIdleRule.PROGRESS_EVENTS.contains(nagging),
                    nagging + " must not reset the clock it is complaining about — "
                            + "a nag that counts as progress fires exactly once, ever");
        }
    }

    @Test
    void internalWorkIsNotProgressForTheCandidate() {
        for (RecruitmentEventType internal : List.of(
                RecruitmentEventType.NOTE_ADDED,
                RecruitmentEventType.NOTE_EDITED,
                RecruitmentEventType.DOCUMENT_UPLOADED,
                RecruitmentEventType.CANDIDATE_UPDATED,
                RecruitmentEventType.AI_BRIEF_GENERATED,
                RecruitmentEventType.AI_SUGGESTIONS_GENERATED)) {
            assertFalse(RecruitmentIdleRule.PROGRESS_EVENTS.contains(internal),
                    internal + " is invisible to the candidate, who is the one "
                            + "deciding whether to keep waiting");
        }
    }

    @Test
    void handingWorkBackIsNotProgress() {
        for (RecruitmentEventType handback : List.of(
                RecruitmentEventType.INTERVIEW_CANCELLED,
                RecruitmentEventType.SCHEDULING_HANDED_BACK,
                RecruitmentEventType.SCHEDULING_EXPIRED,
                RecruitmentEventType.SCHEDULING_CANCELLED)) {
            assertFalse(RecruitmentIdleRule.PROGRESS_EVENTS.contains(handback),
                    handback + " is the moment a human is needed again — counting it "
                            + "as progress would mute exactly the wrong row");
        }
    }

    @Test
    void realMovementIsProgress() {
        for (RecruitmentEventType moved : List.of(
                RecruitmentEventType.APPLICATION_STAGE_CHANGED,
                RecruitmentEventType.APPLICATION_POSITION_CHANGED,
                RecruitmentEventType.INTERVIEW_SCHEDULED,
                RecruitmentEventType.INTERVIEW_RESCHEDULED,
                RecruitmentEventType.SCORECARD_SUBMITTED,
                RecruitmentEventType.INTERVIEW_DECISION_RECORDED,
                RecruitmentEventType.OFFER_OPENED,
                RecruitmentEventType.OPTIONS_SENT,
                RecruitmentEventType.OPTION_SELECTED)) {
            assertTrue(RecruitmentIdleRule.PROGRESS_EVENTS.contains(moved),
                    moved + " moves the application and must restart the clock");
        }
    }

    @Test
    void sendingAnEmailIsNotByItselfProgress() {
        assertFalse(RecruitmentIdleRule.PROGRESS_EVENTS.contains(RecruitmentEventType.EMAIL_SENT),
                "every email that carries progress already has a structural twin "
                        + "(invitation -> INTERVIEW_SCHEDULED, options -> OPTIONS_SENT, "
                        + "stage mail -> APPLICATION_STAGE_CHANGED). What is left is "
                        + "housekeeping: on 2026-08-14 a single CONSENT_RENEWAL batch "
                        + "would have reset the clock on four candidates at once, and an "
                        + "automated ACKNOWLEDGEMENT made eleven untouched days read as three");
    }

    @Test
    void gdprBookkeepingIsNotProgress() {
        for (RecruitmentEventType consent : List.of(
                RecruitmentEventType.CONSENT_REQUESTED,
                RecruitmentEventType.CONSENT_GRANTED,
                RecruitmentEventType.CONSENT_EXPIRED)) {
            assertFalse(RecruitmentIdleRule.PROGRESS_EVENTS.contains(consent),
                    consent + " runs on the GDPR clock, not the hiring one");
        }
    }

    // ---- fixtures ---------------------------------------------------------

    /** Nothing moved for eleven days, nothing queued, requisition open. */
    private static RecruitmentIdleRule.Facts stuck() {
        return facts(b -> {
        });
    }

    private static RecruitmentIdleRule.Facts facts(java.util.function.Consumer<Builder> tweak) {
        Builder b = new Builder();
        tweak.accept(b);
        return new RecruitmentIdleRule.Facts(b.positionOpen, b.futureInterviewBooked,
                b.schedulingInFlight, b.awaitingScorecards, b.debriefReady,
                b.emailAwaitingReview, b.lastProgressAt);
    }

    private static final class Builder {
        boolean positionOpen = true;
        boolean futureInterviewBooked;
        boolean schedulingInFlight;
        boolean awaitingScorecards;
        boolean debriefReady;
        boolean emailAwaitingReview;
        LocalDateTime lastProgressAt = LONG_STILL;
    }
}
