package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.BoardCard;
import dk.trustworks.intranet.recruitmentservice.dto.BoardColumn;
import dk.trustworks.intranet.recruitmentservice.dto.BoardPositionSummary;
import dk.trustworks.intranet.recruitmentservice.dto.BoardTerminalEntry;
import dk.trustworks.intranet.recruitmentservice.dto.BoardTerminalSummary;
import dk.trustworks.intranet.recruitmentservice.dto.PositionBoardResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *       on top); {@code daysInStage} and {@code idle} (&gt; 7 days) are
 *       server-computed in UTC;</li>
 *   <li>terminal applications (REJECTED / WITHDRAWN / RETURNED_TO_POOL)
 *       leave the columns and appear in the summarized rail, newest
 *       closed first.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class RecruitmentBoardService {

    /** Cards waiting longer than this are flagged idle (contract: {@code daysInStage > 7}). */
    static final int IDLE_THRESHOLD_DAYS = 7;

    @Inject
    EntityManager em;

    /**
     * Build the board for a position the viewer is already cleared to read.
     *
     * @param position the resolved, visibility-checked position
     * @return the full {@code IPositionBoard} shape
     */
    public PositionBoardResponse board(RecruitmentPosition position) {
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
        List<BoardColumn> columns = buildColumns(applications, stageSet, candidates, referrerNames, now);
        BoardTerminalSummary terminal = buildTerminal(applications, candidates);

        return new PositionBoardResponse(summarize(position, stageSet), columns, terminal);
    }

    // ---- Columns (open applications) ------------------------------------------

    private List<BoardColumn> buildColumns(List<RecruitmentApplication> applications,
                                           List<String> stageSet,
                                           Map<String, RecruitmentCandidate> candidates,
                                           Map<String, String> referrerNames,
                                           LocalDateTime now) {
        // Sort BEFORE grouping — groupingBy preserves encounter order, so
        // every column comes out oldest stageEnteredAt first (uuid as a
        // deterministic tie-break).
        Map<String, List<RecruitmentApplication>> openByStage = applications.stream()
                .filter(application -> !application.isTerminal())
                .sorted(Comparator.comparing(RecruitmentApplication::getStageEnteredAt)
                        .thenComparing(RecruitmentApplication::getUuid))
                .collect(Collectors.groupingBy(application -> application.getStage().name(),
                        Collectors.toList()));

        return stageSet.stream()
                .map(stage -> new BoardColumn(stage,
                        openByStage.getOrDefault(stage, List.of()).stream()
                                .map(application -> toCard(application,
                                        candidates.get(application.getCandidateUuid()),
                                        referrerNames, now))
                                .toList()))
                .toList();
    }

    private BoardCard toCard(RecruitmentApplication application,
                             RecruitmentCandidate candidate,
                             Map<String, String> referrerNames,
                             LocalDateTime now) {
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
                daysInStage > IDLE_THRESHOLD_DAYS,
                application.getExpectedStartDate(),
                application.getAssignedTeamUuid());
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

    private static BoardPositionSummary summarize(RecruitmentPosition position, List<String> stageSet) {
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
                stageSet);
    }
}
