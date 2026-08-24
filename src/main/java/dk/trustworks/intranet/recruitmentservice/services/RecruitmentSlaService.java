package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCircleMember;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentCircleRole;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCandidateFacts;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.slack.SlackRecruitmentViews;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The P17 SLA sweep (plan §P17, spec §8.4 "the system chases, not the
 * recruiter"): a clock-driven pass over the live pipeline that DMs the
 * right person when something sits too long. Three triggers, thresholds
 * from {@code app_settings} ({@link RecruitmentSlaThresholds}, V447):
 * <ul>
 *   <li><b>Scorecard overdue</b> — a round interview's time has passed and
 *       an assigned interviewer's own scorecard is missing → DM to that
 *       interviewer ({@code SCORECARD_NUDGED}, hard cap of
 *       {@code recruitment.sla.max-scorecard-nudges} — default 2 — per
 *       interviewer per interview, never after submission).</li>
 *   <li><b>Debrief stalled</b> — every assigned interviewer has submitted
 *       (the shared {@code allAssignedSubmitted} rule) but the application
 *       has not moved past the round → DM to the decision owner
 *       ({@code DEBRIEF_STALLED_NUDGED}, re-pinged at most once per
 *       threshold period).</li>
 *   <li><b>Candidate idle</b> — an open application has made no progress
 *       beyond the threshold <em>and</em> nothing is queued to move it
 *       ({@link RecruitmentIdleRule}: not booked, not out with the
 *       scheduling automation, not waiting on a colleague's scorecard, not
 *       debrief-ready, not queued for email review, requisition still open)
 *       → DM to the decision owner ({@code CANDIDATE_IDLE_NUDGED}, re-pinged
 *       at most once per threshold period while still idle). The stage clock
 *       is only the pre-filter; the rule — shared with the landing page's
 *       task list so Slack and the page always agree — decides.</li>
 * </ul>
 * The plan calls this the "SlaReactor", but no event can announce that
 * time has passed — the component is a sweep service driven by the
 * nightly {@code recruitment-sla-sweep} batchlet, not a
 * {@code RecruitmentReactor} subclass (findings §P17). Idempotency is
 * event-derived (the P12 idiom, no new tables): every DM appends its own
 * {@code *_NUDGED} event, and the sweep counts/dates those events to
 * enforce caps and re-nudge spacing — so a re-run, a second instance
 * during ECS cutover, or a manual trigger never double-pings.
 *
 * <h3>Delivery discipline</h3>
 * Each nudge runs in its own transaction with the DM sent <em>before</em>
 * the bookkeeping event is appended (the {@code ReferrerNotificationReactor}
 * order): a Slack failure rolls back the event, so the next sweep retries;
 * a crash between send and commit yields at worst one duplicate DM. One
 * failing nudge never stops the sweep. Recipients without a Slack link are
 * a visible INFO skip with no event — the landing page's task list is the
 * degradation path (it computes the same conditions per viewer).
 *
 * <h3>Gating</h3>
 * All side effects sit behind {@code recruitment.interviews.enabled}
 * (spec §11 places SLA nudges with the interview loop). Off ⇒ the sweep
 * is a no-op; nothing is backfilled on later enable beyond what still
 * matches a trigger condition at that time.
 *
 * <h3>TWO CLOCKS — read this before touching any {@code now}</h3>
 * This module stores timestamps in two different domains, and every
 * comparison here must use the clock matching the column it reads:
 * <ul>
 *   <li><b>Copenhagen wall-clock</b> — {@code RecruitmentInterview.scheduledAt}
 *       and everything derived from it ({@link RecruitmentIdleFacts#endOf},
 *       {@link RecruitmentIdleFacts#booked}). Naive local time as the
 *       scheduler typed it.</li>
 *   <li><b>UTC</b> — every audit timestamp: {@code RecruitmentEvent.occurredAt}
 *       ({@code RecruitmentEventRecorder}), {@code RecruitmentScorecard.submittedAt},
 *       {@code RecruitmentApplication.stageEnteredAt}, and the
 *       {@code lastProgressAt} the idle rule derives from them.</li>
 * </ul>
 * Until 2026-08-24 all three sweeps read one UTC clock, which was right for
 * the audit columns and 1–2 hours wrong against {@code scheduledAt} — so a
 * "24 hours after the interview" nudge actually fired 22 or 23 hours after
 * it in summer. Swinging every read to Copenhagen instead would merely move
 * the error onto the audit columns. Neither single clock is correct, because
 * {@link #sweepOverdueScorecards} and {@link #sweepIdleCandidates} each
 * compare against BOTH domains — the latter feeds a Copenhagen clock to
 * {@link RecruitmentIdleFacts#load} and a UTC one to the idle cutoff in the
 * same pass. {@link #sweepStalledDebriefs} touches only audit columns and is
 * therefore pure UTC.
 *
 * <p>{@code RecruitmentLandingService} carries the same split and MUST feed
 * the shared idle loader the same clock this sweep does: that loader exists
 * so the "My tasks" row and the Slack nudge can never disagree about one
 * candidate, and a one-hour skew between them reintroduces exactly that.
 *
 * <p>The tolerance for getting this wrong is set by the tightest window in
 * the module: {@link #sweepScorecardPrompts()} fires 20 minutes after an
 * interview ends. A one-hour skew there does not shift a reminder, it DMs an
 * interviewer while they are still in the room with the candidate.
 *
 * <h3>Recipient resolution (owner ladder)</h3>
 * For the two owner pings: the position's {@code hiring_owner_uuid} when
 * set; else on partner track the circle {@code OWNER} members; else the
 * current leaders of the position's team; else nobody (INFO skip) —
 * deliberately narrow so a misconfigured position spams no one.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentSlaService {


    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentSlaThresholds thresholds;

    @Inject
    RecruitmentIdleFacts idleFacts;

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    SlackService slackService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    /** Result of one sweep, for logs and the batchlet exit status. */
    public record SweepSummary(boolean enabled, int scorecardNudges, int debriefNudges,
                               int idleNudges, int failures) {

        @Override
        public String toString() {
            if (!enabled) {
                return "sla-sweep[disabled]";
            }
            return "sla-sweep[scorecards=%d, debriefs=%d, idle=%d%s]"
                    .formatted(scorecardNudges, debriefNudges, idleNudges,
                            failures > 0 ? ", failures=" + failures : "");
        }
    }

    /**
     * Run one full sweep. Safe to call at any time and from several
     * instances concurrently — idempotency is event-derived (class
     * javadoc). Each nudge commits independently.
     */
    public SweepSummary sweep() {
        // Fresh transactions for every settings read: the sweep must see
        // the CURRENT flag/threshold values, never a session-cached row
        // (the RecruitmentFeatureFlag no-cache contract).
        if (!inTx(featureFlag::isInterviewsEnabled)) {
            log.debug("recruitment-sla-sweep skipped: recruitment.interviews.enabled=false");
            return new SweepSummary(false, 0, 0, 0, 0);
        }
        Counters counters = new Counters();
        sweepOverdueScorecards(counters);
        sweepStalledDebriefs(counters);
        sweepIdleCandidates(counters);
        return new SweepSummary(true, counters.scorecards, counters.debriefs,
                counters.idle, counters.failures);
    }

    /**
     * Copenhagen hours outside which the end-of-meeting prompt stays quiet.
     * The daily 07:00 UTC sweep never needed this because it only ran once,
     * in the morning; a 15-minute sweep would happily DM at 21:30.
     */
    /**
     * Every event type that counts as "we already asked this interviewer for
     * this scorecard". Both the end-of-meeting prompt and the overdue chase
     * append one, and they share the
     * {@code recruitment.sla.max-scorecard-nudges} budget — the whole reason
     * V531 could add a DM moment without raising the number of DMs anyone
     * receives.
     *
     * <p>This is a constant rather than two literals because the failure mode
     * of them drifting apart is nasty and silent: {@link #priorScorecardNudges}
     * reads from {@code Context.nudgeEvents}, so if a type is listed here but
     * NOT loaded by {@link Context#finish}, the lookup returns empty, the cap
     * never trips, and — because {@link #sweepScorecardPrompts()} suppresses
     * on {@code priorAsks.isEmpty()} — the prompt re-sends every 15 minutes
     * for a full day. {@code RecruitmentSlaScorecardAskTypesTest} pins the
     * two lists together.
     */
    static final List<RecruitmentEventType> SCORECARD_ASK_TYPES = List.of(
            RecruitmentEventType.SCORECARD_NUDGED,
            RecruitmentEventType.SCORECARD_PROMPTED);

    /** Every event type {@link Context} loads for nudge bookkeeping. */
    static final List<RecruitmentEventType> NUDGE_EVENT_TYPES = List.of(
            RecruitmentEventType.SCORECARD_NUDGED,
            RecruitmentEventType.SCORECARD_PROMPTED,
            RecruitmentEventType.DEBRIEF_STALLED_NUDGED,
            RecruitmentEventType.CANDIDATE_IDLE_NUDGED);

    private static final int QUIET_HOURS_END = 7;
    private static final int QUIET_HOURS_START = 20;

    private static final class Counters {
        int scorecards;
        int debriefs;
        int idle;
        int failures;
    }

    /** Result of one prompt pass, for logs and the batchlet exit status. */
    public record PromptSummary(boolean enabled, int prompts, int failures) {

        @Override
        public String toString() {
            if (!enabled) {
                return "scorecard-prompt[disabled]";
            }
            return "scorecard-prompt[prompts=%d%s]"
                    .formatted(prompts, failures > 0 ? ", failures=" + failures : "");
        }
    }

    /**
     * Ask for the scorecard shortly after the meeting actually ended, while
     * the impression is still intact — the first ask, not a chase.
     *
     * <p>Run on its own short cadence rather than inside {@link #sweep()},
     * which fires once a day at 07:00 UTC: a prompt that means "you just
     * finished" cannot ride a daily job. It shares that sweep's context
     * loader, delivery discipline and — critically — its nudge cap, so this
     * does not add pressure, it moves the first ask earlier
     * ({@link #priorScorecardNudges}).
     *
     * <h3>Why the window has two ends</h3>
     * The upper end is {@code recruitment.sla.scorecard-prompt-minutes} after
     * {@link RecruitmentIdleFacts#endOf} — end of meeting, never start, so a
     * round that runs over does not get its interviewer pinged in front of
     * the candidate. The lower end is the overdue threshold: past that the
     * daily chase owns the pair, and — the reason it exists at all — without
     * a lower bound the very first run after deploy would DM every
     * interviewer about every un-scorecarded interview in the history of the
     * table.
     *
     * <h3>Quiet hours</h3>
     * Nothing goes out before 07:00 or after 20:00 Copenhagen. A 19:00
     * interview would otherwise be prompted at 20:20; suppressing costs
     * nothing because the sweep re-derives from source and the pair is still
     * un-prompted when the 07:00 run comes round, comfortably inside the
     * 24 h window.
     */
    public PromptSummary sweepScorecardPrompts() {
        if (!inTx(featureFlag::isInterviewsEnabled)) {
            log.debug("recruitment-scorecard-prompt skipped: recruitment.interviews.enabled=false");
            return new PromptSummary(false, 0, 0);
        }
        LocalDateTime now = LocalDateTime.now(RecruitmentIdleFacts.COPENHAGEN);
        int hour = now.getHour();
        if (hour < QUIET_HOURS_END || hour >= QUIET_HOURS_START) {
            log.debugf("recruitment-scorecard-prompt skipped: %02d:00 is outside 07:00-20:00 "
                    + "Copenhagen (the next in-hours run picks these up)", hour);
            return new PromptSummary(true, 0, 0);
        }
        int promptMinutes = inTx(thresholds::scorecardPromptMinutes);
        int overdueHours = inTx(thresholds::scorecardOverdueHours);
        LocalDateTime endedBefore = now.minusMinutes(promptMinutes);
        LocalDateTime endedAfter = now.minusHours(overdueHours);
        if (!endedAfter.isBefore(endedBefore)) {
            // Misconfiguration (prompt delay >= overdue threshold) would leave
            // an empty window; the daily chase still covers the pair.
            log.warnf("recruitment-scorecard-prompt: empty window — prompt-minutes=%d is not "
                    + "inside overdue-hours=%d; nothing to do", promptMinutes, overdueHours);
            return new PromptSummary(true, 0, 0);
        }
        // endOf = scheduledAt + durationMinutes, so an interview whose END is
        // in [endedAfter, endedBefore] must have STARTED no earlier than one
        // booked day before that — a superset the exact filter then narrows.
        LocalDateTime windowStart = endedAfter.minusDays(1);
        List<RecruitmentInterview> candidates = inTx(() -> RecruitmentInterview.list(
                "kind = ?1 and status <> ?2 and scheduledAt is not null "
                        + "and scheduledAt >= ?3 and scheduledAt <= ?4",
                RecruitmentInterviewKind.ROUND, RecruitmentInterviewStatus.CANCELLED,
                windowStart, endedBefore));
        List<RecruitmentInterview> justEnded = candidates.stream()
                .filter(i -> {
                    LocalDateTime end = RecruitmentIdleFacts.endOf(i);
                    return !end.isBefore(endedAfter) && !end.isAfter(endedBefore);
                })
                .toList();
        if (justEnded.isEmpty()) {
            return new PromptSummary(true, 0, 0);
        }
        Context ctx = inTx(() -> Context.load(justEnded));
        boolean scorecardButtons = inTx(slackFlags::isScorecardEnabled);
        Counters counters = new Counters();

        for (RecruitmentInterview interview : justEnded) {
            RecruitmentApplication application = ctx.applications.get(interview.getApplicationUuid());
            if (application == null || !applicationInPlay(application)) {
                continue; // decision made — the scorecard no longer changes anything
            }
            RecruitmentPosition position = ctx.positions.get(application.getPositionUuid());
            RecruitmentCandidate candidate = ctx.candidates.get(application.getCandidateUuid());
            Set<String> submitted = ctx.scorecards
                    .getOrDefault(interview.getUuid(), List.of()).stream()
                    .map(RecruitmentScorecard::getInterviewerUuid)
                    .collect(Collectors.toSet());

            for (String interviewerUuid : interview.getInterviewerUuids()) {
                if (submitted.contains(interviewerUuid)) {
                    continue; // already in — never ask twice
                }
                List<LocalDateTime> priorAsks = priorScorecardNudges(
                        ctx, interview.getUuid(), interviewerUuid);
                if (!priorAsks.isEmpty()) {
                    continue; // prompted (or chased) already — this is the FIRST ask only
                }
                String message = scorecardPromptText(candidate, position, interview.getRound());
                List<com.slack.api.model.block.LayoutBlock> blocks = scorecardButtons
                        ? scorecardNudgeBlocks(message, interview.getUuid())
                        : null;
                boolean sent = nudge(counters, interviewerUuid, message, blocks, () ->
                        eventRecorder.record(RecruitmentEventBuilder
                                .event(RecruitmentEventType.SCORECARD_PROMPTED)
                                .candidate(application.getCandidateUuid())
                                .application(application.getUuid())
                                .position(application.getPositionUuid())
                                .actorScheduler()
                                .visibility(visibilityFor(position))
                                .payload("interview_uuid", interview.getUuid())
                                .payload("round", interview.getRound())
                                .payload("nudged_user_uuid", interviewerUuid)
                                .payload("nudge_number", 1)
                                .payload("prompt_minutes", promptMinutes)
                                .payload("ended_at", String.valueOf(
                                        RecruitmentIdleFacts.endOf(interview)))));
                if (sent) {
                    counters.scorecards++;
                }
            }
        }
        return new PromptSummary(true, counters.scorecards, counters.failures);
    }

    // ------------------------------------------------------------------
    // Trigger 1 — scorecard overdue
    // ------------------------------------------------------------------

    private void sweepOverdueScorecards(Counters counters) {
        int thresholdHours = inTx(thresholds::scorecardOverdueHours);
        // Two clocks, because this method compares against columns from two
        // different domains — see the class note on TWO CLOCKS. scheduledAt
        // is Copenhagen wall-clock; a nudge event's occurredAt is UTC.
        LocalDateTime nowCph = LocalDateTime.now(RecruitmentIdleFacts.COPENHAGEN);
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = nowCph.minusHours(thresholdHours);

        List<RecruitmentInterview> overdue = inTx(() -> RecruitmentInterview.list(
                "kind = ?1 and status <> ?2 and scheduledAt is not null and scheduledAt <= ?3",
                RecruitmentInterviewKind.ROUND, RecruitmentInterviewStatus.CANCELLED, cutoff));
        if (overdue.isEmpty()) {
            return;
        }
        Context ctx = inTx(() -> Context.load(overdue));

        for (RecruitmentInterview interview : overdue) {
            RecruitmentApplication application = ctx.applications.get(interview.getApplicationUuid());
            if (application == null || !applicationInPlay(application)) {
                continue; // decision made — a nudge would be noise
            }
            RecruitmentPosition position = ctx.positions.get(application.getPositionUuid());
            RecruitmentCandidate candidate = ctx.candidates.get(application.getCandidateUuid());
            Set<String> submitted = ctx.scorecards
                    .getOrDefault(interview.getUuid(), List.of()).stream()
                    .map(RecruitmentScorecard::getInterviewerUuid)
                    .collect(Collectors.toSet());
            long overdueHours = ChronoUnit.HOURS.between(interview.getScheduledAt(), nowCph);

            for (String interviewerUuid : interview.getInterviewerUuids()) {
                if (submitted.contains(interviewerUuid)) {
                    continue; // never after submission (plan DoD)
                }
                List<LocalDateTime> priorNudges = priorScorecardNudges(
                        ctx, interview.getUuid(), interviewerUuid);
                if (priorNudges.size() >= thresholds.maxScorecardNudges()) {
                    continue; // hard cap
                }
                if (!priorNudges.isEmpty()
                        && newest(priorNudges).isAfter(nowUtc.minusHours(thresholdHours))) {
                    continue; // one nudge per threshold period (occurredAt is UTC)
                }
                int nudgeNumber = priorNudges.size() + 1;
                String message = scorecardNudgeText(candidate, position,
                        interview.getRound(), nudgeNumber);
                // P18: with the scorecard toggle on, the nudge carries the
                // "Fill in scorecard" button (Slack spec §5.6); off ⇒ the
                // P17 deep-link-only text — the explicit degradation chain.
                List<com.slack.api.model.block.LayoutBlock> blocks =
                        inTx(slackFlags::isScorecardEnabled)
                                ? scorecardNudgeBlocks(message, interview.getUuid())
                                : null;
                boolean sent = nudge(counters, interviewerUuid, message, blocks, () ->
                        eventRecorder.record(RecruitmentEventBuilder
                                .event(RecruitmentEventType.SCORECARD_NUDGED)
                                .candidate(application.getCandidateUuid())
                                .application(application.getUuid())
                                .position(application.getPositionUuid())
                                .actorScheduler()
                                .visibility(visibilityFor(position))
                                .payload("interview_uuid", interview.getUuid())
                                .payload("round", interview.getRound())
                                .payload("nudged_user_uuid", interviewerUuid)
                                .payload("nudge_number", nudgeNumber)
                                .payload("overdue_hours", overdueHours)
                                .payload("threshold_hours", thresholdHours)));
                if (sent) {
                    counters.scorecards++;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Trigger 2 — debrief ready but unactioned
    // ------------------------------------------------------------------

    private void sweepStalledDebriefs(Counters counters) {
        int thresholdHours = inTx(thresholds::debriefStalledHours);
        // UTC throughout: this method reads only submittedAt and occurredAt,
        // never scheduledAt or endOf. See the class note on TWO CLOCKS.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        List<RecruitmentInterview> rounds = inTx(() -> RecruitmentInterview.list(
                "kind = ?1 and status <> ?2",
                RecruitmentInterviewKind.ROUND, RecruitmentInterviewStatus.CANCELLED));
        if (rounds.isEmpty()) {
            return;
        }
        Context ctx = inTx(() -> Context.load(rounds));

        for (RecruitmentInterview interview : rounds) {
            RecruitmentApplication application = ctx.applications.get(interview.getApplicationUuid());
            if (application == null || !applicationInPlay(application)) {
                continue;
            }
            RecruitmentStage roundStage = interview.roundStage();
            if (roundStage == null || application.getStage().ordinal() > roundStage.ordinal()) {
                continue; // the decision (a stage move past the round) was made
            }
            List<RecruitmentScorecard> cards =
                    ctx.scorecards.getOrDefault(interview.getUuid(), List.of());
            if (!RecruitmentInterviewService.allAssignedSubmitted(interview, cards)) {
                continue; // not debrief-ready — the scorecard trigger owns this state
            }
            LocalDateTime readySince = cards.stream()
                    .map(RecruitmentScorecard::getSubmittedAt)
                    .filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
            if (readySince == null || readySince.isAfter(now.minusHours(thresholdHours))) {
                continue;
            }
            LocalDateTime lastNudge = newestNudgeFor(ctx, RecruitmentEventType.DEBRIEF_STALLED_NUDGED,
                    "interview_uuid", interview.getUuid());
            if (lastNudge != null && lastNudge.isAfter(now.minusHours(thresholdHours))) {
                continue; // re-ping at most once per threshold period
            }
            RecruitmentPosition position = ctx.positions.get(application.getPositionUuid());
            RecruitmentCandidate candidate = ctx.candidates.get(application.getCandidateUuid());
            long stalledHours = ChronoUnit.HOURS.between(readySince, now);

            List<String> owners = resolveOwners(position);
            if (owners.isEmpty()) {
                log.infof("SLA sweep: debrief stalled on interview %s but position %s has no "
                                + "resolvable owner — skipping (landing task list still shows it)",
                        interview.getUuid(), application.getPositionUuid());
                continue;
            }
            String message = debriefNudgeText(candidate, position, interview.getRound(),
                    cards.size(), stalledHours);
            List<String> notified = dmAll(owners, message);
            if (notified.isEmpty()) {
                continue; // nobody linked — no bookkeeping, next sweep retries
            }
            boolean recorded = record(counters, () ->
                    eventRecorder.record(RecruitmentEventBuilder
                            .event(RecruitmentEventType.DEBRIEF_STALLED_NUDGED)
                            .candidate(application.getCandidateUuid())
                            .application(application.getUuid())
                            .position(application.getPositionUuid())
                            .actorScheduler()
                            .visibility(visibilityFor(position))
                            .payload("interview_uuid", interview.getUuid())
                            .payload("round", interview.getRound())
                            .payload("stalled_hours", stalledHours)
                            .payload("threshold_hours", thresholdHours)
                            .payload("nudged_user_uuids", notified)));
            if (recorded) {
                counters.debriefs++;
            }
        }
    }

    // ------------------------------------------------------------------
    // Trigger 3 — candidate idle
    // ------------------------------------------------------------------

    private void sweepIdleCandidates(Counters counters) {
        int thresholdDays = inTx(thresholds::candidateIdleDays);
        // Two clocks, and this method is the reason the distinction matters
        // most — see the class note on TWO CLOCKS. The idle CUTOFF measures
        // stageEnteredAt and lastProgressAt, both UTC. The facts LOADER
        // measures scheduledAt and endOf, both Copenhagen. One clock for
        // both is wrong whichever one you pick.
        LocalDateTime nowCph = LocalDateTime.now(RecruitmentIdleFacts.COPENHAGEN);
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = nowUtc.minusDays(thresholdDays);

        // Candidates for the trigger: the stage clock is only a cheap
        // pre-filter here. stage_entered_at can never be NEWER than the idle
        // rule's clock (the rule takes the max of it and the newest progress
        // event), so anything the rule would keep is already in this list —
        // the rule then removes, never adds.
        List<RecruitmentApplication> idle = inTx(() -> RecruitmentApplication.list(
                "terminal is null and stage <> ?1 and stageEnteredAt <= ?2",
                RecruitmentStage.HIRED, cutoff));
        if (idle.isEmpty()) {
            return;
        }
        Context ctx = inTx(() -> Context.loadForApplications(idle));

        // The same rule the landing page's IDLE_CANDIDATE row runs
        // (RecruitmentIdleRule, 2026-08-22). Before this, the sweep DM'd
        // owners about candidates whose next interview was already booked,
        // whose round was waiting on a colleague's scorecard, or who were
        // simultaneously being chased by the debrief-stalled trigger above —
        // Slack and the page disagreed about the same candidate on the same
        // morning, which is how a nudge channel becomes background noise.
        Map<String, RecruitmentIdleRule.Facts> facts =
                inTx(() -> idleFacts.load(idle, ctx.positions, nowCph));

        for (RecruitmentApplication application : idle) {
            RecruitmentIdleRule.Facts applicationFacts = facts.get(application.getUuid());
            RecruitmentIdleRule.Suppression suppressed =
                    applicationFacts == null ? RecruitmentIdleRule.Suppression.STILL_MOVING
                            : RecruitmentIdleRule.suppressedBecause(applicationFacts, cutoff);
            if (suppressed != null) {
                log.debugf("SLA sweep: application %s not idle (%s)",
                        application.getUuid(), suppressed);
                continue;
            }
            LocalDateTime lastNudge = newestNudgeFor(ctx, RecruitmentEventType.CANDIDATE_IDLE_NUDGED,
                    "application_uuid", application.getUuid());
            if (lastNudge != null && lastNudge.isAfter(nowUtc.minusDays(thresholdDays))) {
                continue; // re-ping at most once per threshold period
            }
            RecruitmentPosition position = ctx.positions.get(application.getPositionUuid());
            RecruitmentCandidate candidate = ctx.candidates.get(application.getCandidateUuid());
            long daysIdle = ChronoUnit.DAYS.between(applicationFacts.lastProgressAt(), nowUtc);

            List<String> owners = resolveOwners(position);
            if (owners.isEmpty()) {
                log.infof("SLA sweep: application %s idle %d days but position %s has no "
                                + "resolvable owner — skipping (landing task list still shows it)",
                        application.getUuid(), daysIdle, application.getPositionUuid());
                continue;
            }
            String message = idleNudgeText(candidate, position, application.getStage(), daysIdle);
            List<String> notified = dmAll(owners, message);
            if (notified.isEmpty()) {
                continue;
            }
            boolean recorded = record(counters, () ->
                    eventRecorder.record(RecruitmentEventBuilder
                            .event(RecruitmentEventType.CANDIDATE_IDLE_NUDGED)
                            .candidate(application.getCandidateUuid())
                            .application(application.getUuid())
                            .position(application.getPositionUuid())
                            .actorScheduler()
                            .visibility(visibilityFor(position))
                            .payload("application_uuid", application.getUuid())
                            .payload("stage", application.getStage().name())
                            .payload("days_idle", daysIdle)
                            .payload("stage_entered_at", application.getStageEnteredAt().toString())
                            .payload("last_progress_at",
                                    applicationFacts.lastProgressAt().toString())
                            .payload("threshold_days", thresholdDays)
                            .payload("nudged_user_uuids", notified)));
            if (recorded) {
                counters.idle++;
            }
        }
    }

    // ------------------------------------------------------------------
    // Recipient resolution
    // ------------------------------------------------------------------

    /**
     * The owner ladder (class javadoc): hiring owner → partner-circle
     * OWNERs → current team leads → nobody. Public since P18 — the Slack
     * reactor's debrief-ready owner DM resolves its recipient with this
     * exact rule (one ladder, never re-implemented).
     */
    public List<String> resolveOwners(RecruitmentPosition position) {
        if (position == null) {
            return List.of();
        }
        if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            if (position.getHiringOwnerUuid() != null
                    && !position.getHiringOwnerUuid().isBlank()
                    && inTx(() -> visibility.canReadPosition(
                            position.getHiringOwnerUuid(), position))) {
                return List.of(position.getHiringOwnerUuid());
            }
            return inTx(() -> RecruitmentCircleMember
                    .<RecruitmentCircleMember>list("positionUuid = ?1 and roleInCircle = ?2",
                            position.getUuid(), RecruitmentCircleRole.OWNER).stream()
                    .map(RecruitmentCircleMember::getUserUuid)
                    .distinct()
                    .toList());
        }
        if (position.getHiringOwnerUuid() != null && !position.getHiringOwnerUuid().isBlank()) {
            return List.of(position.getHiringOwnerUuid());
        }
        if (position.getTeamUuid() != null && !position.getTeamUuid().isBlank()) {
            return inTx(() -> currentTeamLeaders(position.getTeamUuid()));
        }
        return List.of();
    }

    /** Current leaders of a team — the temporal {@code teamroles} rule. */
    @SuppressWarnings("unchecked")
    private List<String> currentTeamLeaders(String teamUuid) {
        return em.createNativeQuery("""
                        SELECT DISTINCT useruuid FROM teamroles
                        WHERE teamuuid = :team AND membertype = 'LEADER'
                          AND startdate <= :today
                          AND (enddate > :today OR enddate IS NULL)
                        """)
                .setParameter("team", teamUuid)
                .setParameter("today", LocalDate.now())
                .getResultList();
    }

    // ------------------------------------------------------------------
    // Delivery
    // ------------------------------------------------------------------

    /**
     * DM one user and append the bookkeeping event in the same transaction
     * (DM first — the event commits only when the DM went out). Returns
     * true when both happened; a missing Slack link is an INFO skip.
     * {@code blocks} (nullable) upgrades the DM to Block Kit — P18 uses it
     * for the scorecard button; {@code message} stays the fallback text.
     */
    private boolean nudge(Counters counters, String userUuid, String message,
                          List<com.slack.api.model.block.LayoutBlock> blocks,
                          Runnable bookkeeping) {
        User user = inTx(() -> User.findById(userUuid));
        if (user == null || user.getSlackusername() == null || user.getSlackusername().isBlank()) {
            log.infof("SLA sweep: user %s has no Slack link — skipping nudge DM", userUuid);
            return false;
        }
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                try {
                    if (blocks != null) {
                        slackService.sendMessage(user, message, blocks);
                    } else {
                        slackService.sendMessage(user, message);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException("Slack DM failed", e);
                }
                bookkeeping.run();
            });
            return true;
        } catch (Exception e) {
            counters.failures++;
            log.warnf(e, "SLA sweep: nudge to user %s failed — continuing (next sweep retries)",
                    userUuid);
            return false;
        }
    }

    /** The nudge text as a section plus the P18 scorecard button. */
    private static List<com.slack.api.model.block.LayoutBlock> scorecardNudgeBlocks(
            String message, String interviewUuid) {
        return List.of(
                com.slack.api.model.block.Blocks.section(s -> s.text(
                        com.slack.api.model.block.composition.BlockCompositions
                                .markdownText(message))),
                SlackRecruitmentViews.scorecardActions(interviewUuid));
    }

    /**
     * DM every linked owner (nudges to a group share ONE bookkeeping event);
     * returns the uuids actually messaged. Transport failures are logged
     * and the recipient dropped from the notified list — with zero
     * successes the caller records nothing, so the next sweep retries.
     */
    private List<String> dmAll(List<String> userUuids, String message) {
        List<String> notified = new ArrayList<>(userUuids.size());
        for (String userUuid : userUuids) {
            User user = inTx(() -> User.findById(userUuid));
            if (user == null || user.getSlackusername() == null
                    || user.getSlackusername().isBlank()) {
                log.infof("SLA sweep: user %s has no Slack link — skipping nudge DM", userUuid);
                continue;
            }
            try {
                slackService.sendMessage(user, message);
                notified.add(userUuid);
            } catch (Exception e) {
                log.warnf(e, "SLA sweep: DM to user %s failed — continuing", userUuid);
            }
        }
        return notified;
    }

    /** Append one bookkeeping event in its own transaction; count failures. */
    private boolean record(Counters counters, Runnable bookkeeping) {
        try {
            QuarkusTransaction.requiringNew().run(bookkeeping::run);
            return true;
        } catch (Exception e) {
            counters.failures++;
            log.warnf(e, "SLA sweep: bookkeeping event failed — the next sweep may re-ping once");
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Message builders — structural facts + mrkdwn-escaped names only
    // ------------------------------------------------------------------

    /**
     * The end-of-meeting ask. Deliberately not the overdue wording: nothing
     * is late yet, so ":hourglass_flowing_sand: Scorecard overdue" would be
     * both wrong and the kind of small untruth that teaches people to skim
     * these DMs. Present tense, one job, and the 90-second promise the
     * overdue nudge already makes — the point is that it is cheap NOW.
     */
    String scorecardPromptText(RecruitmentCandidate candidate, RecruitmentPosition position,
                               Integer round) {
        StringBuilder sb = new StringBuilder(256)
                .append(":memo: *Scorecard* — your ");
        sb.append(round == null ? "interview" : "round " + round);
        sb.append(" with *").append(displayName(candidate)).append('*');
        if (position != null && position.getTitle() != null) {
            sb.append(" for *").append(SlackCandidateFacts.mrkdwnSafe(position.getTitle()))
                    .append('*');
        }
        sb.append(" just wrapped. About 90 seconds while it is fresh:\n")
                .append(baseUrl).append("/recruitment/interviews");
        return sb.toString();
    }

    String scorecardNudgeText(RecruitmentCandidate candidate, RecruitmentPosition position,
                              Integer round, int nudgeNumber) {
        StringBuilder sb = new StringBuilder(256)
                .append(":hourglass_flowing_sand: *Scorecard overdue* — your scorecard for *")
                .append(displayName(candidate)).append('*');
        appendPositionContext(sb, position, round);
        sb.append(" is still open");
        if (nudgeNumber >= thresholds.maxScorecardNudges()) {
            sb.append(" (final reminder)");
        }
        sb.append(". It takes about 90 seconds:\n")
                .append(baseUrl).append("/recruitment/interviews");
        return sb.toString();
    }

    String debriefNudgeText(RecruitmentCandidate candidate, RecruitmentPosition position,
                            Integer round, int scorecardCount, long stalledHours) {
        StringBuilder sb = new StringBuilder(256)
                .append(":clipboard: *Debrief waiting* — all ").append(scorecardCount)
                .append(" scorecards for *").append(displayName(candidate)).append('*');
        appendPositionContext(sb, position, round);
        sb.append(" have been in for ").append(stalledHours)
                .append(" hours without a decision.\n");
        if (candidate != null) {
            sb.append(baseUrl).append("/recruitment/candidates/").append(candidate.getUuid());
        } else {
            sb.append(baseUrl).append("/recruitment/pipeline");
        }
        return sb.toString();
    }

    String idleNudgeText(RecruitmentCandidate candidate, RecruitmentPosition position,
                         RecruitmentStage stage, long daysIdle) {
        StringBuilder sb = new StringBuilder(256)
                .append(":zzz: *Candidate idle* — *").append(displayName(candidate))
                .append("* has been in ").append(humanizeStage(stage)).append(" for ")
                .append(daysIdle).append(" days");
        if (position != null && position.getTitle() != null) {
            sb.append(" on *").append(SlackCandidateFacts.mrkdwnSafe(position.getTitle())).append('*');
        }
        sb.append(". Move them along or close the application:\n")
                .append(baseUrl).append("/recruitment/pipeline");
        if (position != null) {
            sb.append("?position=").append(position.getUuid());
        }
        return sb.toString();
    }

    private static void appendPositionContext(StringBuilder sb, RecruitmentPosition position,
                                              Integer round) {
        if (position == null || position.getTitle() == null) {
            if (round != null) {
                sb.append(" (round ").append(round).append(')');
            }
            return;
        }
        sb.append(" (*").append(SlackCandidateFacts.mrkdwnSafe(position.getTitle())).append('*');
        if (round != null) {
            sb.append(", round ").append(round);
        }
        sb.append(')');
    }

    private static String displayName(RecruitmentCandidate candidate) {
        if (candidate == null) {
            return "a candidate";
        }
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "a candidate" : SlackCandidateFacts.mrkdwnSafe(name);
    }

    /** {@code INTERVIEW_1} → {@code Interview 1}; {@code SCREENING} → {@code Screening}. */
    static String humanizeStage(RecruitmentStage stage) {
        if (stage == null) {
            return "its stage";
        }
        String lower = stage.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    // ------------------------------------------------------------------
    // Event-derived nudge bookkeeping
    // ------------------------------------------------------------------

    /**
     * Timestamps of every prior scorecard ask to this interviewer for this
     * interview — the end-of-meeting {@code SCORECARD_PROMPTED} and the
     * overdue {@code SCORECARD_NUDGED} counted together, deliberately.
     *
     * <p>They share one budget because
     * {@code recruitment.sla.max-scorecard-nudges} is a promise about how
     * often we may ask one person about one interview, not about which code
     * path did the asking. Counting them separately would have quietly
     * doubled that budget the day the prompt sweep shipped — trading a late
     * reminder for a nagging one, which is the opposite of the point.
     */
    private List<LocalDateTime> priorScorecardNudges(Context ctx, String interviewUuid,
                                                     String interviewerUuid) {
        return SCORECARD_ASK_TYPES.stream()
                .flatMap(type -> ctx.nudgeEvents.getOrDefault(type, List.of()).stream())
                .filter(e -> {
                    Map<String, Object> payload = parse(e.getPayload());
                    return interviewUuid.equals(payload.get("interview_uuid"))
                            && interviewerUuid.equals(payload.get("nudged_user_uuid"));
                })
                .map(RecruitmentEvent::getOccurredAt)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Newest nudge event of a type whose payload key matches the given value. */
    private LocalDateTime newestNudgeFor(Context ctx, RecruitmentEventType type,
                                         String payloadKey, String payloadValue) {
        return ctx.nudgeEvents.getOrDefault(type, List.of()).stream()
                .filter(e -> payloadValue.equals(parse(e.getPayload()).get(payloadKey)))
                .map(RecruitmentEvent::getOccurredAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private static LocalDateTime newest(List<LocalDateTime> timestamps) {
        return timestamps.stream().max(LocalDateTime::compareTo).orElse(null);
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------
    // Batched context (the module's no-N+1 rule)
    // ------------------------------------------------------------------

    /**
     * Everything one sub-sweep needs, loaded in a handful of batched
     * queries: applications, positions, candidates, scorecards and the
     * prior {@code *_NUDGED} events of the touched applications.
     */
    private record Context(Map<String, RecruitmentApplication> applications,
                           Map<String, RecruitmentPosition> positions,
                           Map<String, RecruitmentCandidate> candidates,
                           Map<String, List<RecruitmentScorecard>> scorecards,
                           Map<RecruitmentEventType, List<RecruitmentEvent>> nudgeEvents) {

        static Context load(Collection<RecruitmentInterview> interviews) {
            List<String> applicationUuids = interviews.stream()
                    .map(RecruitmentInterview::getApplicationUuid)
                    .distinct()
                    .toList();
            List<RecruitmentApplication> applications = applicationUuids.isEmpty() ? List.of()
                    : RecruitmentApplication.list("uuid in ?1", applicationUuids);
            Map<String, List<RecruitmentScorecard>> scorecards = interviews.isEmpty() ? Map.of()
                    : RecruitmentScorecard.<RecruitmentScorecard>list("interviewUuid in ?1",
                                    interviews.stream().map(RecruitmentInterview::getUuid).toList())
                            .stream()
                            .collect(Collectors.groupingBy(RecruitmentScorecard::getInterviewUuid));
            return finish(applications, scorecards);
        }

        static Context loadForApplications(List<RecruitmentApplication> applications) {
            return finish(applications, Map.of());
        }

        private static Context finish(List<RecruitmentApplication> applications,
                                      Map<String, List<RecruitmentScorecard>> scorecards) {
            Map<String, RecruitmentApplication> byUuid = applications.stream()
                    .collect(Collectors.toMap(RecruitmentApplication::getUuid, a -> a));
            Set<String> positionUuids = applications.stream()
                    .map(RecruitmentApplication::getPositionUuid)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, RecruitmentPosition> positions = positionUuids.isEmpty() ? Map.of()
                    : RecruitmentPosition.<RecruitmentPosition>list("uuid in ?1",
                                    List.copyOf(positionUuids)).stream()
                            .collect(Collectors.toMap(RecruitmentPosition::getUuid, p -> p));
            Set<String> candidateUuids = applications.stream()
                    .map(RecruitmentApplication::getCandidateUuid)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Map<String, RecruitmentCandidate> candidates = candidateUuids.isEmpty() ? Map.of()
                    : RecruitmentCandidate.<RecruitmentCandidate>list("uuid in ?1",
                                    List.copyOf(candidateUuids)).stream()
                            .collect(Collectors.toMap(RecruitmentCandidate::getUuid, c -> c));
            Map<RecruitmentEventType, List<RecruitmentEvent>> nudgeEvents =
                    applications.isEmpty() ? new HashMap<>()
                            : RecruitmentEvent.<RecruitmentEvent>list(
                                            "applicationUuid in ?1 and eventType in ?2",
                                            applications.stream()
                                                    .map(RecruitmentApplication::getUuid).toList(),
                                            NUDGE_EVENT_TYPES)
                                    .stream()
                                    .collect(Collectors.groupingBy(RecruitmentEvent::getEventType));
            return new Context(byUuid, positions, candidates, scorecards, nudgeEvents);
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static boolean applicationInPlay(RecruitmentApplication application) {
        return application.getTerminal() == null
                && application.getStage() != RecruitmentStage.HIRED;
    }

    private static RecruitmentEventVisibility visibilityFor(RecruitmentPosition position) {
        return position != null && position.getHiringTrack() == RecruitmentHiringTrack.PARTNER
                ? RecruitmentEventVisibility.CIRCLE
                : RecruitmentEventVisibility.NORMAL;
    }

    /** Reads on batch threads need a transaction (lazily-bound EntityManager). */
    private <T> T inTx(java.util.function.Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }
}
