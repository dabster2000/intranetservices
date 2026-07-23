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
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCircleMember;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentCircleRole;
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
 * (partner circles stay a hard filter), decision-owned tasks replicate
 * {@code canDecideOnApplication} with batched lookups, and the activity
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
 */
@JBossLog
@ApplicationScoped
public class RecruitmentLandingService {

    /** Feed page size served to the client. */
    static final int FEED_SIZE = 15;
    /** Raw events fetched before visibility filtering (some rows drop out). */
    static final int FEED_FETCH_SIZE = 60;
    /** Upcoming own interviews served. */
    static final int UPCOMING_INTERVIEWS_SIZE = 5;

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

    /** Build the full landing aggregate for one viewer. */
    public LandingResponse build(String viewerUuid) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Set<String> roles = visibility.rolesOf(viewerUuid);
        boolean admin = roles.contains("ADMIN");
        boolean recruiterTier = admin || roles.contains("HR") || roles.contains("CXO");

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
                    List.of(), List.of(), List.of(), List.of());
        }

        // ---- Batched context around the visible slice --------------------
        List<RecruitmentApplication> openApplications = openApplicationsOn(visiblePositions);
        Map<String, List<RecruitmentApplication>> openByPosition = openApplications.stream()
                .collect(Collectors.groupingBy(RecruitmentApplication::getPositionUuid));

        // Interviews relevant to tasks: the viewer's own + the ones on
        // decidable applications (loaded together below).
        Set<String> decidablePositionUuids =
                decidablePositionUuids(viewerUuid, roles, visiblePositions);
        List<RecruitmentApplication> decidableApplications = openApplications.stream()
                .filter(a -> decidablePositionUuids.contains(a.getPositionUuid()))
                .toList();

        List<RecruitmentInterview> taskInterviews =
                roundInterviewsFor(ownInterviews, decidableApplications);
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
        tasks.addAll(idleCandidateTasks(decidableApplications, taskPositions, candidates, now));

        // ---- KPI row -------------------------------------------------------
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

        // ---- Pipelines + feed (not for the interviewer shape — spec §6.1:
        // an interviewer sees interviews + scorecards only) -----------------
        List<LandingPipeline> pipelines = LandingResponse.SHAPE_INTERVIEWER.equals(shape)
                ? List.of()
                : pipelines(openPositions, openByPosition, now);
        List<LandingActivity> activity = LandingResponse.SHAPE_INTERVIEWER.equals(shape)
                ? List.of()
                : activityFeed(viewerUuid, positionsByUuid.keySet(), candidates);

        List<LandingInterview> upcoming = upcomingInterviews(viewerUuid, ownInterviews,
                taskApplications, taskPositions, candidates, scorecardsByInterview, now);

        return new LandingResponse(shape, kpis, tasks, pipelines, upcoming, activity);
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

    /**
     * The visible positions the viewer may decide on — mirrors
     * {@link RecruitmentVisibility#canDecideOnApplication} with per-call
     * (not per-row) lookups: admin/recruiter everywhere; partner track only
     * for circle OWNER/RECRUITER members; otherwise hiring owner or current
     * team lead. Change the two together.
     */
    private Set<String> decidablePositionUuids(String viewerUuid, Set<String> roles,
                                               List<RecruitmentPosition> visiblePositions) {
        boolean admin = roles.contains("ADMIN");
        boolean recruiterTier = admin || roles.contains("HR") || roles.contains("CXO");
        Set<String> ledTeams = recruiterTier ? Set.of()
                : new HashSet<>(visibility.currentlyLedTeams(viewerUuid));
        Map<String, RecruitmentCircleRole> circleRoles = admin ? Map.of()
                : RecruitmentCircleMember.<RecruitmentCircleMember>list("userUuid", viewerUuid)
                        .stream()
                        .collect(Collectors.toMap(RecruitmentCircleMember::getPositionUuid,
                                RecruitmentCircleMember::getRoleInCircle));

        return visiblePositions.stream().filter(position -> {
            if (admin) {
                return true;
            }
            if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
                // HR decides everywhere except outside partner circles it
                // is not an OWNER/RECRUITER member of (canManageCircle
                // grants plain HR; visibility already required membership).
                if (roles.contains("HR")) {
                    return true;
                }
                RecruitmentCircleRole role = circleRoles.get(position.getUuid());
                return role == RecruitmentCircleRole.OWNER
                        || role == RecruitmentCircleRole.RECRUITER;
            }
            if (recruiterTier) {
                return true;
            }
            return viewerUuid.equals(position.getHiringOwnerUuid())
                    || (position.getTeamUuid() != null && ledTeams.contains(position.getTeamUuid()));
        }).map(RecruitmentPosition::getUuid).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------
    // Task builders
    // ------------------------------------------------------------------

    private List<LandingTask> overdueScorecardTasks(String viewerUuid,
                                                    List<RecruitmentInterview> ownInterviews,
                                                    Map<String, RecruitmentApplication> applications,
                                                    Map<String, RecruitmentPosition> positions,
                                                    Map<String, RecruitmentCandidate> candidates,
                                                    Map<String, List<RecruitmentScorecard>> scorecards,
                                                    LocalDateTime now) {
        return ownInterviews.stream()
                .filter(i -> i.getKind() == RecruitmentInterviewKind.ROUND)
                .filter(i -> i.getScheduledAt() != null && !i.getScheduledAt().isAfter(now))
                .filter(i -> scorecards.getOrDefault(i.getUuid(), List.of()).stream()
                        .noneMatch(s -> s.getInterviewerUuid().equals(viewerUuid)))
                .filter(i -> {
                    RecruitmentApplication application = applications.get(i.getApplicationUuid());
                    return application != null && applicationInPlay(application);
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

    private List<LandingTask> pendingDecisionTasks(List<RecruitmentApplication> decidable,
                                                   List<RecruitmentInterview> interviews,
                                                   Map<String, List<RecruitmentScorecard>> scorecards,
                                                   Map<String, RecruitmentPosition> positions,
                                                   Map<String, RecruitmentCandidate> candidates,
                                                   LocalDateTime now) {
        Map<String, List<RecruitmentInterview>> byApplication = interviews.stream()
                .filter(i -> i.getKind() == RecruitmentInterviewKind.ROUND)
                .collect(Collectors.groupingBy(RecruitmentInterview::getApplicationUuid));

        List<LandingTask> tasks = new ArrayList<>();
        for (RecruitmentApplication application : decidable) {
            if (!applicationInPlay(application)) {
                continue;
            }
            for (RecruitmentInterview interview
                    : byApplication.getOrDefault(application.getUuid(), List.of())) {
                RecruitmentStage roundStage = interview.roundStage();
                if (roundStage == null
                        || application.getStage().ordinal() > roundStage.ordinal()) {
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

    private List<LandingTask> idleCandidateTasks(List<RecruitmentApplication> decidable,
                                                 Map<String, RecruitmentPosition> positions,
                                                 Map<String, RecruitmentCandidate> candidates,
                                                 LocalDateTime now) {
        int thresholdDays = thresholds.candidateIdleDays();
        LocalDateTime cutoff = now.minusDays(thresholdDays);
        return decidable.stream()
                .filter(this::applicationInPlayStatic)
                .filter(a -> a.getStageEnteredAt() != null
                        && a.getStageEnteredAt().isBefore(cutoff))
                .sorted(Comparator.comparing(RecruitmentApplication::getStageEnteredAt))
                .map(a -> new LandingTask(LandingTask.TYPE_IDLE_CANDIDATE,
                        a.getCandidateUuid(), displayName(candidates.get(a.getCandidateUuid())),
                        a.getPositionUuid(), titleOf(positions.get(a.getPositionUuid())),
                        a.getUuid(), null, null, a.getStage().name(),
                        ChronoUnit.HOURS.between(a.getStageEnteredAt(), now),
                        a.getStageEnteredAt(), null))
                .toList();
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

    private List<LandingPipeline> pipelines(List<RecruitmentPosition> openPositions,
                                            Map<String, List<RecruitmentApplication>> openByPosition,
                                            LocalDateTime now) {
        int idleDays = thresholds.candidateIdleDays();
        LocalDateTime idleCutoff = now.minusDays(idleDays);
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
                            .filter(a -> a.getStageEnteredAt() != null
                                    && a.getStageEnteredAt().isBefore(idleCutoff))
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
                .limit(UPCOMING_INTERVIEWS_SIZE)
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
                            i.getKind() == RecruitmentInterviewKind.ROUND, ownSubmitted);
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
                .page(0, FEED_FETCH_SIZE)
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
                .limit(FEED_SIZE)
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

    private List<RecruitmentApplication> openApplicationsOn(
            List<RecruitmentPosition> positions) {
        if (positions.isEmpty()) {
            return List.of();
        }
        return RecruitmentApplication.list("positionUuid in ?1 and terminal is null",
                positions.stream().map(RecruitmentPosition::getUuid).toList());
    }

    /** Non-cancelled ROUND interviews for the union of own + decidable applications. */
    private List<RecruitmentInterview> roundInterviewsFor(
            List<RecruitmentInterview> ownInterviews,
            List<RecruitmentApplication> decidableApplications) {
        Set<String> applicationUuids = decidableApplications.stream()
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
