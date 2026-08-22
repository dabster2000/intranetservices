package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * The "is this candidate actually waiting on a human right now?" rule behind
 * the {@code IDLE_CANDIDATE} task row, the landing pipelines' idle badge and
 * the nightly {@code CANDIDATE_IDLE_NUDGED} Slack DM — one definition, three
 * surfaces (the module's one-rule-in-one-place idiom, like
 * {@link RecruitmentInterviewService#allAssignedSubmitted}).
 *
 * <h3>Why the rule exists</h3>
 * P17 measured idleness as "{@code stage_entered_at} is older than the
 * threshold". That answers a much weaker question than a task list asks. A
 * stage is a coarse marker: the process can be moving briskly — round 2
 * booked for next Tuesday, scorecards still coming in, an email drafted and
 * queued for review — while the stage sits perfectly still. Every one of
 * those produced a row saying "move them along or close the application" for
 * a candidate nobody had to touch, and the noise devalued the rows that were
 * real. Measured on production 2026-08-22: 6 of 14 idle rows were candidates
 * whose next interview was already in the calendar, whose round was waiting
 * on a colleague's scorecard, or who were already listed one row higher as a
 * pending decision.
 *
 * <h3>The two halves</h3>
 * <ol>
 *   <li><b>A truthful clock.</b> Idleness runs from the last thing that
 *       actually <em>moved</em> the application ({@link #PROGRESS_EVENTS}),
 *       not from the last stage change. A rescheduled interview, a submitted
 *       scorecard or an email to the candidate all count; a note to self, a
 *       document upload, an AI brief and — critically — the system's own
 *       {@code *_NUDGED} bookkeeping do not. A nag that resets its own clock
 *       would never fire twice; a nag that ignores real work fires forever.</li>
 *   <li><b>A court check.</b> Even a genuinely still application is not a
 *       task when the ball is demonstrably somewhere else: booked on a
 *       calendar, out with the scheduling automation, waiting on a named
 *       colleague's scorecard, sitting in the review-before-send queue, on a
 *       paused requisition — or already owned by a sharper trigger (the
 *       debrief).</li>
 * </ol>
 *
 * <h3>What suppression is not</h3>
 * Suppressed candidates keep their card, their {@code daysInStage} and their
 * board position on {@code /recruitment/pipeline}, and keep counting toward
 * "Candidates in pipeline". They stop being <em>tasks</em> — a claim about
 * who must act next, not a claim about whether they exist. Every suppression
 * is also self-clearing: the day the interview is held, the scorecard lands
 * or the requisition reopens, the row comes back with a truthful age.
 *
 * <h3>Known gap: the offer phase</h3>
 * "The contract is out for signature, we are waiting on the candidate's pen"
 * is a textbook ball-elsewhere state, and it is <em>not</em> implemented —
 * because it cannot be, yet. {@code signing_cases} keys on {@code user_uuid}
 * with no candidate linkage, and {@code recruitment_signing_completed_cases}
 * maps case → candidate only <em>after</em> completion; measured 2026-08-22,
 * all 9 in-flight cases (1 {@code in_progress}, 8 {@code pending}) were
 * unlinkable. {@code candidate_dossiers.status} is only OPEN/CLOSED, so it
 * cannot stand in. A candidate in OFFER whose contract is out therefore still
 * appears as idle. Closing this needs a candidate reference on the signing
 * case (or a "signature sent" event on the stream) — not a guess here.
 *
 * <p>Deliberately free of CDI, entities and I/O: callers gather the facts in
 * their own batched queries ({@link RecruitmentIdleFacts}) and hand over a
 * {@link Facts} record, so the rule is exercised by plain unit tests in the
 * DB-free fast tier.
 */
public final class RecruitmentIdleRule {

    private RecruitmentIdleRule() {
    }

    /**
     * Events that count as forward movement on an application and therefore
     * restart the idle clock.
     *
     * <p>The exclusions carry as much intent as the inclusions:
     * <ul>
     *   <li>{@code NOTE_ADDED} / {@code NOTE_EDITED} / {@code DOCUMENT_UPLOADED}
     *       / {@code CANDIDATE_UPDATED} / {@code AI_*} — real work, but
     *       invisible to the candidate, who is the one deciding whether to
     *       keep waiting. "We wrote about you" is not "we moved you".</li>
     *   <li>{@code CONSENT_REQUESTED} / {@code CONSENT_GRANTED} — GDPR
     *       bookkeeping that runs on its own clock, unrelated to whether
     *       anyone is working the application.</li>
     *   <li>every {@code *_NUDGED} and digest {@code *_SENT} event — the
     *       system chasing about this row must never look like progress on
     *       it. Left in, the first nudge would silence all the rest.</li>
     *   <li>{@code INTERVIEW_CANCELLED}, {@code SCHEDULING_HANDED_BACK},
     *       {@code SCHEDULING_EXPIRED}, {@code SCHEDULING_CANCELLED} — these
     *       hand work <em>back</em>. Counting them as progress would mute the
     *       exact moment a human is needed again.</li>
     *   <li>{@code EMAIL_SENT} — the surprising one, and the reason this list
     *       is enumerated rather than inferred. Every email that <em>does</em>
     *       carry progress already has a structural twin here: an interview
     *       invitation rides with {@code INTERVIEW_SCHEDULED}, an options mail
     *       with {@code OPTIONS_SENT}, a stage mail with
     *       {@code APPLICATION_STAGE_CHANGED}. What is left is housekeeping —
     *       {@code ACKNOWLEDGEMENT} ("we received your application"),
     *       {@code CONSENT_RENEWAL}, {@code ART14_NOTICE},
     *       {@code DUPLICATE_APPLICATION_NOTICE} — none of which moves anyone
     *       forward. Including it bought nothing and cost a great deal: on
     *       2026-08-14 one GDPR consent-renewal batch would have reset the
     *       clock on four candidates at once, and a candidate eleven days
     *       untouched in Screening would have read as three days because an
     *       automated receipt went out. A rule that mass-mutes on a
     *       housekeeping batch is worse than no rule.</li>
     * </ul>
     */
    public static final Set<RecruitmentEventType> PROGRESS_EVENTS = EnumSet.of(
            RecruitmentEventType.APPLICATION_CREATED,
            RecruitmentEventType.APPLICATION_STAGE_CHANGED,
            RecruitmentEventType.APPLICATION_POSITION_CHANGED,
            RecruitmentEventType.INTERVIEW_SCHEDULED,
            RecruitmentEventType.INTERVIEW_RESCHEDULED,
            RecruitmentEventType.SCORECARD_SUBMITTED,
            RecruitmentEventType.INTERVIEW_DECISION_RECORDED,
            RecruitmentEventType.OFFER_OPENED,
            RecruitmentEventType.DOSSIER_CREATED,
            RecruitmentEventType.SIGNING_COMPLETED,
            RecruitmentEventType.RECORD_CHECK_DRAWN,
            RecruitmentEventType.SCHEDULING_REQUEST_CREATED,
            RecruitmentEventType.SLOT_PROPOSED,
            RecruitmentEventType.OPTIONS_SENT,
            RecruitmentEventType.OPTION_SELECTED,
            RecruitmentEventType.SCHEDULING_FINALIZED);

    /** Why an application that has not moved is still not somebody's task. */
    public enum Suppression {

        /** Progress is newer than the threshold — the ordinary case. */
        STILL_MOVING,

        /** The requisition is ON_HOLD or CLOSED — a req decision, not a candidate one. */
        POSITION_NOT_OPEN,

        /**
         * A non-cancelled interview of any kind (round, informal chat or the
         * offer meeting) sits in the future. The next step is on a calendar;
         * nobody has to invent it.
         */
        NEXT_STEP_BOOKED,

        /**
         * A Method B scheduling request is still live — the automation is
         * hunting slots, the interviewers are answering, or the options are
         * out with the candidate. Chasing here would race the robot.
         */
        SCHEDULING_IN_FLIGHT,

        /**
         * The live round has been held and a named assigned interviewer still
         * owes a scorecard. The blocker is a colleague who already carries the
         * {@code OVERDUE_SCORECARD} row (and the scorecard nudge) — telling
         * the owner to "move them along" asks for something they cannot do.
         */
        AWAITING_SCORECARDS,

        /**
         * Every assigned interviewer has answered on a live round: this is a
         * <em>decision</em>, owned by {@code PENDING_DECISION} on the page and
         * by the debrief-stalled nudge in Slack. One application never earns
         * two rows — the sharper one wins ("open the debrief" beats "move them
         * along", and it links somewhere useful).
         */
        DEBRIEF_READY,

        /**
         * The next contact is already written and waiting in the P15
         * review-before-send queue, which is its own aggregate row.
         */
        EMAIL_AWAITING_REVIEW
    }

    /**
     * Facts about one open application, gathered in batch by
     * {@link RecruitmentIdleFacts}. Deliberately viewer-independent: the same
     * record answers the page, the pipelines badge and the nightly sweep.
     *
     * @param positionOpen          the requisition's status is {@code OPEN}
     * @param futureInterviewBooked a non-cancelled interview of any kind is scheduled after now
     * @param schedulingInFlight    a Method B request exists in a non-terminal status
     * @param awaitingScorecards    a held, still-live round has an assigned interviewer who has not submitted
     * @param debriefReady          a held, still-live round has every assigned interviewer submitted
     * @param emailAwaitingReview   a PENDING row sits in the review-before-send queue
     * @param lastProgressAt        {@link #lastProgressAt}; null only when the application has no clock at all
     */
    public record Facts(
            boolean positionOpen,
            boolean futureInterviewBooked,
            boolean schedulingInFlight,
            boolean awaitingScorecards,
            boolean debriefReady,
            boolean emailAwaitingReview,
            LocalDateTime lastProgressAt) {
    }

    /**
     * The idle clock: the later of the stage entry and the newest
     * {@link #PROGRESS_EVENTS} row on the application.
     *
     * <p>Taking the max rather than simply preferring the event stream is
     * deliberate — the Airtable import writes {@code stage_entered_at} at
     * import time without a matching event, and an application moved before a
     * given event type existed may carry a stage newer than anything
     * recorded. Preferring the older of the two would resurrect long-settled
     * rows on the day this ships.
     */
    public static LocalDateTime lastProgressAt(LocalDateTime stageEnteredAt,
                                               LocalDateTime lastProgressEventAt) {
        if (stageEnteredAt == null) {
            return lastProgressEventAt;
        }
        if (lastProgressEventAt == null) {
            return stageEnteredAt;
        }
        return lastProgressEventAt.isAfter(stageEnteredAt) ? lastProgressEventAt : stageEnteredAt;
    }

    /**
     * Why this application should <em>not</em> be chased, or {@code null} when
     * it should.
     *
     * <p>Ordered most-informative first: the answer is the one sentence a
     * recruiter asking "why isn't X on my list?" would want back.
     *
     * @param cutoff {@code now − candidateIdleDays}; progress at or after it means still moving
     */
    public static Suppression suppressedBecause(Facts facts, LocalDateTime cutoff) {
        if (facts.lastProgressAt() == null || !facts.lastProgressAt().isBefore(cutoff)) {
            return Suppression.STILL_MOVING;
        }
        if (!facts.positionOpen()) {
            return Suppression.POSITION_NOT_OPEN;
        }
        if (facts.futureInterviewBooked()) {
            return Suppression.NEXT_STEP_BOOKED;
        }
        if (facts.schedulingInFlight()) {
            return Suppression.SCHEDULING_IN_FLIGHT;
        }
        if (facts.awaitingScorecards()) {
            return Suppression.AWAITING_SCORECARDS;
        }
        if (facts.debriefReady()) {
            return Suppression.DEBRIEF_READY;
        }
        if (facts.emailAwaitingReview()) {
            return Suppression.EMAIL_AWAITING_REVIEW;
        }
        return null;
    }

    /** Convenience twin of {@link #suppressedBecause}: true ⇒ this is a real task. */
    public static boolean isIdleTask(Facts facts, LocalDateTime cutoff) {
        return facts != null && suppressedBecause(facts, cutoff) == null;
    }
}
