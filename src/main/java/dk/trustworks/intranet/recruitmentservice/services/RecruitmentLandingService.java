package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingActivity;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingInterview;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingKpis;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingPipeline;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingStageCount;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse.LandingTask;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentReferralStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read model for the role-aware landing page (ATS plan §P17, spec §6.1
 * {@code /recruitment}) — a pure query service, no mutations, no events.
 * Builds the whole {@link LandingResponse} for one viewer in a bounded
 * number of batched queries (the module's no-N+1 rule; the DoD's p95
 * budget is one aggregated request under a second on staging volume).
 *
 * <h3>Authorization</h3>
 * Everything is derived from the {@code X-Requested-By} viewer through
 * {@link RecruitmentVisibility}: positions are query-level filtered
 * (partner circles stay a hard filter), decision-owned tasks come from
 * {@code decidablePositionUuids} (the shared batched twin of
 * {@code canDecideOnApplication}, not a copy of it), and the activity
 * feed drops CIRCLE events outside the viewer's circles plus every event
 * of partner-track-only candidates. Feed rows carry structural facts and
 * names only — never event {@code pii}.
 *
 * <h3>Task urgency order (served order = render order)</h3>
 * <ol>
 *   <li>{@code OVERDUE_SCORECARD} — blocks the debrief, the 90-second job
 *       (spec §8.5); most-overdue first.</li>
 *   <li>{@code PENDING_DECISION} — everyone has answered, the process
 *       waits for exactly one person.</li>
 *   <li>{@code EMAIL_REVIEW} — a candidate is waiting for a message a
 *       recruiter already decided to send (aggregate row).</li>
 *   <li>{@code REFERRAL_TO_TRIAGE} — intake queue (aggregate row).</li>
 *   <li>{@code IDLE_CANDIDATE} — chronic rather than acute; oldest
 *       first.</li>
 * </ol>
 *
 * <h3>What earns a row (2026-08-22)</h3>
 * Every row asserts the same thing: <em>the process is waiting on you, and
 * there is something to do about it right now.</em> A row that fails either
 * half is not a smaller task, it is noise — and noise on this card is
 * expensive, because the card's promise is "when this list is empty, you're
 * done". Three rules keep the assertion true:
 * <ul>
 *   <li><b>Idle rows go through {@link RecruitmentIdleRule}</b> — a truthful
 *       clock (last real progress, not last stage change) plus a court check
 *       (booked, out with the scheduling automation, waiting on a colleague's
 *       scorecard, queued for email review, on a paused requisition). The
 *       landing pipelines' idle badge and the nightly SLA DM run the same
 *       rule, so the three surfaces can never disagree.</li>
 *   <li><b>An application yields at most one row per viewer</b> — the
 *       sharper type wins. Before this, a debrief-ready candidate appeared
 *       both as "Decide on X" and as "X is waiting in Interview 2", with two
 *       different ages, which read as two different problems.</li>
 *   <li><b>"This round is over" is
 *       {@link RecruitmentInterviewService#decisionMade} and nothing else</b>
 *       — the page used to keep a private copy of the predicate (a bare
 *       stage-ordinal comparison) that missed V519's recorded-but-not-yet-moved
 *       decisions, so recording a decision left the nag standing.</li>
 * </ul>
 * Suppressed candidates keep their board card, their {@code daysInStage} and
 * their pipeline position — they stop being <em>tasks</em>, which is a claim
 * about who acts next, not about whether they exist.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentLandingService {

    // Feed page size, over-fetch and upcoming-interview count are admin
    // tunables since 2026-08-22 — see RecruitmentDisplayLimits.

    /**
     * Event types the feed renders — deliberately curated: process moves
     * and communication, no note events (private-note existence stays on
     * the profile timeline behind its authz) and no nudge bookkeeping.
     */
    static final Set<RecruitmentEventType> FEED_TYPES = EnumSet.of(
            RecruitmentEventType.APPLICATION_CREATED,
            RecruitmentEventType.APPLICATION_STAGE_CHANGED,
            RecruitmentEventType.APPLICATION_REJECTED,
            RecruitmentEventType.APPLICATION_WITHDRAWN,
            RecruitmentEventType.CANDIDATE_CREATED,
            RecruitmentEventType.CANDIDATE_POOLED,
            RecruitmentEventType.CANDIDATE_HIRED,
            RecruitmentEventType.INTERVIEW_SCHEDULED,
            RecruitmentEventType.INTERVIEW_RESCHEDULED,
            RecruitmentEventType.INTERVIEW_CANCELLED,
            RecruitmentEventType.SCORECARD_SUBMITTED,
            RecruitmentEventType.EMAIL_SENT,
            RecruitmentEventType.REFERRAL_SUBMITTED,
            RecruitmentEventType.POSITION_OPENED,
            RecruitmentEventType.POSITION_CLOSED,
            RecruitmentEventType.OFFER_OPENED,
            RecruitmentEventType.SIGNING_COMPLETED);

    @Inject
    EntityManager em;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentSlaThresholds thresholds;

    @Inject
    RecruitmentEmailService emailService;

    @Inject
    RecruitmentIdleFacts idleFactsLoader;

    @Inject
    RecruitmentDisplayLimits displayLimits;

    /** Build the full landing aggregate for one viewer, own pipelines only. */
    public LandingResponse build(String viewerUuid) {
        return build(viewerUuid, false);
    }

    /**
     * Build the full landing aggregate for one viewer.
     *
     * @param showAll when true, "Your pipelines" and the activity feed cover
     *                every position the viewer may read instead of only
     *                their own. The viewer's choice, carried per request
     *                (the frontend remembers it in the browser); it can only
     *                ever widen back to what visibility already allows.
     */
    public LandingResponse build(String viewerUuid, boolean showAll) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Set<String> roles = visibility.rolesOf(viewerUuid);
        boolean admin = roles.contains("ADMIN");
        boolean recruiterTier = admin || roles.contains("HR") || roles.contains("RECRUITMENT");

        // The viewer's whole visible position slice (any status) — one
        // query-level filtered fetch reused by tasks, pipelines and feed.
        List<RecruitmentPosition> visiblePositions =
                visibility.filterPositions(viewerUuid, null, null, null);
        Map<String, RecruitmentPosition> positionsByUuid = visiblePositions.stream()
                .collect(Collectors.toMap(RecruitmentPosition::getUuid, Function.identity()));
        List<RecruitmentPosition> openPositions = visiblePositions.stream()
                .filter(p -> p.getStatus() == RecruitmentPositionStatus.OPEN)
                .toList();

        // The viewer's own interview assignments (non-cancelled).
        List<RecruitmentInterview> ownInterviews = ownInterviews(viewerUuid);

        String shape = recruiterTier ? LandingResponse.SHAPE_RECRUITER
                : !visiblePositions.isEmpty() ? LandingResponse.SHAPE_INVOLVED
                : !ownInterviews.isEmpty() ? LandingResponse.SHAPE_INTERVIEWER
                : LandingResponse.SHAPE_EMPLOYEE;

        if (LandingResponse.SHAPE_EMPLOYEE.equals(shape)) {
            // No involvement: the client redirects to /recruitment/refer.
            return new LandingResponse(shape, new LandingKpis(0, 0, 0, 0),
                    List.of(), List.of(), List.of(), List.of(),
                    LandingResponse.PIPELINE_SCOPE_OWN, false);
        }

        // ---- Batched context around the visible slice --------------------
        List<RecruitmentApplication> openApplications = openApplicationsOn(visiblePositions);
        Map<String, List<RecruitmentApplication>> openByPosition = openApplications.stream()
                .collect(Collectors.groupingBy(RecruitmentApplication::getPositionUuid));

        // Interviews relevant to tasks: the viewer's own + the ones on
        // decidable applications (loaded together below).
        // The decision rule lives in one place (RecruitmentVisibility): this
        // page used to keep its own copy of the predicate, which would have
        // gone stale on 2026-08-12 when decision rights gained the practice
        // route.
        Set<String> decidablePositionUuids =
                visibility.decidablePositionUuids(viewerUuid, visiblePositions);
        List<RecruitmentApplication> decidableApplications = openApplications.stream()
                .filter(a -> decidablePositionUuids.contains(a.getPositionUuid()))
                .toList();

        // Every non-cancelled interview on the whole open slice, not just the
        // decidable one: the idle rule needs "is the next step already on a
        // calendar?" for every open application, and the pipelines' idle
        // badge counts the same population as the task rows (2026-08-22 — the
        // two used to be computed from different rules and disagreed on the
        // same screen).
        List<RecruitmentInterview> taskInterviews =
                interviewsFor(ownInterviews, openApplications);
        Map<String, List<RecruitmentScorecard>> scorecardsByInterview =
                scorecardsOf(taskInterviews);
        Map<String, RecruitmentApplication> taskApplications =
                applicationsFor(taskInterviews, openApplications);

        Map<String, RecruitmentCandidate> candidates = candidatesFor(
                openApplications, taskApplications.values());
        Map<String, RecruitmentPosition> taskPositions = positionsFor(
                positionsByUuid, taskApplications.values());

        // ---- Tasks, in urgency order --------------------------------------
        List<LandingTask> tasks = new ArrayList<>();
        tasks.addAll(overdueScorecardTasks(viewerUuid, ownInterviews, taskApplications,
                taskPositions, candidates, scorecardsByInterview, now));
        tasks.addAll(pendingDecisionTasks(decidableApplications, taskInterviews,
                scorecardsByInterview, taskPositions, candidates, now));
        if (recruiterTier) {
            emailReviewTask(viewerUuid).ifPresent(tasks::add);
            referralTriageTask().ifPresent(tasks::add);
        }

        // One idle-fact table for the whole open slice — three extra batched
        // queries (scheduling requests, pending emails, progress events) on
        // top of the interviews and scorecards already in hand. Shared by the
        // task rows below, the pipelines' idle badge and (through the same
        // loader) the nightly SLA sweep, so the three can never disagree.
        Map<String, RecruitmentIdleRule.Facts> idleFacts = idleFactsLoader.load(
                openApplications, taskPositions, taskInterviews, scorecardsByInterview, now);
        tasks.addAll(idleCandidateTasks(decidableApplications, taskPositions, candidates,
                idleFacts, now));

        // ---- KPI row -------------------------------------------------------
        // Always company-wide (everything the viewer may read), never scoped
        // to the card below it — product decision 2026-08-11: these answer
        // "how is hiring going", the card answers "what is mine". The
        // frontend's subtitles are worded to match; re-scoping these to the
        // card would make those labels lie.
        int activeCandidates = (int) openApplications.stream()
                .map(RecruitmentApplication::getCandidateUuid)
                .distinct()
                .count();
        int interviewsNext7Days = interviewsNext7Days(shape, ownInterviews,
                openApplications, now);
        int openTasks = tasks.stream()
                .mapToInt(t -> t.count() != null ? t.count() : 1)
                .sum();
        LandingKpis kpis = new LandingKpis(openPositions.size(), activeCandidates,
                interviewsNext7Days, openTasks);

        // ---- Pipeline scope: "yours" vs everything you may read ------------
        // Product decision 2026-08-11. "Your pipelines" led with every
        // non-partner position for anyone in POSITION_READ_ROLES, because it
        // was built on filterPositions back when that call WAS the
        // involvement set; go-live decision D3 widened the meaning underneath
        // it. The card now leads with the viewer's own positions and offers
        // to show the rest.
        //
        // The recruiter tier is never narrowed — the world is their job (spec
        // §6.1 "the recruiter sees the world") — and the interviewer shape has
        // no pipelines at all, so neither is offered the choice. Read access
        // is untouched: this only reorders what the page leads with, and
        // showAll can never reach past filterPositions.
        boolean interviewer = LandingResponse.SHAPE_INTERVIEWER.equals(shape);
        boolean scopeSelectable = !recruiterTier && !interviewer;
        boolean ownOnly = scopeSelectable && !showAll;

        // Any status, like positionsByUuid: the feed covers closed positions
        // too, so narrowing it must use the same breadth it replaces.
        Set<String> scopedPositionUuids = ownOnly
                ? visibility.ownPositionUuids(viewerUuid, visiblePositions)
                : positionsByUuid.keySet();
        List<RecruitmentPosition> scopedOpenPositions = ownOnly
                ? openPositions.stream()
                        .filter(p -> scopedPositionUuids.contains(p.getUuid()))
                        .toList()
                : openPositions;

        // Pipelines + feed are not built for the interviewer shape — spec
        // §6.1: an interviewer sees interviews + scorecards only.
        List<LandingPipeline> pipelines = interviewer
                ? List.of()
                : pipelines(scopedOpenPositions, openByPosition, idleFacts, now);
        List<LandingActivity> activity = interviewer
                ? List.of()
                : activityFeed(viewerUuid, scopedPositionUuids, candidates);

        List<LandingInterview> upcoming = upcomingInterviews(viewerUuid, ownInterviews,
                taskApplications, taskPositions, candidates, scorecardsByInterview, now);

        return new LandingResponse(shape, kpis, tasks, pipelines, upcoming, activity,
                ownOnly ? LandingResponse.PIPELINE_SCOPE_OWN
                        : LandingResponse.PIPELINE_SCOPE_ALL,
                scopeSelectable);
    }

    // ------------------------------------------------------------------
    // Own interviews (assignment-scoped, the P11 JSON_CONTAINS idiom)
    // ------------------------------------------------------------------

    private List<RecruitmentInterview> ownInterviews(String viewerUuid) {
        @SuppressWarnings("unchecked")
        List<String> uuids = em.createNativeQuery("""
                        SELECT i.uuid FROM recruitment_interviews i
                        WHERE i.status <> 'CANCELLED'
                          AND JSON_CONTAINS(i.interviewer_uuids, JSON_QUOTE(:viewer))
                        """)
                .setParameter("viewer", viewerUuid)
                .getResultList();
        return uuids.isEmpty() ? List.of() : RecruitmentInterview.list("uuid in ?1", uuids);
    }

    // ------------------------------------------------------------------
    // Decision-owner resolution (batched canDecideOnApplication twin)
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Task builders
    // ------------------------------------------------------------------

    /**
     * The viewer's own missing scorecards.
     *
     * <p>Two conditions beyond "you have not submitted" (2026-08-22):
     * <ul>
     *   <li>the meeting must actually be <em>over</em> — {@code scheduledAt +
     *       durationMinutes}, not {@code scheduledAt}. The row used to appear
     *       the minute an interview started, telling the interviewer they were
     *       late for a scorecard while still in the room with the candidate;</li>
     *   <li>the round must still be live — {@link
     *       RecruitmentInterviewService#decisionMade}. Once the decision is
     *       made (the stage moved, or V519 recorded it on the round), the
     *       card unblocks nobody, and the row's own copy — "your written
     *       impressions unblock the debrief for everyone" — stops being true.
     *       The scorecard stays submittable from {@code /recruitment/interviews};
     *       it just stops claiming to be urgent.</li>
     * </ul>
     */
    private List<LandingTask> overdueScorecardTasks(String viewerUuid,
                                                    List<RecruitmentInterview> ownInterviews,
                                                    Map<String, RecruitmentApplication> applications,
                                                    Map<String, RecruitmentPosition> positions,
                                                    Map<String, RecruitmentCandidate> candidates,
                                                    Map<String, List<RecruitmentScorecard>> scorecards,
                                                    LocalDateTime now) {
        return ownInterviews.stream()
                .filter(i -> i.getKind() == RecruitmentInterviewKind.ROUND)
                .filter(i -> i.getScheduledAt() != null && !RecruitmentIdleFacts.endOf(i).isAfter(now))
                .filter(i -> scorecards.getOrDefault(i.getUuid(), List.of()).stream()
                        .noneMatch(s -> s.getInterviewerUuid().equals(viewerUuid)))
                .filter(i -> {
                    RecruitmentApplication application = applications.get(i.getApplicationUuid());
                    return application != null && applicationInPlay(application)
                            && !RecruitmentInterviewService.decisionMade(application, i);
                })
                .sorted(Comparator.comparing(RecruitmentInterview::getScheduledAt))
                .map(i -> {
                    RecruitmentApplication application = applications.get(i.getApplicationUuid());
                    RecruitmentCandidate candidate = candidates.get(application.getCandidateUuid());
                    RecruitmentPosition position = positions.get(application.getPositionUuid());
                    return new LandingTask(LandingTask.TYPE_OVERDUE_SCORECARD,
                            application.getCandidateUuid(), displayName(candidate),
                            application.getPositionUuid(), titleOf(position),
                            application.getUuid(), i.getUuid(), i.getRound(),
                            application.getStage().name(),
                            ChronoUnit.HOURS.between(i.getScheduledAt(), now),
                            i.getScheduledAt(), null);
                })
                .toList();
    }

    /**
     * Rounds where everyone has answered and the process waits for exactly
     * one person — the viewer.
     *
     * <p>Three conditions beyond "all scorecards are in" (2026-08-22):
     * <ul>
     *   <li>the requisition is still {@code OPEN} — deciding on a candidate
     *       for a paused or closed req is a requisition conversation, not a
     *       candidate one;</li>
     *   <li>"this round is over" is {@link
     *       RecruitmentInterviewService#decisionMade}, not a private
     *       stage-ordinal copy of it. The copy predated V519 and missed
     *       recorded-but-not-yet-moved decisions, so recording the decision —
     *       which V519 defines as <em>being</em> the decision — left the row
     *       standing;</li>
     *   <li>nothing is booked ahead of it. When the next round (or an offer
     *       meeting, or a clarifying chat) is already in the calendar, the
     *       decision has visibly been taken and only the stage move lags —
     *       chasing it adds a row nobody can act on.</li>
     * </ul>
     */
    private List<LandingTask> pendingDecisionTasks(List<RecruitmentApplication> decidable,
                                                   List<RecruitmentInterview> interviews,
                                                   Map<String, List<RecruitmentScorecard>> scorecards,
                                                   Map<String, RecruitmentPosition> positions,
                                                   Map<String, RecruitmentCandidate> candidates,
                                                   LocalDateTime now) {
        Map<String, List<RecruitmentInterview>> byApplication = interviews.stream()
                .filter(i -> i.getKind() == RecruitmentInterviewKind.ROUND)
                .collect(Collectors.groupingBy(RecruitmentInterview::getApplicationUuid));
        Set<String> booked = RecruitmentIdleFacts.booked(interviews, now);

        List<LandingTask> tasks = new ArrayList<>();
        for (RecruitmentApplication application : decidable) {
            if (!applicationInPlay(application)
                    || !isOpen(positions.get(application.getPositionUuid()))
                    || booked.contains(application.getUuid())) {
                continue;
            }
            for (RecruitmentInterview interview
                    : byApplication.getOrDefault(application.getUuid(), List.of())) {
                if (interview.roundStage() == null
                        || RecruitmentInterviewService.decisionMade(application, interview)) {
                    continue; // decision made for this round
                }
                List<RecruitmentScorecard> cards =
                        scorecards.getOrDefault(interview.getUuid(), List.of());
                if (!RecruitmentInterviewService.allAssignedSubmitted(interview, cards)) {
                    continue; // not debrief-ready
                }
                LocalDateTime readySince = cards.stream()
                        .map(RecruitmentScorecard::getSubmittedAt)
                        .filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo)
                        .orElse(null);
                if (readySince == null) {
                    continue;
                }
                RecruitmentCandidate candidate = candidates.get(application.getCandidateUuid());
                RecruitmentPosition position = positions.get(application.getPositionUuid());
                tasks.add(new LandingTask(LandingTask.TYPE_PENDING_DECISION,
                        application.getCandidateUuid(), displayName(candidate),
                        application.getPositionUuid(), titleOf(position),
                        application.getUuid(), interview.getUuid(), interview.getRound(),
                        application.getStage().name(),
                        ChronoUnit.HOURS.between(readySince, now), readySince, null));
                break; // one decision task per application is enough
            }
        }
        tasks.sort(Comparator.comparing(LandingTask::ageHours,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return tasks;
    }

    /** Aggregate row for the review-before-send queue (P15 carry-over: the landing absorbs it). */
    private java.util.Optional<LandingTask> emailReviewTask(String viewerUuid) {
        List<String> partnerOnly =
                visibility.partnerTrackOnlyCandidateUuids(viewerUuid, null);
        Set<String> excluded = new HashSet<>(partnerOnly);
        long count = emailService.listPending().stream()
                .filter(pending -> !excluded.contains(pending.getCandidateUuid()))
                .count();
        return count == 0 ? java.util.Optional.empty()
                : java.util.Optional.of(new LandingTask(LandingTask.TYPE_EMAIL_REVIEW,
                        null, null, null, null, null, null, null, null, null, null,
                        (int) count));
    }

    /** Aggregate row for the referral triage queue (P6 carry-over: reuse the pending queue). */
    private java.util.Optional<LandingTask> referralTriageTask() {
        long count = RecruitmentReferral.count("status", RecruitmentReferralStatus.SUBMITTED);
        return count == 0 ? java.util.Optional.empty()
                : java.util.Optional.of(new LandingTask(LandingTask.TYPE_REFERRAL_TO_TRIAGE,
                        null, null, null, null, null, null, null, null, null, null,
                        (int) count));
    }

    /**
     * Candidates nothing is happening to and nothing is queued to happen to —
     * the {@link RecruitmentIdleRule} population, oldest first.
     *
     * <p>{@code ageHours} and {@code since} are measured from the rule's
     * clock ({@code lastProgressAt}), not from {@code stage_entered_at}: the
     * number the card shows is "how long has this been still", and a stage
     * that has not changed since an interview was rescheduled yesterday has
     * not been still for eleven days. The frontend copy says so.
     */
    private List<LandingTask> idleCandidateTasks(List<RecruitmentApplication> decidable,
                                                 Map<String, RecruitmentPosition> positions,
                                                 Map<String, RecruitmentCandidate> candidates,
                                                 Map<String, RecruitmentIdleRule.Facts> facts,
                                                 LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(thresholds.candidateIdleDays());
        return decidable.stream()
                .filter(this::applicationInPlayStatic)
                .filter(a -> RecruitmentIdleRule.isIdleTask(facts.get(a.getUuid()), cutoff))
                .sorted(Comparator.comparing(a -> facts.get(a.getUuid()).lastProgressAt()))
                .map(a -> {
                    LocalDateTime since = facts.get(a.getUuid()).lastProgressAt();
                    return new LandingTask(LandingTask.TYPE_IDLE_CANDIDATE,
                            a.getCandidateUuid(), displayName(candidates.get(a.getCandidateUuid())),
                            a.getPositionUuid(), titleOf(positions.get(a.getPositionUuid())),
                            a.getUuid(), null, null, a.getStage().name(),
                            ChronoUnit.HOURS.between(since, now), since, null);
                })
                .toList();
    }

    private static boolean isOpen(RecruitmentPosition position) {
        return position != null && position.getStatus() == RecruitmentPositionStatus.OPEN;
    }

    // ------------------------------------------------------------------
    // KPI helpers
    // ------------------------------------------------------------------

    private int interviewsNext7Days(String shape, List<RecruitmentInterview> ownInterviews,
                                    List<RecruitmentApplication> openApplications,
                                    LocalDateTime now) {
        LocalDateTime horizon = now.plusDays(7);
        if (LandingResponse.SHAPE_INTERVIEWER.equals(shape)) {
            return (int) ownInterviews.stream()
                    .filter(i -> i.getScheduledAt() != null
                            && !i.getScheduledAt().isBefore(now)
                            && !i.getScheduledAt().isAfter(horizon))
                    .count();
        }
        if (openApplications.isEmpty()) {
            return 0;
        }
        return (int) RecruitmentInterview.count(
                "applicationUuid in ?1 and status <> ?2 and scheduledAt >= ?3 and scheduledAt <= ?4",
                openApplications.stream().map(RecruitmentApplication::getUuid).toList(),
                RecruitmentInterviewStatus.CANCELLED, now, horizon);
    }

    // ------------------------------------------------------------------
    // Pipelines
    // ------------------------------------------------------------------

    /**
     * "Your pipelines". {@code idleCount} counts exactly the population that
     * would produce an {@code IDLE_CANDIDATE} task on the card above
     * ({@link RecruitmentIdleRule}) — before 2026-08-22 the badge ran a bare
     * {@code stage_entered_at} comparison of its own, so the amber number and
     * the task list disagreed on the same screen and taught the viewer to
     * trust neither.
     */
    private List<LandingPipeline> pipelines(List<RecruitmentPosition> openPositions,
                                            Map<String, List<RecruitmentApplication>> openByPosition,
                                            Map<String, RecruitmentIdleRule.Facts> idleFacts,
                                            LocalDateTime now) {
        LocalDateTime idleCutoff = now.minusDays(thresholds.candidateIdleDays());
        return openPositions.stream()
                .map(position -> {
                    List<RecruitmentApplication> open =
                            openByPosition.getOrDefault(position.getUuid(), List.of());
                    List<String> stageSet = position.getStageSet() != null
                            && !position.getStageSet().isEmpty()
                            ? position.getStageSet()
                            : RecruitmentPositionDefaults.defaultStageSet(position.getHiringTrack());
                    Map<String, Long> byStage = open.stream()
                            .collect(Collectors.groupingBy(a -> a.getStage().name(),
                                    Collectors.counting()));
                    List<LandingStageCount> stageCounts = stageSet.stream()
                            .map(stage -> new LandingStageCount(stage,
                                    byStage.getOrDefault(stage, 0L).intValue()))
                            .toList();
                    int idleCount = (int) open.stream()
                            .filter(a -> RecruitmentIdleRule.isIdleTask(idleFacts.get(a.getUuid()), idleCutoff))
                            .count();
                    return new LandingPipeline(position.getUuid(), position.getTitle(),
                            position.getPracticeName(),
                            position.getHiringTrack() == null ? null
                                    : position.getHiringTrack().name(),
                            position.getDemandRag() == null ? null
                                    : position.getDemandRag().name(),
                            open.size(), idleCount, stageCounts);
                })
                .toList();
    }

    // ------------------------------------------------------------------
    // Upcoming own interviews
    // ------------------------------------------------------------------

    private List<LandingInterview> upcomingInterviews(String viewerUuid,
                                                      List<RecruitmentInterview> ownInterviews,
                                                      Map<String, RecruitmentApplication> applications,
                                                      Map<String, RecruitmentPosition> positions,
                                                      Map<String, RecruitmentCandidate> candidates,
                                                      Map<String, List<RecruitmentScorecard>> scorecards,
                                                      LocalDateTime now) {
        return ownInterviews.stream()
                .filter(i -> i.getScheduledAt() != null && i.getScheduledAt().isAfter(now))
                .sorted(Comparator.comparing(RecruitmentInterview::getScheduledAt))
                .limit(displayLimits.upcomingInterviewRows())
                .map(i -> {
                    RecruitmentApplication application = applications.get(i.getApplicationUuid());
                    RecruitmentCandidate candidate = application == null ? null
                            : candidates.get(application.getCandidateUuid());
                    RecruitmentPosition position = application == null ? null
                            : positions.get(application.getPositionUuid());
                    boolean ownSubmitted = scorecards.getOrDefault(i.getUuid(), List.of()).stream()
                            .anyMatch(s -> s.getInterviewerUuid().equals(viewerUuid));
                    return new LandingInterview(i.getUuid(),
                            application == null ? null : application.getCandidateUuid(),
                            displayName(candidate), titleOf(position),
                            i.getKind().name(), i.getRound(), i.getScheduledAt(),
                            i.getLocation(),
                            i.getKind().takesScorecard(), ownSubmitted);
                })
                .toList();
    }

    // ------------------------------------------------------------------
    // Activity feed
    // ------------------------------------------------------------------

    private List<LandingActivity> activityFeed(String viewerUuid,
                                               Set<String> visiblePositionUuids,
                                               Map<String, RecruitmentCandidate> preloadedCandidates) {
        List<RecruitmentEvent> raw = RecruitmentEvent.<RecruitmentEvent>find(
                        "eventType in ?1 order by seq desc", List.copyOf(FEED_TYPES))
                .page(0, displayLimits.activityFetchRows())
                .list();
        if (raw.isEmpty()) {
            return List.of();
        }
        // Partner hard filter, applied to events (spec §7.2): CIRCLE events
        // require a visible position (fail closed on position-less CIRCLE
        // rows); every event of a partner-track-only candidate drops too.
        Set<String> partnerOnlyCandidates = new HashSet<>(
                visibility.partnerTrackOnlyCandidateUuids(viewerUuid, null));
        List<RecruitmentEvent> visible = raw.stream()
                .filter(e -> e.getVisibility() != RecruitmentEventVisibility.CIRCLE
                        || (e.getPositionUuid() != null
                        && visiblePositionUuids.contains(e.getPositionUuid())))
                .filter(e -> e.getPositionUuid() == null
                        || visiblePositionUuids.contains(e.getPositionUuid()))
                .filter(e -> e.getCandidateUuid() == null
                        || !partnerOnlyCandidates.contains(e.getCandidateUuid()))
                .limit(displayLimits.activityRows())
                .toList();

        // Batched name resolution — candidates, positions, actors.
        Map<String, RecruitmentCandidate> candidates = candidatesByUuid(
                visible.stream().map(RecruitmentEvent::getCandidateUuid)
                        .filter(Objects::nonNull).collect(Collectors.toSet()),
                preloadedCandidates);
        Map<String, String> positionTitles = positionTitles(
                visible.stream().map(RecruitmentEvent::getPositionUuid)
                        .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<String, String> actorNames = userNames(
                visible.stream().map(RecruitmentEvent::getActorUuid)
                        .filter(Objects::nonNull).collect(Collectors.toSet()));

        return visible.stream()
                .map(e -> new LandingActivity(
                        e.getSeq(),
                        e.getEventType().name(),
                        e.getOccurredAt(),
                        e.getCandidateUuid(),
                        e.getCandidateUuid() == null ? null
                                : displayName(candidates.get(e.getCandidateUuid())),
                        e.getPositionUuid() == null ? null
                                : positionTitles.get(e.getPositionUuid()),
                        e.getActorType() == null ? null : e.getActorType().name(),
                        e.getActorUuid() == null ? null : actorNames.get(e.getActorUuid())))
                .toList();
    }

    // ------------------------------------------------------------------
    // Batched lookups
    // ------------------------------------------------------------------

    /**
     * Every application still IN PLAY on the given positions — the one fetch
     * the KPI row, the pipeline board and the task rows are all built from.
     * <p>
     * {@code stage <> HIRED} is not optional: HIRED is a stage, not a
     * terminal, so {@code markHired} leaves {@code terminal} NULL forever.
     * Without it a converted hire kept inflating "Candidates in pipeline"
     * and every per-position open/stage/idle count. The task rows already
     * defended themselves with {@link #applicationInPlay}; the counts did
     * not, which is exactly the half-fix this closes.
     * <p>
     * Safe to narrow here: {@code applicationsFor} / {@code positionsFor} /
     * {@code candidatesFor} all backfill anything an interview references
     * but this fetch no longer returns, so labels on the viewer's own
     * upcoming interviews survive.
     */
    private List<RecruitmentApplication> openApplicationsOn(
            List<RecruitmentPosition> positions) {
        if (positions.isEmpty()) {
            return List.of();
        }
        return RecruitmentApplication.list(
                "positionUuid in ?1 and terminal is null and stage <> ?2",
                positions.stream().map(RecruitmentPosition::getUuid).toList(),
                RecruitmentStage.HIRED);
    }

    /**
     * Every non-cancelled interview (any kind) for the union of the viewer's
     * own assignments and the whole open-application slice.
     *
     * <p>Widened from "decidable applications" on 2026-08-22: the idle rule
     * asks "is the next step already booked?" of every open application, and
     * the pipelines' idle badge must count the same population as the task
     * rows. One query either way — the {@code in} list simply gets longer.
     */
    private List<RecruitmentInterview> interviewsFor(
            List<RecruitmentInterview> ownInterviews,
            List<RecruitmentApplication> openApplications) {
        Set<String> applicationUuids = openApplications.stream()
                .map(RecruitmentApplication::getUuid)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<RecruitmentInterview> merged = new ArrayList<>(ownInterviews);
        Set<String> seen = ownInterviews.stream()
                .map(RecruitmentInterview::getUuid)
                .collect(Collectors.toCollection(HashSet::new));
        if (!applicationUuids.isEmpty()) {
            RecruitmentInterview.<RecruitmentInterview>list(
                            "applicationUuid in ?1 and status <> ?2",
                            List.copyOf(applicationUuids), RecruitmentInterviewStatus.CANCELLED)
                    .forEach(i -> {
                        if (seen.add(i.getUuid())) {
                            merged.add(i);
                        }
                    });
        }
        return merged;
    }

    private Map<String, List<RecruitmentScorecard>> scorecardsOf(
            Collection<RecruitmentInterview> interviews) {
        if (interviews.isEmpty()) {
            return Map.of();
        }
        return RecruitmentScorecard.<RecruitmentScorecard>list("interviewUuid in ?1",
                        interviews.stream().map(RecruitmentInterview::getUuid).toList())
                .stream()
                .collect(Collectors.groupingBy(RecruitmentScorecard::getInterviewUuid));
    }

    /** Applications referenced by task interviews, merged with the open-application fetch. */
    private Map<String, RecruitmentApplication> applicationsFor(
            List<RecruitmentInterview> interviews,
            List<RecruitmentApplication> openApplications) {
        Map<String, RecruitmentApplication> byUuid = openApplications.stream()
                .collect(Collectors.toMap(RecruitmentApplication::getUuid,
                        Function.identity(), (a, b) -> a,
                        java.util.HashMap::new));
        List<String> missing = interviews.stream()
                .map(RecruitmentInterview::getApplicationUuid)
                .distinct()
                .filter(uuid -> !byUuid.containsKey(uuid))
                .toList();
        if (!missing.isEmpty()) {
            RecruitmentApplication.<RecruitmentApplication>list("uuid in ?1", missing)
                    .forEach(a -> byUuid.put(a.getUuid(), a));
        }
        return byUuid;
    }

    private Map<String, RecruitmentCandidate> candidatesFor(
            List<RecruitmentApplication> openApplications,
            Collection<RecruitmentApplication> taskApplications) {
        Set<String> uuids = new LinkedHashSet<>();
        openApplications.forEach(a -> uuids.add(a.getCandidateUuid()));
        taskApplications.forEach(a -> uuids.add(a.getCandidateUuid()));
        if (uuids.isEmpty()) {
            return Map.of();
        }
        return RecruitmentCandidate.<RecruitmentCandidate>list("uuid in ?1",
                        List.copyOf(uuids)).stream()
                .collect(Collectors.toMap(RecruitmentCandidate::getUuid, Function.identity()));
    }

    /** Visible positions plus any task-application position missing from the slice. */
    private Map<String, RecruitmentPosition> positionsFor(
            Map<String, RecruitmentPosition> visible,
            Collection<RecruitmentApplication> taskApplications) {
        Map<String, RecruitmentPosition> byUuid = new java.util.HashMap<>(visible);
        List<String> missing = taskApplications.stream()
                .map(RecruitmentApplication::getPositionUuid)
                .distinct()
                .filter(uuid -> !byUuid.containsKey(uuid))
                .toList();
        if (!missing.isEmpty()) {
            RecruitmentPosition.<RecruitmentPosition>list("uuid in ?1", missing)
                    .forEach(p -> byUuid.put(p.getUuid(), p));
        }
        return byUuid;
    }

    private Map<String, RecruitmentCandidate> candidatesByUuid(
            Set<String> uuids, Map<String, RecruitmentCandidate> preloaded) {
        if (uuids.isEmpty()) {
            return Map.of();
        }
        Map<String, RecruitmentCandidate> result = new java.util.HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String uuid : uuids) {
            RecruitmentCandidate candidate = preloaded.get(uuid);
            if (candidate != null) {
                result.put(uuid, candidate);
            } else {
                missing.add(uuid);
            }
        }
        if (!missing.isEmpty()) {
            RecruitmentCandidate.<RecruitmentCandidate>list("uuid in ?1", missing)
                    .forEach(c -> result.put(c.getUuid(), c));
        }
        return result;
    }

    private Map<String, String> positionTitles(Set<String> uuids) {
        if (uuids.isEmpty()) {
            return Map.of();
        }
        return RecruitmentPosition.<RecruitmentPosition>list("uuid in ?1",
                        List.copyOf(uuids)).stream()
                .filter(p -> p.getTitle() != null)
                .collect(Collectors.toMap(RecruitmentPosition::getUuid,
                        RecruitmentPosition::getTitle));
    }

    /** Batched user-name resolution — the timeline's no-N+1 idiom. */
    @SuppressWarnings("unchecked")
    private Map<String, String> userNames(Collection<String> userUuids) {
        List<String> distinct = userUuids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT uuid, TRIM(CONCAT(COALESCE(firstname, ''), ' ', COALESCE(lastname, '')))
                        FROM user
                        WHERE uuid IN (:uuids)
                        """)
                .setParameter("uuids", distinct)
                .getResultList();
        return rows.stream()
                .filter(row -> row[1] != null && !((String) row[1]).isBlank())
                .collect(Collectors.toMap(row -> (String) row[0], row -> (String) row[1]));
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /**
     * "Still in play": open AND not already hired. {@link #openApplicationsOn}
     * now applies the same rule in SQL, so callers working off that fetch are
     * already clean — this stays as the in-memory statement of the rule (and
     * as cover for anything backfilled by {@code applicationsFor}).
     */
    private static boolean applicationInPlay(RecruitmentApplication application) {
        return application.getTerminal() == null
                && application.getStage() != RecruitmentStage.HIRED;
    }

    /** Instance-method twin for method references in streams. */
    private boolean applicationInPlayStatic(RecruitmentApplication application) {
        return applicationInPlay(application);
    }

    private static String displayName(RecruitmentCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? null : name;
    }

    private static String titleOf(RecruitmentPosition position) {
        return position == null ? null : position.getTitle();
    }
}
