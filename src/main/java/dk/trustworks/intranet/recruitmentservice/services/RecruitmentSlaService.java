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
 *       interviewer ({@code SCORECARD_NUDGED}, hard cap of 2 per
 *       interviewer per interview, never after submission).</li>
 *   <li><b>Debrief stalled</b> — every assigned interviewer has submitted
 *       (the shared {@code allAssignedSubmitted} rule) but the application
 *       has not moved past the round → DM to the decision owner
 *       ({@code DEBRIEF_STALLED_NUDGED}, re-pinged at most once per
 *       threshold period).</li>
 *   <li><b>Candidate idle</b> — an open application has sat in one stage
 *       beyond the threshold → DM to the decision owner
 *       ({@code CANDIDATE_IDLE_NUDGED}, re-pinged at most once per
 *       threshold period while still idle).</li>
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
 * <h3>Recipient resolution (owner ladder)</h3>
 * For the two owner pings: the position's {@code hiring_owner_uuid} when
 * set; else on partner track the circle {@code OWNER} members; else the
 * current leaders of the position's team; else nobody (INFO skip) —
 * deliberately narrow so a misconfigured position spams no one.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentSlaService {

    /** Spec §8.4 / plan §P17: at most two scorecard nudges per interviewer per interview. */
    static final int MAX_SCORECARD_NUDGES = 2;

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
    RecruitmentSlaThresholds thresholds;

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

    private static final class Counters {
        int scorecards;
        int debriefs;
        int idle;
        int failures;
    }

    // ------------------------------------------------------------------
    // Trigger 1 — scorecard overdue
    // ------------------------------------------------------------------

    private void sweepOverdueScorecards(Counters counters) {
        int thresholdHours = inTx(thresholds::scorecardOverdueHours);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusHours(thresholdHours);

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
            long overdueHours = ChronoUnit.HOURS.between(interview.getScheduledAt(), now);

            for (String interviewerUuid : interview.getInterviewerUuids()) {
                if (submitted.contains(interviewerUuid)) {
                    continue; // never after submission (plan DoD)
                }
                List<LocalDateTime> priorNudges = priorScorecardNudges(
                        ctx, interview.getUuid(), interviewerUuid);
                if (priorNudges.size() >= MAX_SCORECARD_NUDGES) {
                    continue; // hard cap
                }
                if (!priorNudges.isEmpty()
                        && newest(priorNudges).isAfter(now.minusHours(thresholdHours))) {
                    continue; // one nudge per threshold period
                }
                int nudgeNumber = priorNudges.size() + 1;
                String message = scorecardNudgeText(candidate, position,
                        interview.getRound(), nudgeNumber);
                boolean sent = nudge(counters, interviewerUuid, message, () ->
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
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusDays(thresholdDays);

        List<RecruitmentApplication> idle = inTx(() -> RecruitmentApplication.list(
                "terminal is null and stage <> ?1 and stageEnteredAt <= ?2",
                RecruitmentStage.HIRED, cutoff));
        if (idle.isEmpty()) {
            return;
        }
        Context ctx = inTx(() -> Context.loadForApplications(idle));

        for (RecruitmentApplication application : idle) {
            LocalDateTime lastNudge = newestNudgeFor(ctx, RecruitmentEventType.CANDIDATE_IDLE_NUDGED,
                    "application_uuid", application.getUuid());
            if (lastNudge != null && lastNudge.isAfter(now.minusDays(thresholdDays))) {
                continue; // re-ping at most once per threshold period
            }
            RecruitmentPosition position = ctx.positions.get(application.getPositionUuid());
            RecruitmentCandidate candidate = ctx.candidates.get(application.getCandidateUuid());
            long daysIdle = ChronoUnit.DAYS.between(application.getStageEnteredAt(), now);

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
     * OWNERs → current team leads → nobody.
     */
    List<String> resolveOwners(RecruitmentPosition position) {
        if (position == null) {
            return List.of();
        }
        if (position.getHiringOwnerUuid() != null && !position.getHiringOwnerUuid().isBlank()) {
            return List.of(position.getHiringOwnerUuid());
        }
        if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            return inTx(() -> RecruitmentCircleMember
                    .<RecruitmentCircleMember>list("positionUuid = ?1 and roleInCircle = ?2",
                            position.getUuid(), RecruitmentCircleRole.OWNER).stream()
                    .map(RecruitmentCircleMember::getUserUuid)
                    .distinct()
                    .toList());
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
     */
    private boolean nudge(Counters counters, String userUuid, String message, Runnable bookkeeping) {
        User user = inTx(() -> User.findById(userUuid));
        if (user == null || user.getSlackusername() == null || user.getSlackusername().isBlank()) {
            log.infof("SLA sweep: user %s has no Slack link — skipping nudge DM", userUuid);
            return false;
        }
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                try {
                    slackService.sendMessage(user, message);
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

    String scorecardNudgeText(RecruitmentCandidate candidate, RecruitmentPosition position,
                              Integer round, int nudgeNumber) {
        StringBuilder sb = new StringBuilder(256)
                .append(":hourglass_flowing_sand: *Scorecard overdue* — your scorecard for *")
                .append(displayName(candidate)).append('*');
        appendPositionContext(sb, position, round);
        sb.append(" is still open");
        if (nudgeNumber >= MAX_SCORECARD_NUDGES) {
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

    /** Timestamps of prior scorecard nudges to this interviewer for this interview. */
    private List<LocalDateTime> priorScorecardNudges(Context ctx, String interviewUuid,
                                                     String interviewerUuid) {
        return ctx.nudgeEvents.getOrDefault(RecruitmentEventType.SCORECARD_NUDGED, List.of()).stream()
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
                                            List.of(RecruitmentEventType.SCORECARD_NUDGED,
                                                    RecruitmentEventType.DEBRIEF_STALLED_NUDGED,
                                                    RecruitmentEventType.CANDIDATE_IDLE_NUDGED))
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
