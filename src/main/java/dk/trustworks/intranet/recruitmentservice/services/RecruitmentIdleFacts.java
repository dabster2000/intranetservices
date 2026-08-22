package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Batched fact-gathering for {@link RecruitmentIdleRule} — the DB half of the
 * rule, kept apart so the rule itself stays a pure function.
 *
 * <p>Exists so the landing page, the landing pipelines' idle badge and the
 * nightly SLA sweep cannot drift apart: this whole change was needed because
 * the page and the sweep each carried their own copy of "idle", and neither
 * knew about interviews already in the calendar. One loader, one rule, three
 * callers.
 *
 * <p>Every lookup is a single batched query over the whole application slice
 * — the module's no-N+1 rule. Callers that already hold the interviews and
 * scorecards (the landing page loads both for its task rows) pass them in and
 * pay only three extra queries; callers that do not (the sweep) let the
 * loader fetch them.
 */
@ApplicationScoped
public class RecruitmentIdleFacts {

    @Inject
    EntityManager em;

    /**
     * Facts for every given application, fetching interviews and scorecards
     * as needed. Used by the nightly SLA sweep.
     */
    public Map<String, RecruitmentIdleRule.Facts> load(
            List<RecruitmentApplication> applications,
            Map<String, RecruitmentPosition> positions,
            LocalDateTime now) {
        return load(applications, positions, null, null, now);
    }

