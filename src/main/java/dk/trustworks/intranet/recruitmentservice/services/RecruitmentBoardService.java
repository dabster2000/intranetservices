package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.BoardCard;
import dk.trustworks.intranet.recruitmentservice.dto.BoardCardSubStatus;
import dk.trustworks.intranet.recruitmentservice.dto.BoardColumn;
import dk.trustworks.intranet.recruitmentservice.dto.BoardPositionSummary;
import dk.trustworks.intranet.recruitmentservice.dto.BoardTerminalEntry;
import dk.trustworks.intranet.recruitmentservice.dto.BoardTerminalSummary;
import dk.trustworks.intranet.recruitmentservice.dto.PositionBoardResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read model for the pipeline board (ATS plan §P7, spec §6.1
 * {@code /recruitment/pipeline}) — a pure query service, no mutations, no
 * events. The caller (resource) has already resolved the position and
 * enforced {@code RecruitmentVisibility.canReadPosition}; this service
 * only shapes data.
 * <p>
 * Query plan: one application fetch by position ({@code idx_ra_position_stage}
 * covers it), one batched candidate fetch, and at most one batched referrer
 * name lookup — grouping happens in Java (a position has dozens of
 * applications, not thousands). No N+1 anywhere.
 * <p>
 * Shape rules (P7 contract, binding):
 * <ul>
 *   <li>one column per {@code stage_set} entry in set order, including
 *       {@code HIRED} — empty stages still render;</li>
 *   <li>cards ordered oldest {@code stageEnteredAt} first (longest-waiting
 *       on top); {@code daysInStage} and {@code idle}
 *       ({@code > recruitment.sla.candidate-idle-days}) are
 *       server-computed in UTC;</li>
 *   <li>terminal applications (REJECTED / WITHDRAWN / RETURNED_TO_POOL)
 *       leave the columns and appear in the summarized rail, newest
 *       closed first.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class RecruitmentBoardService {

    @Inject
    EntityManager em;

    /**
     * The idle chip's threshold, shared with the SLA sweep and the landing
     * page (2026-08-22).
     *
     * <p>Was a hard-coded {@code 7} while {@code recruitment.sla.candidate-idle-days}
     * sat at 4 in production, so the same candidate was "idle" on the landing
     * page and in Slack three days before the board agreed — and the admin
     * settings page told administrators the one number drove all three. One
     * word, one number now.
     *
     * <p>The board's chip stays deliberately simpler than the task list:
     * {@code daysInStage > threshold}, with none of {@link RecruitmentIdleRule}'s
     * suppressions. A board card is a fact about how long someone has waited
     * — true even when the next interview is already booked — while a task
     * row is a claim that somebody must act. Only the number is shared.
     */
    @Inject
    RecruitmentSlaThresholds thresholds;

    @Inject
    dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility visibility;

    /**
     * Build the board for a position the viewer is already cleared to read.
     *
     * @param position   the resolved, visibility-checked position
     * @param viewerUuid the requesting user — reading a board never implied
     *                   being able to move its cards, and the summary now
     *                   says which of the two the caller has
     * @return the full {@code IPositionBoard} shape
     */
    public PositionBoardResponse board(RecruitmentPosition position, String viewerUuid) {
        Objects.requireNonNull(position, "position must not be null");

        List<RecruitmentApplication> applications =
                RecruitmentApplication.list("positionUuid", position.getUuid());
        Map<String, RecruitmentCandidate> candidates = loadCandidates(applications);
        Map<String, String> referrerNames = resolveReferrerNames(candidates.values());

        // stage_set is always populated for service-created positions; the
        // track default is a defensive fallback for hand-seeded rows so the
        // board never renders zero columns.
        List<String> stageSet = position.getStageSet() != null && !position.getStageSet().isEmpty()
                ? position.getStageSet()
                : RecruitmentPositionDefaults.defaultStageSet(position.getHiringTrack());

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean viewerCanDecide = visibility.canDecideOnApplication(viewerUuid, position);
        SubStatusInputs subStatusInputs = loadSubStatusInputs(applications);
        // Read once per board, never per card — app_settings is a tiny table
        // but a lookup per card is still an N+1 by any other name.
        int idleThresholdDays = thresholds.candidateIdleDays();
        List<BoardColumn> columns = buildColumns(applications, stageSet, candidates,
                referrerNames, now, position, subStatusInputs, viewerCanDecide,
                idleThresholdDays);
        BoardTerminalSummary terminal = buildTerminal(applications, candidates);

        return new PositionBoardResponse(
                summarize(position, stageSet, viewerCanDecide),
                columns, terminal);
    }

    // ---- Columns (open applications) ------------------------------------------

    private List<BoardColumn> buildColumns(List<RecruitmentApplication> applications,
                                           List<String> stageSet,
                                           Map<String, RecruitmentCandidate> candidates,
                                           Map<String, String> referrerNames,
                                           LocalDateTime now,
                                           RecruitmentPosition position,
                                           SubStatusInputs subStatusInputs,
                                           boolean viewerCanDecide,
                                           int idleThresholdDays) {
        // Sort BEFORE grouping — groupingBy preserves encounter order, so
        // every column comes out oldest stageEnteredAt first (uuid as a
        // deterministic tie-break).
        Map<String, List<RecruitmentApplication>> openByStage = applications.stream()
                .filter(application -> !application.isTerminal())
                .sorted(Comparator.comparing(RecruitmentApplication::getStageEnteredAt)
                        .thenComparing(RecruitmentApplication::getUuid))
                .collect(Collectors.groupingBy(application -> application.getStage().name(),
                        Collectors.toList()));

        // The "date has passed" pivot is Copenhagen wall-clock, because
        // scheduled_at is stored as Copenhagen wall-clock — a UTC now would
        // flip cards to VOTERING up to two hours early.
        LocalDateTime nowCopenhagen = LocalDateTime.now(COPENHAGEN);

        return stageSet.stream()
                .map(stage -> new BoardColumn(stage,
                        openByStage.getOrDefault(stage, List.of()).stream()
                                .map(application -> toCard(application,
                                        candidates.get(application.getCandidateUuid()),
                                        referrerNames, now,
                                        deriveSubStatus(application, position,
                                                subStatusInputs, nowCopenhagen,
                                                viewerCanDecide),
                                        idleThresholdDays))
                                .toList()))
                .toList();
    }

    private BoardCard toCard(RecruitmentApplication application,
                             RecruitmentCandidate candidate,
                             Map<String, String> referrerNames,
                             LocalDateTime now,
                             BoardCardSubStatus subStatus,
                             int idleThresholdDays) {
        long daysInStage = Math.max(0,
                ChronoUnit.DAYS.between(application.getStageEnteredAt(), now));
        CandidateSource source = candidate == null ? null : candidate.getSource();
        String referredByName = null;
        if (candidate != null && candidate.getReferredByUserUuid() != null
                && (source == CandidateSource.REFERRAL || source == CandidateSource.PARTNER_REFERRAL)) {
            referredByName = referrerNames.get(candidate.getReferredByUserUuid());
        }
        return new BoardCard(
                application.getUuid(),
                application.getCandidateUuid(),
                displayName(candidate),
                source,
                referredByName,
                application.getStageEnteredAt(),
                daysInStage,
                daysInStage > idleThresholdDays,
                application.getExpectedStartDate(),
                application.getAssignedTeamUuid(),
                subStatus);
    }

    // ---- Sub-status (pipeline sub-status feature) ------------------------------

    /** {@code scheduled_at} is Copenhagen wall-clock; so is the held-pivot. */
    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    /** Method B request states that mean "booking automation is in flight". */
    private static final List<SchedulingRequestStatus> BOOKING_IN_FLIGHT =
            Arrays.stream(SchedulingRequestStatus.values())
                    .filter(status -> !status.isTerminal())
                    .toList();

    /**
     * Everything the per-card derivation needs, batched per board (the
     * no-N+1 contract rule): the driving interview of each interview-stage
     * application's CURRENT round, its scorecards, which applications have
     * Method B automation running for that round, and the offer-dossier
     * signing state per OFFER candidate.
     *
     * @param drivingInterviews  applicationUuid → the latest active ROUND
     *                           interview matching the application's stage
     * @param scorecardsByInterview interviewUuid → all its scorecards
     * @param bookingInFlight    applicationUuids with a live Method B
     *                           request for their current round
     * @param contractSent       OFFER candidateUuids whose open dossier has
     *                           a SIGNATURE revision
     * @param contractSigned     the subset whose signing case is COMPLETED
     */
    record SubStatusInputs(Map<String, RecruitmentInterview> drivingInterviews,
                           Map<String, List<RecruitmentScorecard>> scorecardsByInterview,
                           Set<String> bookingInFlight,
                           Set<String> contractSent,
                           Set<String> contractSigned) {

        static final SubStatusInputs EMPTY = new SubStatusInputs(
                Map.of(), Map.of(), Set.of(), Set.of(), Set.of());
    }

    /** Three batched queries + one native query — never per card. */
    private SubStatusInputs loadSubStatusInputs(List<RecruitmentApplication> applications) {
        Map<String, Integer> roundByApplication = new HashMap<>();
        Set<String> offerCandidateSet = new HashSet<>();
        for (RecruitmentApplication application : applications) {
            if (application.isTerminal()) {
                continue;
            }
            Integer round = roundOf(application.getStage());
            if (round != null) {
                roundByApplication.put(application.getUuid(), round);
            } else if (application.getStage() == RecruitmentStage.OFFER) {
                offerCandidateSet.add(application.getCandidateUuid());
            }
        }
        List<String> offerCandidates = List.copyOf(offerCandidateSet);
        if (roundByApplication.isEmpty() && offerCandidates.isEmpty()) {
            return SubStatusInputs.EMPTY;
        }

        Map<String, RecruitmentInterview> driving = new HashMap<>();
        Map<String, List<RecruitmentScorecard>> scorecardsByInterview = Map.of();
        Set<String> bookingInFlight = new HashSet<>();
        if (!roundByApplication.isEmpty()) {
            List<String> interviewStageApps = List.copyOf(roundByApplication.keySet());
            List<RecruitmentInterview> interviews = RecruitmentInterview.list(
                    "applicationUuid in ?1 and kind = ?2 and status <> ?3",
                    interviewStageApps, RecruitmentInterviewKind.ROUND,
                    RecruitmentInterviewStatus.CANCELLED);
            for (RecruitmentInterview interview : interviews) {
                Integer currentRound = roundByApplication.get(interview.getApplicationUuid());
                if (currentRound == null || !currentRound.equals(interview.getRound())) {
                    continue; // another round's interview — not this stage's
                }
                driving.merge(interview.getApplicationUuid(), interview,
                        RecruitmentBoardService::laterOf);
            }
            if (!driving.isEmpty()) {
                scorecardsByInterview = RecruitmentScorecard.<RecruitmentScorecard>list(
                                "interviewUuid in ?1",
                                driving.values().stream().map(RecruitmentInterview::getUuid).toList())
                        .stream()
                        .collect(Collectors.groupingBy(RecruitmentScorecard::getInterviewUuid));
            }
            List<RecruitmentSchedulingRequest> liveRequests = RecruitmentSchedulingRequest.list(
                    "applicationUuid in ?1 and status in ?2",
                    interviewStageApps, BOOKING_IN_FLIGHT);
            for (RecruitmentSchedulingRequest request : liveRequests) {
                Integer currentRound = roundByApplication.get(request.getApplicationUuid());
                if (currentRound != null && currentRound.equals(request.getRound())) {
                    bookingInFlight.add(request.getApplicationUuid());
                }
            }
        }

        Set<String> contractSent = new HashSet<>();
        Set<String> contractSigned = new HashSet<>();
        if (!offerCandidates.isEmpty()) {
            // State-table truth, not event replay: a SIGNATURE revision on
            // the candidate's OPEN dossier = the contract went out; its
            // signing case reaching COMPLETED = signed (the same join the
            // signature-completion listener runs).
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery("""
                            SELECT cd.candidate_uuid,
                                   MAX(CASE WHEN cdr.kind = 'SIGNATURE' THEN 1 ELSE 0 END),
                                   MAX(CASE WHEN cdr.kind = 'SIGNATURE' AND sc.status = 'COMPLETED'
                                            THEN 1 ELSE 0 END)
                            FROM candidate_dossiers cd
                            JOIN candidate_dossier_revisions cdr ON cdr.dossier_uuid = cd.uuid
                            LEFT JOIN signing_cases sc ON sc.case_key = cdr.signing_case_key
                            WHERE cd.status = 'OPEN'
                              AND cd.candidate_uuid IN (:uuids)
                            GROUP BY cd.candidate_uuid
                            """)
                    .setParameter("uuids", offerCandidates)
                    .getResultList();
            for (Object[] row : rows) {
                String candidateUuid = (String) row[0];
                if (((Number) row[1]).intValue() > 0) {
                    contractSent.add(candidateUuid);
                }
                if (((Number) row[2]).intValue() > 0) {
                    contractSigned.add(candidateUuid);
                }
            }
        }

        return new SubStatusInputs(driving, scorecardsByInterview, bookingInFlight,
                contractSent, contractSigned);
    }

    /**
     * The ladder itself — static and pure so the DB-free tier pins every
     * rung. Interview stages: BOOK/BOOKING → AWAITING → VOTERING → DECIDE
     * → INFORM; OFFER: TEAM_MISSING → CONTRACT_NOT_SENT →
     * AWAITING_SIGNATURE → SIGNED (signing outranks the team gate — a
     * signed contract is further along regardless). SCREENING/HIRED: null.
     */
    static BoardCardSubStatus deriveSubStatus(RecruitmentApplication application,
                                              RecruitmentPosition position,
                                              SubStatusInputs inputs,
                                              LocalDateTime nowCopenhagen,
                                              boolean viewerCanDecide) {
        RecruitmentStage stage = application.getStage();
        if (roundOf(stage) != null) {
            return deriveInterviewSubStatus(
                    inputs.drivingInterviews().get(application.getUuid()),
                    inputs.bookingInFlight().contains(application.getUuid()),
                    inputs.scorecardsByInterview(), nowCopenhagen, viewerCanDecide);
        }
        if (stage == RecruitmentStage.OFFER) {
            String candidateUuid = application.getCandidateUuid();
            if (inputs.contractSigned().contains(candidateUuid)) {
                return BoardCardSubStatus.of(BoardCardSubStatus.Code.SIGNED);
            }
            if (inputs.contractSent().contains(candidateUuid)) {
                return BoardCardSubStatus.of(BoardCardSubStatus.Code.AWAITING_SIGNATURE);
            }
            if (position.getHiringTrack() == RecruitmentHiringTrack.PRACTICE_TEAM
                    && application.getAssignedTeamUuid() == null) {
                // The backend refuses to send the contract before a team is
                // chosen — the team gate is the blocking prerequisite.
                return BoardCardSubStatus.of(BoardCardSubStatus.Code.TEAM_MISSING);
            }
            return BoardCardSubStatus.of(BoardCardSubStatus.Code.CONTRACT_NOT_SENT);
        }
        return null;
    }

    private static BoardCardSubStatus deriveInterviewSubStatus(
            RecruitmentInterview interview,
            boolean bookingInFlight,
            Map<String, List<RecruitmentScorecard>> scorecardsByInterview,
            LocalDateTime nowCopenhagen,
            boolean viewerCanDecide) {
        if (interview == null) {
            return BoardCardSubStatus.of(bookingInFlight
                    ? BoardCardSubStatus.Code.BOOKING
                    : BoardCardSubStatus.Code.BOOK);
        }
        List<RecruitmentScorecard> scorecards =
                scorecardsByInterview.getOrDefault(interview.getUuid(), List.of());
        int expected = interview.getInterviewerUuids() == null
                ? 0 : interview.getInterviewerUuids().size();

        if (interview.getDecision() != null) {
            return new BoardCardSubStatus(BoardCardSubStatus.Code.INFORM,
                    interview.getUuid(), interview.getScheduledAt(), interview.getLocation(),
                    scorecards.size(), expected,
                    viewerCanDecide ? interview.getDecision() : null);
        }
        boolean held = interview.getStatus() == RecruitmentInterviewStatus.HELD
                || (interview.getScheduledAt() != null
                        && !interview.getScheduledAt().isAfter(nowCopenhagen));
        if (!held) {
            return new BoardCardSubStatus(BoardCardSubStatus.Code.AWAITING,
                    interview.getUuid(), interview.getScheduledAt(), interview.getLocation(),
                    null, null, null);
        }
        // Same debrief-readiness rule as the blind rule and the P12 Slack
        // reactor — assigned ⊆ submitted (kept scorecards from removed
        // interviewers count toward the counter but not the flip).
        boolean debriefReady = expected == 0
                || RecruitmentInterviewService.allAssignedSubmitted(interview, scorecards);
        return new BoardCardSubStatus(
                debriefReady ? BoardCardSubStatus.Code.DECIDE : BoardCardSubStatus.Code.VOTERING,
                interview.getUuid(), interview.getScheduledAt(), interview.getLocation(),
                scorecards.size(), expected, null);
    }

    /** INTERVIEW_n → n; every other stage → null (no interview ladder). */
    static Integer roundOf(RecruitmentStage stage) {
        return switch (stage) {
            case INTERVIEW_1 -> 1;
            case INTERVIEW_2 -> 2;
            case INTERVIEW_3 -> 3;
            default -> null;
        };
    }

    /**
     * Of two active interviews for the same round (re-runs), the later
     * {@code scheduledAt} drives the sub-status; an unscheduled row loses
     * to a scheduled one, uuid breaks exact ties deterministically.
     */
    private static RecruitmentInterview laterOf(RecruitmentInterview a, RecruitmentInterview b) {
        return Comparator
                .comparing(RecruitmentInterview::getScheduledAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(RecruitmentInterview::getUuid)
                .compare(a, b) >= 0 ? a : b;
    }

    // ---- Terminal rail ---------------------------------------------------------

    private BoardTerminalSummary buildTerminal(List<RecruitmentApplication> applications,
                                               Map<String, RecruitmentCandidate> candidates) {
        List<RecruitmentApplication> closed = applications.stream()
                .filter(RecruitmentApplication::isTerminal)
                .sorted(Comparator.comparing(RecruitmentBoardService::closedAt).reversed()
                        .thenComparing(RecruitmentApplication::getUuid))
                .toList();

        Map<RecruitmentApplicationTerminal, Long> counts = closed.stream()
                .collect(Collectors.groupingBy(RecruitmentApplication::getTerminal,
                        Collectors.counting()));

        List<BoardTerminalEntry> entries = closed.stream()
                .map(application -> new BoardTerminalEntry(
                        application.getUuid(),
                        application.getCandidateUuid(),
                        displayName(candidates.get(application.getCandidateUuid())),
                        application.getTerminal(),
                        application.getRejectionReasonCode(),
                        closedAt(application)))
                .toList();

        return new BoardTerminalSummary(
                counts.getOrDefault(RecruitmentApplicationTerminal.REJECTED, 0L),
                counts.getOrDefault(RecruitmentApplicationTerminal.WITHDRAWN, 0L),
                counts.getOrDefault(RecruitmentApplicationTerminal.RETURNED_TO_POOL, 0L),
                entries);
    }

    /**
     * When did this application leave the pipeline? Terminal moves never
     * touch {@code stage_entered_at}, so {@code updated_at} — maintained by
     * the audit listener on every mutation, and the terminal is always the
     * LAST mutation — is the terminal-move timestamp. {@code stage_entered_at}
     * is the defensive fallback (the column is NOT NULL in practice).
     */
    private static LocalDateTime closedAt(RecruitmentApplication application) {
        return application.getUpdatedAt() != null
                ? application.getUpdatedAt()
                : application.getStageEnteredAt();
    }

    // ---- Batched lookups -------------------------------------------------------

    private Map<String, RecruitmentCandidate> loadCandidates(List<RecruitmentApplication> applications) {
        List<String> candidateUuids = applications.stream()
                .map(RecruitmentApplication::getCandidateUuid)
                .distinct()
                .toList();
        if (candidateUuids.isEmpty()) {
            return Map.of();
        }
        return RecruitmentCandidate.<RecruitmentCandidate>list("uuid in ?1", candidateUuids).stream()
                .collect(Collectors.toMap(RecruitmentCandidate::getUuid, Function.identity()));
    }

    /**
     * Display names for the internal referrers of the board's
     * REFERRAL/PARTNER_REFERRAL candidates — ONE query for the whole board
     * (the no-N+1 contract rule). Missing users simply resolve to no name.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> resolveReferrerNames(java.util.Collection<RecruitmentCandidate> candidates) {
        List<String> referrerUuids = candidates.stream()
                .filter(candidate -> candidate.getSource() == CandidateSource.REFERRAL
                        || candidate.getSource() == CandidateSource.PARTNER_REFERRAL)
                .map(RecruitmentCandidate::getReferredByUserUuid)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (referrerUuids.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = em.createNativeQuery("""
                        SELECT uuid, TRIM(CONCAT(COALESCE(firstname, ''), ' ', COALESCE(lastname, '')))
                        FROM user
                        WHERE uuid IN (:uuids)
                        """)
                .setParameter("uuids", referrerUuids)
                .getResultList();
        return rows.stream()
                .filter(row -> row[1] != null && !((String) row[1]).isBlank())
                .collect(Collectors.toMap(row -> (String) row[0], row -> (String) row[1]));
    }

    // ---- Shaping helpers -------------------------------------------------------

    /** "First Last" per the contract; null-safe on hand-seeded partial rows. */
    private static String displayName(RecruitmentCandidate candidate) {
        if (candidate == null) {
            return null; // Defensive — the candidate FK makes this unreachable.
        }
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        return (first + " " + last).trim();
    }

    private static BoardPositionSummary summarize(RecruitmentPosition position,
                                                  List<String> stageSet,
                                                  boolean viewerCanDecide) {
        return new BoardPositionSummary(
                position.getUuid(),
                position.getTitle(),
                position.getHiringTrack(),
                position.getPracticeUuid(),
                position.getPracticeName(),
                position.getPracticeCode(),
                position.getPracticeActive(),
                position.getTeamUuid(),
                position.getHiringOwnerUuid(),
                position.getStatus(),
                position.getDemandRag(),
                stageSet,
                viewerCanDecide);
    }
}
