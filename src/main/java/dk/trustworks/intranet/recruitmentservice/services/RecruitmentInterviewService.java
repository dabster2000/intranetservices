package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateDocument;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateInterviewsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.DebriefResponse;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewResponse;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewScheduleRequest;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewScorecardsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.MyInterviewRow;
import dk.trustworks.intranet.recruitmentservice.dto.MyInterviewsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ScorecardResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ScorecardSubmitRequest;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewDecision;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The interview loop (ATS plan §P11, spec §5.3): interview scheduling per
 * application, blind scorecards, and the debrief view. Composes the domain
 * entities with events (every mutation appends exactly one
 * {@code INTERVIEW_*}/{@code SCORECARD_SUBMITTED} event in the same
 * transaction) and the optional Outlook bridge
 * ({@link RecruitmentCalendarService}, dark until Graph permissions land).
 *
 * <h3>The blind rule (server-side, spec §5.3)</h3>
 * A viewer sees scorecard CONTENT for an interview exactly when:
 * <ul>
 *   <li>they are an assigned interviewer AND have submitted their own; or</li>
 *   <li>they hold decision rights on the position
 *       ({@code canDecideOnApplication}) AND either every assigned
 *       interviewer has submitted (debrief-ready) or the decision has been
 *       made (the application moved past the round's stage, is terminal or
 *       HIRED, or a pending decision is recorded on the round — V519).</li>
 * </ul>
 * Everyone else — including practice leads with read access and recruiters
 * before the debrief/decision — gets progress counters only. Free-text
 * scorecard notes live in the {@code SCORECARD_SUBMITTED} event's
 * {@code pii} (never a column) and are re-attached at serve time, only for
 * unlocked viewers or the author.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentInterviewService {

    static final int MAX_INTERVIEWERS = 10;
    static final int NOTE_MAX_LENGTH = 2000;
    static final int MIN_SCORE = 1;
    static final int MAX_SCORE = 4;

    /** {@code SCORECARD_SUBMITTED.payload.origin} values (P18 — the P14 referral idiom). */
    public static final String ORIGIN_WEB = "web";
    public static final String ORIGIN_SLACK = "slack";

    @Inject
    RecruitmentEventRecorder recorder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentCalendarService calendarService;

    @Inject
    CandidateProfileReadService profileReadService;

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper objectMapper;

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    // ---- Commands -----------------------------------------------------------

    /**
     * Create (= schedule) an interview. The application must be in play —
     * terminal or HIRED applications take no more interviews. ROUND
     * interviews must map to a stage in the position's stage set; INFORMAL
     * and OFFER are schedulable at any point (spec §5.3). An offer meeting
     * is deliberately NOT gated on the application already standing at
     * OFFER: the meeting is regularly booked while the decision to make an
     * offer is being taken, and a back-move must not orphan it.
     */
    @Transactional
    public RecruitmentInterview create(RecruitmentApplication application,
                                       RecruitmentPosition position,
                                       RecruitmentCandidate candidate,
                                       InterviewCreateRequest request,
                                       UUID actor) {
        requireInPlay(application);
        if (request.kind() == RecruitmentInterviewKind.ROUND) {
            if (request.round() == null || request.round() < 1 || request.round() > 3) {
                throw new BusinessRuleViolation("A round interview needs a round between 1 and 3");
            }
            String roundStage = "INTERVIEW_" + request.round();
            if (position.getStageSet() == null || !position.getStageSet().contains(roundStage)) {
                throw new BusinessRuleViolation(
                        "Round %d is not part of this position's pipeline (%s)"
                                .formatted(request.round(), String.join(" → ",
                                        position.getStageSet() == null ? List.of() : position.getStageSet())));
            }
        } else if (request.round() != null) {
            throw new BusinessRuleViolation(
                    request.kind() == RecruitmentInterviewKind.OFFER
                            ? "An offer meeting has no round number"
                            : "An informal chat has no round number");
        }
        List<String> interviewers = normalizeInterviewers(request.interviewerUuids());

        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setApplicationUuid(application.getUuid());
        interview.setKind(request.kind());
        interview.setRound(request.kind().hasRound() ? request.round() : null);
        interview.setScheduledAt(request.scheduledAt());
        if (request.durationMinutes() != null) {
            interview.setDurationMinutes(request.durationMinutes());
        }
        interview.setInterviewerUuids(interviewers);
        interview.setLocation(trimToNull(request.location()));
        interview.setRoomEmail(trimToNull(request.roomEmail()));
        interview.setOnlineMeeting(Boolean.TRUE.equals(request.onlineMeeting()));
        if (interview.isOnlineMeeting() && interview.getRoomEmail() == null
                && interview.getLocation() == null) {
            // A pure Teams interview: give the location a sensible face
            // instead of an empty field (spec §4.1 allows "Teams").
            interview.setLocation("Microsoft Teams");
        }
        interview.setStatus(RecruitmentInterviewStatus.SCHEDULED);
        interview.persist();

        // Outlook is best-effort: a Graph failure never fails scheduling.
        // createCalendarEvent=false (F17) skips Outlook deliberately —
        // the interview was booked by hand; nobody is invited to
        // anything, and calendarSynced stays false until the reschedule
        // dialog's recovery checkbox creates the missing event.
        if (shouldCreateEventOnCreate(request.createCalendarEvent())) {
            calendarService.createEvent(interview, candidate, position).ifPresent(created -> {
                interview.setGraphEventId(created.eventId());
                interview.setGraphOrganizer(created.organizer());
                interview.setJoinUrl(created.joinUrl());
                interview.setGraphCandidateEventId(created.candidateEventId());
            });
        }

        recorder.record(interviewEvent(RecruitmentEventType.INTERVIEW_SCHEDULED,
                interview, application, position, actor)
                .payload("scheduled_at", interview.getScheduledAt().toString())
                .payload("interviewer_uuids", interviewers)
                .payload("location", interview.getLocation())
                .payload("room_email", interview.getRoomEmail())
                .payload("online_meeting", interview.isOnlineMeeting())
                .payload("calendar_synced", interview.getGraphEventId() != null));
        return interview;
    }

    /**
     * Reschedule: new time is mandatory; location/interviewers optional
     * (null = keep). Kept scorecards from removed interviewers still count
     * in the debrief.
     */
    @Transactional
    public RecruitmentInterview reschedule(RecruitmentInterview detached,
                                           RecruitmentApplication application,
                                           RecruitmentPosition position,
                                           RecruitmentCandidate candidate,
                                           InterviewScheduleRequest request,
                                           UUID actor) {
        // The resource loads outside the transaction; mutate the managed
        // row (Quarkus sessions are transaction-scoped — a detached
        // entity's changes would silently not flush).
        RecruitmentInterview interview = managed(detached);
        requireActive(interview);
        LocalDateTime previous = interview.getScheduledAt();
        List<String> previousInterviewers = List.copyOf(interview.getInterviewerUuids());
        String previousLocation = interview.getLocation();
        String previousRoomEmail = interview.getRoomEmail();
        int previousDuration = interview.getDurationMinutes();
        interview.setScheduledAt(request.scheduledAt());
        if (request.durationMinutes() != null) {
            // null = keep the current length (same convention as location).
            interview.setDurationMinutes(request.durationMinutes());
        }
        if (request.location() != null) {
            interview.setLocation(trimToNull(request.location()));
        }
        if (request.roomEmail() != null) {
            // null = keep; blank = clear the booking (same tri-state as location).
            interview.setRoomEmail(trimToNull(request.roomEmail()));
        }
        if (request.interviewerUuids() != null) {
            interview.setInterviewerUuids(normalizeInterviewers(request.interviewerUuids()));
        }
        if (Boolean.TRUE.equals(request.onlineMeeting())) {
            // One-way by design: Graph cannot cleanly strip Teams from an
            // existing event, so FALSE is treated as "keep" too.
            interview.setOnlineMeeting(true);
        }
        if (shouldCreateMissingEvent(interview.getGraphEventId(),
                request.createCalendarEvent(), calendarService.isEnabled())) {
            // The recovery path for pre-toggle and Airtable-migrated rows:
            // the interview exists, the Outlook invitation never did.
            calendarService.createEvent(interview, candidate, position).ifPresent(created -> {
                interview.setGraphEventId(created.eventId());
                interview.setGraphOrganizer(created.organizer());
                interview.setJoinUrl(created.joinUrl());
                interview.setGraphCandidateEventId(created.candidateEventId());
            });
        } else {
            calendarService.updateEvent(interview, candidate, position)
                    .ifPresent(interview::setJoinUrl);
        }

        recorder.record(interviewEvent(RecruitmentEventType.INTERVIEW_RESCHEDULED,
                interview, application, position, actor)
                .payload("previous_scheduled_at", previous != null ? previous.toString() : null)
                .payload("scheduled_at", interview.getScheduledAt().toString())
                .payload("previous_interviewer_uuids", previousInterviewers)
                .payload("interviewer_uuids", interview.getInterviewerUuids())
                .payload("previous_location", previousLocation)
                .payload("location", interview.getLocation())
                .payload("previous_room_email", previousRoomEmail)
                .payload("room_email", interview.getRoomEmail())
                .payload("previous_duration_minutes", previousDuration)
                .payload("duration_minutes", interview.getDurationMinutes())
                .payload("online_meeting", interview.isOnlineMeeting())
                .payload("calendar_synced", interview.getGraphEventId() != null));
        return interview;
    }

    /**
     * The create branch: only an EXPLICIT {@code createCalendarEvent:
     * false} skips the Outlook event (F17's deliberate no-calendar
     * interview) — null and TRUE both create, so every existing caller
     * keeps its behaviour. Static and pure, pinned by a DB-free test.
     */
    static boolean shouldCreateEventOnCreate(Boolean createCalendarEvent) {
        return !Boolean.FALSE.equals(createCalendarEvent);
    }

    /**
     * The reschedule branch: create the MISSING Outlook event instead of
     * updating? Only when the caller explicitly asked
     * ({@code createCalendarEvent: true}), the interview has no event, and
     * the Graph toggle is on. Static and pure — the branch is the recovery
     * path for every unsynced row, so it is pinned by a DB-free test.
     */
    static boolean shouldCreateMissingEvent(String graphEventId,
                                            Boolean createCalendarEvent,
                                            boolean calendarEnabled) {
        return graphEventId == null
                && Boolean.TRUE.equals(createCalendarEvent)
                && calendarEnabled;
    }

    /** Cancel — terminal for the interview; the Outlook event is cancelled too. */
    @Transactional
    public RecruitmentInterview cancel(RecruitmentInterview detached,
                                       RecruitmentApplication application,
                                       RecruitmentPosition position,
                                       UUID actor) {
        RecruitmentInterview interview = managed(detached);
        requireActive(interview);
        interview.setStatus(RecruitmentInterviewStatus.CANCELLED);
        // A cancelled interview cannot carry a pending go/no-go — the
        // round is unheld again. Part of the same mutation, no extra event.
        interview.setDecision(null);
        interview.setDecidedBy(null);
        interview.setDecidedAt(null);
        calendarService.cancelEvent(interview);

        recorder.record(interviewEvent(RecruitmentEventType.INTERVIEW_CANCELLED,
                interview, application, position, actor)
                .payload("scheduled_at",
                        interview.getScheduledAt() != null ? interview.getScheduledAt().toString() : null));
        return interview;
    }

    /**
     * Record (or overwrite) the pending go/no-go for one interview round —
     * the opt-in first half of the two-step "decide, then inform the
     * candidate" flow (pipeline sub-status, V519). Only ROUND interviews of
     * the application's CURRENT stage take a decision: past rounds are
     * already decided by the stage history, future rounds are not yet
     * anyone's to decide. The stage move (or terminal) that follows
     * consumes the record; moving without recording first remains fully
     * supported and implies the decision, as it always did.
     * <p>
     * Recording unlocks the debrief for decision-tier viewers ("after the
     * decision", class javadoc) — deliberately: today the same unlock
     * happens the moment the card moves, and an owner who decides before
     * every scorecard is in could always effect that by moving the card.
     */
    @Transactional
    public RecruitmentInterview recordDecision(RecruitmentInterview detached,
                                               RecruitmentApplication application,
                                               RecruitmentPosition position,
                                               RecruitmentInterviewDecision decision,
                                               UUID actor) {
        Objects.requireNonNull(decision, "decision must not be null");
        RecruitmentInterview interview = managed(detached);
        requireActive(interview);
        requireInPlay(application);
        if (interview.getKind() != RecruitmentInterviewKind.ROUND) {
            throw new BusinessRuleViolation(
                    "Only round interviews take a decision — informal chats and offer meetings do not gate the pipeline");
        }
        if (interview.roundStage() != application.getStage()) {
            throw new BusinessRuleViolation(
                    "The decision belongs to the current round — this application stands at %s, not %s"
                            .formatted(application.getStage(), interview.roundStage()));
        }
        RecruitmentInterviewDecision previous = interview.getDecision();
        interview.setDecision(decision);
        interview.setDecidedBy(actor.toString());
        interview.setDecidedAt(LocalDateTime.now(ZoneOffset.UTC));

        recorder.record(interviewEvent(RecruitmentEventType.INTERVIEW_DECISION_RECORDED,
                interview, application, position, actor)
                .payload("decision", decision.name())
                .payload("previous_decision", previous != null ? previous.name() : null)
                .payload("stage", application.getStage().name()));
        return interview;
    }

    /**
     * Withdraw a pending decision — the undo path (NOT the normal
     * completion; the consuming stage move clears the columns under its own
     * event). Idempotent: clearing a round with nothing pending is a no-op
     * and appends nothing.
     */
    @Transactional
    public RecruitmentInterview clearDecision(RecruitmentInterview detached,
                                              RecruitmentApplication application,
                                              RecruitmentPosition position,
                                              UUID actor) {
        RecruitmentInterview interview = managed(detached);
        RecruitmentInterviewDecision previous = interview.getDecision();
        if (previous == null) {
            return interview;
        }
        interview.setDecision(null);
        interview.setDecidedBy(null);
        interview.setDecidedAt(null);

        recorder.record(interviewEvent(RecruitmentEventType.INTERVIEW_DECISION_CLEARED,
                interview, application, position, actor)
                .payload("previous_decision", previous.name())
                .payload("stage", application.getStage().name()));
        return interview;
    }

    /**
     * Submit the caller's own blind scorecard. Exactly one per interviewer
     * per interview (DB UNIQUE beneath); scores must cover the position's
     * template codes; INFORMAL takes no scorecard (spec §5.3 — a plain note
     * suffices). The first submission marks the interview HELD.
     */
    @Transactional
    public RecruitmentScorecard submitScorecard(RecruitmentInterview detached,
                                                RecruitmentApplication application,
                                                RecruitmentPosition position,
                                                ScorecardSubmitRequest request,
                                                UUID actor) {
        return submitScorecard(detached, application, position, request, actor, ORIGIN_WEB);
    }

    /**
     * Origin-aware variant (P18): the Slack scorecard modal executes THIS
     * SAME command with {@link #ORIGIN_SLACK}, so the timeline shows
     * provenance and reports can measure Slack-vs-web adoption
     * ({@code payload.origin} — the P14 referral precedent). Everything
     * else is byte-identical between the two fronts.
     */
    @Transactional
    public RecruitmentScorecard submitScorecard(RecruitmentInterview detached,
                                                RecruitmentApplication application,
                                                RecruitmentPosition position,
                                                ScorecardSubmitRequest request,
                                                UUID actor,
                                                String origin) {
        RecruitmentInterview interview = managed(detached);
        requireActive(interview);
        if (!interview.getKind().takesScorecard()) {
            throw new BusinessRuleViolation(
                    (interview.getKind() == RecruitmentInterviewKind.OFFER
                            ? "An offer meeting takes no scorecard"
                            : "An informal chat takes no scorecard")
                            + " — add a note on the candidate instead");
        }
        if (!interview.isAssigned(actor.toString())) {
            // The resource has already 404'd viewers without candidate
            // access; a visible-but-unassigned viewer is a real 403.
            throw new BusinessRuleViolation("Only assigned interviewers can submit a scorecard");
        }
        if (RecruitmentScorecard.count("interviewUuid = ?1 and interviewerUuid = ?2",
                interview.getUuid(), actor.toString()) > 0) {
            throw new BusinessRuleViolation(
                    "You have already submitted your scorecard for this interview");
        }
        Map<String, Integer> scores = validateScores(request.scores(), position);
        if (request.recommendation() == null) {
            throw new BusinessRuleViolation("A recommendation is required");
        }
        String notes = trimToNull(request.notes());
        if (notes != null && notes.length() > NOTE_MAX_LENGTH) {
            throw new BusinessRuleViolation(
                    "Notes must be at most " + NOTE_MAX_LENGTH + " characters");
        }

        RecruitmentScorecard scorecard = new RecruitmentScorecard();
        scorecard.setInterviewUuid(interview.getUuid());
        scorecard.setInterviewerUuid(actor.toString());
        scorecard.setScores(scores);
        scorecard.setRecommendation(request.recommendation());
        scorecard.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC));
        scorecard.persist();

        if (interview.getStatus() == RecruitmentInterviewStatus.SCHEDULED) {
            interview.setStatus(RecruitmentInterviewStatus.HELD);
        }

        // Blind by construction: neither scores nor the recommendation ride
        // on the event — the debrief reads the table through the blind
        // filter. Free text goes to pii only (spec §4.1).
        RecruitmentEventBuilder event = interviewEvent(RecruitmentEventType.SCORECARD_SUBMITTED,
                interview, application, position, actor)
                .payload("scorecard_uuid", scorecard.getUuid())
                .payload("origin", ORIGIN_SLACK.equals(origin) ? ORIGIN_SLACK : ORIGIN_WEB);
        if (notes != null) {
            event.pii("notes", notes);
        }
        recorder.record(event);
        return scorecard;
    }

    // ---- Reads --------------------------------------------------------------

    /**
     * The blind-filtered scorecard view of one interview for this viewer.
     * Progress counters are always included; content only per the blind
     * rule (class javadoc).
     */
    public InterviewScorecardsResponse scorecardsFor(String viewerUuid,
                                                     RecruitmentInterview interview,
                                                     RecruitmentApplication application,
                                                     RecruitmentPosition position) {
        List<RecruitmentScorecard> all = RecruitmentScorecard.list(
                "interviewUuid = ?1 order by submittedAt", interview.getUuid());
        Map<String, String> names = resolveNames(namesNeeded(interview, all));
        return blindFiltered(viewerUuid, interview, application, position, all, names);
    }

    /**
     * The debrief for an application (spec §5.3): every non-cancelled ROUND
     * interview in round order with its blind-filtered scorecards.
     */
    public DebriefResponse debrief(String viewerUuid,
                                   RecruitmentApplication application,
                                   RecruitmentPosition position) {
        List<RecruitmentInterview> rounds = RecruitmentInterview.list(
                "applicationUuid = ?1 and kind = ?2 and status <> ?3 order by round, createdAt",
                application.getUuid(), RecruitmentInterviewKind.ROUND,
                RecruitmentInterviewStatus.CANCELLED);
        Map<String, List<RecruitmentScorecard>> scorecardsByInterview = scorecardsOf(rounds);
        Set<String> nameUuids = new LinkedHashSet<>();
        rounds.forEach(i -> nameUuids.addAll(i.getInterviewerUuids()));
        scorecardsByInterview.values().forEach(list ->
                list.forEach(s -> nameUuids.add(s.getInterviewerUuid())));
        Map<String, String> names = resolveNames(nameUuids);
        RecruitmentCandidate candidate =
                RecruitmentCandidate.findById(application.getCandidateUuid());
        boolean viewerCanDecide = visibility.canDecideOnApplication(viewerUuid, position);

        List<DebriefResponse.DebriefEntry> entries = rounds.stream()
                .map(interview -> {
                    List<RecruitmentScorecard> cards =
                            scorecardsByInterview.getOrDefault(interview.getUuid(), List.of());
                    InterviewScorecardsResponse filtered = blindFiltered(
                            viewerUuid, interview, application, position, cards, names);
                    return new DebriefResponse.DebriefEntry(
                            toResponse(viewerUuid, interview, application, position, candidate,
                                    cards, names, viewerCanDecide),
                            filtered);
                })
                .toList();
        return new DebriefResponse(application.getUuid(), entries);
    }

    /**
     * All interviews across the candidate's applications that the viewer
     * may see — the profile Interviews tab. Applications are filtered per
     * position visibility; an assigned interviewer additionally sees their
     * own interviews even without position visibility (the P11 interviewer
     * grant).
     */
    public CandidateInterviewsResponse listForCandidate(String viewerUuid,
                                                        RecruitmentCandidate candidate) {
        List<RecruitmentApplication> allApplications = RecruitmentApplication.list(
                "candidateUuid = ?1 order by createdAt", candidate.getUuid());
        List<RecruitmentApplication> visibleApplications =
                visibility.filterApplications(viewerUuid, candidate.getUuid());
        Set<String> visibleApplicationUuids = visibleApplications.stream()
                .map(RecruitmentApplication::getUuid)
                .collect(Collectors.toSet());
        Map<String, RecruitmentApplication> applicationsByUuid = allApplications.stream()
                .collect(Collectors.toMap(RecruitmentApplication::getUuid, a -> a));

        List<RecruitmentInterview> interviews = allApplications.isEmpty() ? List.of()
                : RecruitmentInterview.list(
                        "applicationUuid in ?1 order by scheduledAt desc",
                        allApplications.stream().map(RecruitmentApplication::getUuid).toList());
        List<RecruitmentInterview> visible = interviews.stream()
                .filter(i -> visibleApplicationUuids.contains(i.getApplicationUuid())
                        || i.isAssigned(viewerUuid))
                .toList();

        Map<String, RecruitmentPosition> positions = positionsOf(allApplications);
        Map<String, List<RecruitmentScorecard>> scorecardsByInterview = scorecardsOf(visible);
        Set<String> nameUuids = new LinkedHashSet<>();
        visible.forEach(i -> nameUuids.addAll(i.getInterviewerUuids()));
        Map<String, String> names = resolveNames(nameUuids);

        // Decision rights per position, resolved once per distinct position
        // — not per interview row (the lookup walks role/practice tables).
        Map<String, Boolean> canDecideByPosition = new HashMap<>();
        List<InterviewResponse> rows = visible.stream()
                .map(interview -> {
                    RecruitmentApplication application =
                            applicationsByUuid.get(interview.getApplicationUuid());
                    RecruitmentPosition position = positions.get(application.getPositionUuid());
                    boolean viewerCanDecide = canDecideByPosition.computeIfAbsent(
                            position.getUuid(),
                            uuid -> visibility.canDecideOnApplication(viewerUuid, position));
                    return toResponse(viewerUuid, interview, application, position, candidate,
                            scorecardsByInterview.getOrDefault(interview.getUuid(), List.of()),
                            names, viewerCanDecide);
                })
                .toList();
        return new CandidateInterviewsResponse(rows, rows.size());
    }

    /**
     * The caller's own upcoming + recent interviews with the kit (spec §6.1
     * {@code /recruitment/interviews}): candidate, position, focus areas
     * (= the position's scorecard template) and the latest CV handle.
     * Soonest first, unscheduled last; cancelled excluded.
     */
    public MyInterviewsResponse listMine(String viewerUuid) {
        @SuppressWarnings("unchecked")
        List<String> interviewUuids = em.createNativeQuery("""
                        SELECT i.uuid FROM recruitment_interviews i
                        WHERE i.status <> 'CANCELLED'
                          AND JSON_CONTAINS(i.interviewer_uuids, JSON_QUOTE(:viewer))
                        """)
                .setParameter("viewer", viewerUuid)
                .getResultList();
        if (interviewUuids.isEmpty()) {
            return new MyInterviewsResponse(List.of(), 0);
        }
        List<RecruitmentInterview> interviews =
                RecruitmentInterview.list("uuid in ?1", interviewUuids);
        List<RecruitmentApplication> applications = RecruitmentApplication.list("uuid in ?1",
                interviews.stream().map(RecruitmentInterview::getApplicationUuid).distinct().toList());
        Map<String, RecruitmentApplication> applicationsByUuid = applications.stream()
                .collect(Collectors.toMap(RecruitmentApplication::getUuid, a -> a));
        Map<String, RecruitmentPosition> positions = positionsOf(applications);
        Map<String, RecruitmentCandidate> candidates = candidatesOf(applications);
        Map<String, List<RecruitmentScorecard>> scorecardsByInterview = scorecardsOf(interviews);
        Set<String> nameUuids = new LinkedHashSet<>();
        interviews.forEach(i -> nameUuids.addAll(i.getInterviewerUuids()));
        Map<String, String> names = resolveNames(nameUuids);

        List<MyInterviewRow> rows = interviews.stream()
                .sorted(Comparator.comparing(RecruitmentInterview::getScheduledAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(interview -> {
                    RecruitmentApplication application =
                            applicationsByUuid.get(interview.getApplicationUuid());
                    RecruitmentPosition position = positions.get(application.getPositionUuid());
                    RecruitmentCandidate candidate = candidates.get(application.getCandidateUuid());
                    boolean ownSubmitted = scorecardsByInterview
                            .getOrDefault(interview.getUuid(), List.of()).stream()
                            .anyMatch(s -> s.getInterviewerUuid().equals(viewerUuid));
                    List<String> coInterviewers = interview.getInterviewerUuids().stream()
                            .filter(uuid -> !uuid.equals(viewerUuid))
                            .map(uuid -> names.getOrDefault(uuid, "Unknown"))
                            .toList();
                    // Pipeline stage is withheld from viewers whose only key
                    // to this candidate is the interview assignment (go-live
                    // decision D10): an interviewer forms a view without
                    // knowing where the process stands. Role holders — who
                    // can open the full profile anyway — keep it.
                    String stage = visibility.canReadCandidateProfile(viewerUuid, candidate)
                            ? application.getStage().name()
                            : null;
                    return new MyInterviewRow(
                            interview.getUuid(),
                            application.getUuid(),
                            candidate.getUuid(),
                            fullName(candidate),
                            position.getUuid(),
                            position.getTitle(),
                            interview.getKind(),
                            interview.getRound(),
                            interview.getScheduledAt(),
                            interview.getLocation(),
                            interview.getStatus(),
                            stage,
                            focusAreas(position),
                            latestCvFileUuid(candidate.getUuid()),
                            interview.getKind().takesScorecard(),
                            ownSubmitted,
                            coInterviewers);
                })
                .toList();
        return new MyInterviewsResponse(rows, rows.size());
    }

    /**
     * May the viewer access this interview's read surfaces? Either the
     * application is visible to them (position rule) or they are an
     * assigned interviewer (P11 grant). Callers answer 404 when false.
     */
    public boolean canAccessInterview(String viewerUuid,
                                      RecruitmentInterview interview,
                                      RecruitmentApplication application) {
        return interview.isAssigned(viewerUuid)
                || visibility.canReadApplication(viewerUuid, application);
    }

    // ---- The blind rule -------------------------------------------------------

    private InterviewScorecardsResponse blindFiltered(String viewerUuid,
                                                      RecruitmentInterview interview,
                                                      RecruitmentApplication application,
                                                      RecruitmentPosition position,
                                                      List<RecruitmentScorecard> all,
                                                      Map<String, String> names) {
        boolean ownSubmitted = all.stream()
                .anyMatch(s -> s.getInterviewerUuid().equals(viewerUuid));
        boolean unlocked = isUnlockedFor(viewerUuid, interview, application, position,
                all, ownSubmitted);
        List<RecruitmentScorecard> served = unlocked
                ? all
                : all.stream().filter(s -> s.getInterviewerUuid().equals(viewerUuid)).toList();
        Map<String, String> notes = served.isEmpty() ? Map.of()
                : notesFor(application, served.stream().map(RecruitmentScorecard::getUuid)
                        .collect(Collectors.toSet()));
        List<ScorecardResponse> cards = served.stream()
                .map(s -> new ScorecardResponse(
                        s.getUuid(),
                        s.getInterviewUuid(),
                        s.getInterviewerUuid(),
                        names.getOrDefault(s.getInterviewerUuid(), "Unknown"),
                        s.getScores(),
                        s.getRecommendation(),
                        s.getSubmittedAt(),
                        notes.get(s.getUuid())))
                .toList();
        return new InterviewScorecardsResponse(
                interview.getUuid(),
                unlocked,
                ownSubmitted,
                all.size(),
                interview.getInterviewerUuids().size(),
                interviewerInfos(interview, all, names),
                cards);
    }

    /**
     * The blind rule's unlock decision (class javadoc). ADMIN unlocks via
     * {@code canDecideOnApplication} + the decision/all-in condition like
     * every other decision-tier viewer — blindness applies to admins too
     * until the debrief is ready.
     */
    private boolean isUnlockedFor(String viewerUuid,
                                  RecruitmentInterview interview,
                                  RecruitmentApplication application,
                                  RecruitmentPosition position,
                                  List<RecruitmentScorecard> all,
                                  boolean ownSubmitted) {
        if (interview.isAssigned(viewerUuid) && ownSubmitted) {
            return true;
        }
        if (!visibility.canDecideOnApplication(viewerUuid, position)) {
            return false;
        }
        return allAssignedSubmitted(interview, all) || decisionMade(application, interview);
    }

    /**
     * "Debrief ready" = every assigned interviewer has submitted. Public
     * because P12's Slack reactor must compute debrief-readiness with this
     * exact rule (findings §P11 carry-over).
     */
    public static boolean allAssignedSubmitted(RecruitmentInterview interview,
                                               List<RecruitmentScorecard> all) {
        List<String> assigned = interview.getInterviewerUuids();
        if (assigned == null || assigned.isEmpty()) {
            return false;
        }
        Set<String> submitted = all.stream()
                .map(RecruitmentScorecard::getInterviewerUuid)
                .collect(Collectors.toSet());
        return submitted.containsAll(assigned);
    }

    /**
     * "After decision" = the application has moved past this round's stage
     * (canonical order), has left the pipeline (terminal / HIRED), or the
     * round carries a pending recorded decision (V519 — recording IS the
     * decision; the move that follows merely completes it). A back-move
     * re-locks — deliberate: the rule keys on current state, a rewound
     * round is a live round again, and every stage move clears pending
     * decisions, so a stale record can never hold the lock open.
     */
    static boolean decisionMade(RecruitmentApplication application,
                                RecruitmentInterview interview) {
        if (application.getTerminal() != null
                || application.getStage() == RecruitmentStage.HIRED) {
            return true;
        }
        if (interview.getDecidedAt() != null) {
            return true;
        }
        RecruitmentStage roundStage = interview.roundStage();
        return roundStage != null && application.getStage().ordinal() > roundStage.ordinal();
    }

    // ---- Shaping ---------------------------------------------------------------

    private InterviewResponse toResponse(String viewerUuid,
                                         RecruitmentInterview interview,
                                         RecruitmentApplication application,
                                         RecruitmentPosition position,
                                         RecruitmentCandidate candidate,
                                         List<RecruitmentScorecard> scorecards,
                                         Map<String, String> names,
                                         boolean viewerCanDecide) {
        boolean ownSubmitted = scorecards.stream()
                .anyMatch(s -> s.getInterviewerUuid().equals(viewerUuid));
        return new InterviewResponse(
                interview.getUuid(),
                application.getUuid(),
                application.getCandidateUuid(),
                position.getUuid(),
                position.getTitle(),
                interview.getKind(),
                interview.getRound(),
                interview.getScheduledAt(),
                interview.getDurationMinutes(),
                interview.getLocation(),
                interview.getRoomEmail(),
                interview.getStatus(),
                interviewerInfos(interview, scorecards, names),
                interview.getKind().takesScorecard(),
                scorecards.size(),
                interview.getInterviewerUuids().size(),
                ownSubmitted,
                interview.isAssigned(viewerUuid),
                isUnlockedFor(viewerUuid, interview, application, position, scorecards, ownSubmitted),
                interview.getGraphEventId() != null,
                candidate != null && candidate.getEmail() != null
                        && !candidate.getEmail().isBlank(),
                interview.isOnlineMeeting(),
                interview.getJoinUrl(),
                // A pending decision is decider-only until the candidate is
                // informed — everyone else learns it when the card moves.
                viewerCanDecide ? interview.getDecision() : null,
                viewerCanDecide ? interview.getDecidedAt() : null);
    }

    private static List<InterviewResponse.InterviewerInfo> interviewerInfos(
            RecruitmentInterview interview,
            List<RecruitmentScorecard> scorecards,
            Map<String, String> names) {
        Set<String> submitted = scorecards.stream()
                .map(RecruitmentScorecard::getInterviewerUuid)
                .collect(Collectors.toSet());
        return interview.getInterviewerUuids().stream()
                .map(uuid -> new InterviewResponse.InterviewerInfo(
                        uuid, names.getOrDefault(uuid, "Unknown"), submitted.contains(uuid)))
                .toList();
    }

    private List<ScorecardAttribute> focusAreas(RecruitmentPosition position) {
        List<ScorecardAttribute> template = position.getScorecardTemplate();
        return template != null ? template : List.of();
    }

    /** The latest CV on file, reusing the P8 documents derivation. */
    private String latestCvFileUuid(String candidateUuid) {
        return profileReadService.documents(candidateUuid).documents().stream()
                .filter(d -> "CV".equals(d.kind()))
                .map(CandidateDocument::fileUuid)
                .findFirst()
                .orElse(null);
    }

    // ---- Validation helpers ------------------------------------------------------

    /** Re-load the row inside the current transaction (see reschedule javadoc). */
    private static RecruitmentInterview managed(RecruitmentInterview detached) {
        RecruitmentInterview interview = RecruitmentInterview.findById(detached.getUuid());
        if (interview == null) {
            throw new BusinessRuleViolation("Interview no longer exists: " + detached.getUuid());
        }
        return interview;
    }

    private void requireInPlay(RecruitmentApplication application) {
        if (application.getTerminal() != null
                || application.getStage() == RecruitmentStage.HIRED) {
            throw new BusinessRuleViolation(
                    "This application has left the pipeline — no more interviews can be scheduled");
        }
    }

    private static void requireActive(RecruitmentInterview interview) {
        if (!interview.isActive()) {
            throw new BusinessRuleViolation("This interview has been cancelled");
        }
    }

    /** Dedupe, cap, and verify every interviewer is an existing user. */
    private List<String> normalizeInterviewers(List<String> interviewerUuids) {
        if (interviewerUuids == null || interviewerUuids.isEmpty()) {
            throw new BusinessRuleViolation("At least one interviewer is required");
        }
        List<String> distinct = interviewerUuids.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            throw new BusinessRuleViolation("At least one interviewer is required");
        }
        if (distinct.size() > MAX_INTERVIEWERS) {
            throw new BusinessRuleViolation(
                    "At most " + MAX_INTERVIEWERS + " interviewers per interview");
        }
        Number existing = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM user WHERE uuid IN (:uuids)")
                .setParameter("uuids", distinct)
                .getSingleResult();
        if (existing.intValue() != distinct.size()) {
            throw new BusinessRuleViolation("Unknown interviewer — pick existing employees");
        }
        return distinct;
    }

    private Map<String, Integer> validateScores(Map<String, Integer> scores,
                                                RecruitmentPosition position) {
        List<ScorecardAttribute> template = position.getScorecardTemplate();
        if (template == null || template.isEmpty()) {
            throw new BusinessRuleViolation("This position has no scorecard template");
        }
        if (scores == null || scores.isEmpty()) {
            throw new BusinessRuleViolation("Scores are required");
        }
        Map<String, Integer> validated = new LinkedHashMap<>();
        for (ScorecardAttribute attribute : template) {
            Integer score = scores.get(attribute.code());
            if (score == null) {
                throw new BusinessRuleViolation(
                        "Missing score for '" + attribute.label() + "'");
            }
            if (score < MIN_SCORE || score > MAX_SCORE) {
                throw new BusinessRuleViolation(
                        "Scores are 1–4; got " + score + " for '" + attribute.label() + "'");
            }
            validated.put(attribute.code(), score);
        }
        Set<String> templateCodes = template.stream()
                .map(ScorecardAttribute::code)
                .collect(Collectors.toSet());
        for (String code : scores.keySet()) {
            if (!templateCodes.contains(code)) {
                throw new BusinessRuleViolation(
                        "Unknown scorecard attribute: " + code);
            }
        }
        return validated;
    }

    // ---- Notes from events -------------------------------------------------------

    /**
     * Scorecard notes live in {@code SCORECARD_SUBMITTED} events' pii —
     * re-attach them by {@code payload.scorecard_uuid}, one query per
     * application (bounded: a handful of scorecards each).
     */
    private Map<String, String> notesFor(RecruitmentApplication application,
                                         Set<String> scorecardUuids) {
        List<RecruitmentEvent> events = RecruitmentEvent.list(
                "eventType = ?1 and applicationUuid = ?2",
                RecruitmentEventType.SCORECARD_SUBMITTED, application.getUuid());
        Map<String, String> notes = new HashMap<>();
        for (RecruitmentEvent event : events) {
            Map<String, Object> payload = parseJson(event.getPayload());
            Object scorecardUuid = payload.get("scorecard_uuid");
            if (scorecardUuid instanceof String uuid && scorecardUuids.contains(uuid)) {
                Map<String, Object> pii = parseJson(event.getPii());
                Object note = pii.get("notes");
                if (note instanceof String text && !text.isBlank()) {
                    notes.put(uuid, text);
                }
            }
        }
        return notes;
    }

    // ---- Batched lookups -----------------------------------------------------------

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

    private static Map<String, RecruitmentPosition> positionsOf(
            List<RecruitmentApplication> applications) {
        if (applications.isEmpty()) {
            return Map.of();
        }
        return RecruitmentPosition.<RecruitmentPosition>list("uuid in ?1",
                        applications.stream().map(RecruitmentApplication::getPositionUuid)
                                .distinct().toList())
                .stream()
                .collect(Collectors.toMap(RecruitmentPosition::getUuid, p -> p));
    }

    private static Map<String, RecruitmentCandidate> candidatesOf(
            List<RecruitmentApplication> applications) {
        if (applications.isEmpty()) {
            return Map.of();
        }
        return RecruitmentCandidate.<RecruitmentCandidate>list("uuid in ?1",
                        applications.stream().map(RecruitmentApplication::getCandidateUuid)
                                .distinct().toList())
                .stream()
                .collect(Collectors.toMap(RecruitmentCandidate::getUuid, c -> c));
    }

    /** Batched user-name resolution — the timeline's no-N+1 idiom. */
    @SuppressWarnings("unchecked")
    private Map<String, String> resolveNames(Collection<String> userUuids) {
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

    private static Set<String> namesNeeded(RecruitmentInterview interview,
                                           List<RecruitmentScorecard> scorecards) {
        Set<String> uuids = new LinkedHashSet<>(interview.getInterviewerUuids());
        scorecards.forEach(s -> uuids.add(s.getInterviewerUuid()));
        return uuids;
    }

    // ---- Event skeleton --------------------------------------------------------------

    /**
     * All three subjects + acting user + the interview's structural facts;
     * {@code visibility=CIRCLE} on partner track (module rule — the
     * timeline applies the same hard filter as the state tables).
     */
    private static RecruitmentEventBuilder interviewEvent(RecruitmentEventType type,
                                                          RecruitmentInterview interview,
                                                          RecruitmentApplication application,
                                                          RecruitmentPosition position,
                                                          UUID actor) {
        RecruitmentEventBuilder builder = RecruitmentEventBuilder.event(type)
                .candidate(application.getCandidateUuid())
                .application(application.getUuid())
                .position(application.getPositionUuid())
                .actorUser(actor.toString())
                .payload("interview_uuid", interview.getUuid())
                .payload("kind", interview.getKind().name())
                .payload("round", interview.getRound());
        if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            builder.visibility(RecruitmentEventVisibility.CIRCLE);
        }
        return builder;
    }

    private static String fullName(RecruitmentCandidate candidate) {
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        return (first + " " + last).trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            log.warn("Unparseable recruitment event JSON section — returning empty object");
            return Map.of();
        }
    }
}