    /**
     * Facts for every given application.
     *
     * @param preloadedInterviews  non-cancelled interviews already in hand
     *                             (any kind), or {@code null} to fetch them
     * @param preloadedScorecards  scorecards grouped by interview uuid that
     *                             cover {@code preloadedInterviews}, or {@code null} to fetch
     */
    public Map<String, RecruitmentIdleRule.Facts> load(
            List<RecruitmentApplication> applications,
            Map<String, RecruitmentPosition> positions,
            List<RecruitmentInterview> preloadedInterviews,
            Map<String, List<RecruitmentScorecard>> preloadedScorecards,
            LocalDateTime now) {
        if (applications == null || applications.isEmpty()) {
            return Map.of();
        }
        List<String> applicationUuids = applications.stream()
                .map(RecruitmentApplication::getUuid)
                .distinct()
                .toList();

        List<RecruitmentInterview> interviews = preloadedInterviews != null
                ? preloadedInterviews
                : RecruitmentInterview.list("applicationUuid in ?1 and status <> ?2",
                        applicationUuids, RecruitmentInterviewStatus.CANCELLED);
        Map<String, List<RecruitmentScorecard>> scorecards = preloadedScorecards != null
                ? preloadedScorecards
                : scorecardsOf(interviews);

        Map<String, RecruitmentApplication> byUuid = applications.stream()
                .collect(Collectors.toMap(RecruitmentApplication::getUuid, Function.identity(),
                        (a, b) -> a));

        Set<String> booked = booked(interviews, now);
        Set<String> awaitingCards = roundsWhere(interviews, scorecards, byUuid, now, false);
        Set<String> debriefReady = roundsWhere(interviews, scorecards, byUuid, now, true);
        Set<String> scheduling = schedulingInFlight(applicationUuids);
        Set<String> queuedEmails = pendingEmails(applicationUuids);
        Map<String, LocalDateTime> progress = lastProgress(applicationUuids);

        Map<String, RecruitmentIdleRule.Facts> result = new HashMap<>(applications.size());
        for (RecruitmentApplication a : applications) {
            RecruitmentPosition position = positions == null ? null
                    : positions.get(a.getPositionUuid());
            result.put(a.getUuid(), new RecruitmentIdleRule.Facts(
                    position != null && position.getStatus() == RecruitmentPositionStatus.OPEN,
                    booked.contains(a.getUuid()),
                    scheduling.contains(a.getUuid()),
                    awaitingCards.contains(a.getUuid()),
                    // A round still missing a card outranks a sibling round
                    // that is complete: the blocker is the missing card.
                    debriefReady.contains(a.getUuid()) && !awaitingCards.contains(a.getUuid()),
                    queuedEmails.contains(a.getUuid()),
                    RecruitmentIdleRule.lastProgressAt(a.getStageEnteredAt(),
                            progress.get(a.getUuid()))));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Individual facts
    // ------------------------------------------------------------------

    /** Applications whose next step already sits in a calendar (any interview kind). */
    static Set<String> booked(Collection<RecruitmentInterview> interviews, LocalDateTime now) {
        return interviews.stream()
                .filter(i -> i.getStatus() != RecruitmentInterviewStatus.CANCELLED)
                .filter(i -> i.getScheduledAt() != null && i.getScheduledAt().isAfter(now))
                .map(RecruitmentInterview::getApplicationUuid)
                .collect(Collectors.toSet());
    }

    /**
     * Applications with a held, still-live round whose assigned interviewers
     * have {@code allSubmitted} — false gives "waiting on a colleague's
     * scorecard", true gives "debrief ready".
     *
     * <p>The empty-assignment guard is load-bearing: imported rounds carry
     * {@code interviewer_uuids = []}, and
     * {@link RecruitmentInterviewService#allAssignedSubmitted} answers
     * {@code false} for them (nothing to wait for is not "everyone
     * answered"). Without the guard an unassigned past round would mute its
     * candidate forever — the exact failure this rule exists to remove.
     */
    static Set<String> roundsWhere(Collection<RecruitmentInterview> interviews,
                                   Map<String, List<RecruitmentScorecard>> scorecards,
                                   Map<String, RecruitmentApplication> applications,
                                   LocalDateTime now,
                                   boolean allSubmitted) {
        return interviews.stream()
                .filter(i -> i.getKind() == RecruitmentInterviewKind.ROUND)
                .filter(i -> i.getStatus() != RecruitmentInterviewStatus.CANCELLED)
                .filter(i -> i.getScheduledAt() != null && !endOf(i).isAfter(now))
                .filter(i -> i.getInterviewerUuids() != null && !i.getInterviewerUuids().isEmpty())
                .filter(i -> RecruitmentInterviewService.allAssignedSubmitted(i,
                        scorecards.getOrDefault(i.getUuid(), List.of())) == allSubmitted)
                .filter(i -> {
                    RecruitmentApplication a = applications.get(i.getApplicationUuid());
                    return a != null && !RecruitmentInterviewService.decisionMade(a, i);
                })
                .map(RecruitmentInterview::getApplicationUuid)
                .collect(Collectors.toSet());
    }

    /** Applications with a live Method B scheduling request (spec §15). */
    private Set<String> schedulingInFlight(List<String> applicationUuids) {
        List<SchedulingRequestStatus> live = Arrays.stream(SchedulingRequestStatus.values())
                .filter(status -> !status.isTerminal())
                .toList();
        return RecruitmentSchedulingRequest.<RecruitmentSchedulingRequest>list(
                        "applicationUuid in ?1 and status in ?2", applicationUuids, live)
                .stream()
                .map(RecruitmentSchedulingRequest::getApplicationUuid)
                .collect(Collectors.toSet());
    }

    /** Applications whose next contact is already drafted in the P15 review queue. */
    private Set<String> pendingEmails(List<String> applicationUuids) {
        return RecruitmentPendingEmail.<RecruitmentPendingEmail>list(
                        "applicationUuid in ?1 and status = ?2", applicationUuids,
                        RecruitmentPendingEmailStatus.PENDING)
                .stream()
                .map(RecruitmentPendingEmail::getApplicationUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Newest {@link RecruitmentIdleRule#PROGRESS_EVENTS} timestamp per
     * application — one grouped query, never one per row. Served by
     * {@code idx_re_application_seq} (V522).
     */
    private Map<String, LocalDateTime> lastProgress(List<String> applicationUuids) {
        List<Object[]> rows = em.createQuery(
                        "select e.applicationUuid, max(e.occurredAt) from RecruitmentEvent e "
                                + "where e.applicationUuid in :apps and e.eventType in :types "
                                + "group by e.applicationUuid", Object[].class)
                .setParameter("apps", applicationUuids)
                .setParameter("types", RecruitmentIdleRule.PROGRESS_EVENTS)
                .getResultList();
        Map<String, LocalDateTime> result = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            result.put((String) row[0], (LocalDateTime) row[1]);
        }
        return result;
    }

    private static Map<String, List<RecruitmentScorecard>> scorecardsOf(
            Collection<RecruitmentInterview> interviews) {
        if (interviews.isEmpty()) {
            return Map.of();
        }
        return RecruitmentScorecard.<RecruitmentScorecard>list("interviewUuid in ?1",
                        interviews.stream().map(RecruitmentInterview::getUuid).toList())
                .stream()
                .collect(Collectors.groupingBy(RecruitmentScorecard::getInterviewUuid));
    }

    /** When the meeting is actually over — the start plus its booked duration. */
    static LocalDateTime endOf(RecruitmentInterview interview) {
        return interview.getScheduledAt().plusMinutes(Math.max(0, interview.getDurationMinutes()));
    }
}
