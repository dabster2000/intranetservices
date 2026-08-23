package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.CandidateBriefInterview;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateBriefResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateDocument;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the restricted candidate brief — the single surface a person with
 * no recruitment role can reach (go-live decisions D10–D12). The access
 * decision itself lives in
 * {@code RecruitmentVisibility#canReadRestrictedCandidateView}; this class
 * decides <em>what the brief contains</em>, and its job is as much
 * subtraction as assembly.
 *
 * <h3>Why this is a separate service</h3>
 * The full profile services are built to show everything to an audience
 * already trusted with everything. Reusing them here and filtering the
 * result afterwards would put the exclusion list one forgotten field away
 * from a leak, and every future field added to the profile would default to
 * <em>exposed</em>. This class instead assembles the brief from scratch out
 * of an explicit allow-list, so a new profile field is invisible here until
 * someone deliberately adds it.
 */
@ApplicationScoped
public class CandidateBriefService {

    /**
     * The only document kinds an interviewer or circle member may see: the
     * material the candidate themselves submitted. Contract drafts, signed
     * documents, dossier appendices and identity documents are employment
     * paperwork, not interview preparation, and never appear on a brief.
     */
    static final Set<String> BRIEF_DOCUMENT_KINDS = Set.of("CV", "COVER_LETTER", "OTHER");

    @Inject
    EntityManager em;

    @Inject
    CandidateProfileReadService profileReadService;

    /**
     * Assemble the brief for one viewer and one candidate. The caller has
     * already established access; this method assumes nothing and still
     * scopes every collection to the viewer:
     * <ul>
     *   <li>interviews — only the viewer's own, non-cancelled;</li>
     *   <li>scorecards — only whether the <em>viewer's own</em> card exists;</li>
     *   <li>documents — only {@link #BRIEF_DOCUMENT_KINDS}.</li>
     * </ul>
     */
    public CandidateBriefResponse brief(String viewerUuid, RecruitmentCandidate candidate) {
        List<RecruitmentApplication> applications =
                RecruitmentApplication.list("candidateUuid", candidate.getUuid());
        Map<String, RecruitmentApplication> applicationsByUuid = applications.stream()
                .collect(Collectors.toMap(RecruitmentApplication::getUuid, a -> a));
        Map<String, RecruitmentPosition> positions = positionsOf(applications);

        List<RecruitmentInterview> ownInterviews = ownInterviews(viewerUuid, applicationsByUuid.keySet());
        Map<String, List<RecruitmentScorecard>> scorecards = scorecardsOf(ownInterviews);

        Set<String> coInterviewerUuids = new LinkedHashSet<>();
        ownInterviews.forEach(interview -> coInterviewerUuids.addAll(interview.getInterviewerUuids()));
        coInterviewerUuids.remove(viewerUuid);
        Map<String, String> names = resolveNames(coInterviewerUuids);

        List<CandidateBriefInterview> interviewRows = ownInterviews.stream()
                .sorted(Comparator.comparing(RecruitmentInterview::getScheduledAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(interview -> {
                    RecruitmentApplication application =
                            applicationsByUuid.get(interview.getApplicationUuid());
                    RecruitmentPosition position = application == null ? null
                            : positions.get(application.getPositionUuid());
                    boolean ownSubmitted = scorecards
                            .getOrDefault(interview.getUuid(), List.of()).stream()
                            .anyMatch(card -> viewerUuid.equals(card.getInterviewerUuid()));
                    List<String> coInterviewers = interview.getInterviewerUuids().stream()
                            .filter(uuid -> !viewerUuid.equals(uuid))
                            .map(uuid -> names.getOrDefault(uuid, "Unknown"))
                            .toList();
                    return new CandidateBriefInterview(
                            interview.getUuid(),
                            interview.getApplicationUuid(),
                            position == null ? null : position.getTitle(),
                            interview.getKind(),
                            interview.getRound(),
                            interview.getScheduledAt(),
                            interview.getLocation(),
                            interview.getStatus(),
                            position == null ? List.<ScorecardAttribute>of() : focusAreas(position),
                            coInterviewers,
                            interview.getKind().takesScorecard(),
                            ownSubmitted);
                })
                .toList();

        return new CandidateBriefResponse(
                candidate.getUuid(),
                fullName(candidate),
                candidate.getLinkedinUrl(),
                briefDocuments(candidate.getUuid()),
                profileReadService.answersForCandidate(candidate.getUuid()).answers(),
                interviewRows);
    }

    /**
     * The document rows a restricted viewer may see — the allow-list of
     * {@link #BRIEF_DOCUMENT_KINDS} applied to the P8 derivation, so kind
     * resolution stays in one place.
     */
    public List<CandidateDocument> briefDocuments(String candidateUuid) {
        return profileReadService.documents(candidateUuid, false).documents().stream()
                .filter(document -> BRIEF_DOCUMENT_KINDS.contains(document.kind()))
                .toList();
    }

    /**
     * The file uuids a restricted viewer may download for this candidate —
     * the download endpoint's allow-list. Derived from the same rule as
     * {@link #briefDocuments}, so a document that is not on the brief can
     * never be fetched by guessing its uuid.
     */
    public Set<String> downloadableFileUuids(String candidateUuid) {
        return briefDocuments(candidateUuid).stream()
                .map(CandidateDocument::fileUuid)
                .collect(Collectors.toSet());
    }

    // ---- Batched lookups ---------------------------------------------------

    /**
     * The viewer's own non-cancelled interviews on the given applications.
     * Scoped by {@code JSON_CONTAINS} on {@code interviewer_uuids} — the
     * same assignment predicate the access rule uses, so the two can never
     * disagree about what "assigned" means.
     */
    @SuppressWarnings("unchecked")
    private List<RecruitmentInterview> ownInterviews(String viewerUuid,
                                                     Collection<String> applicationUuids) {
        if (applicationUuids.isEmpty()) {
            return List.of();
        }
        List<String> uuids = em.createNativeQuery("""
                        SELECT i.uuid FROM recruitment_interviews i
                        WHERE i.application_uuid IN (:applications)
                          AND i.status <> 'CANCELLED'
                          AND JSON_CONTAINS(i.interviewer_uuids, JSON_QUOTE(:viewer))
                        """)
                .setParameter("applications", List.copyOf(applicationUuids))
                .setParameter("viewer", viewerUuid)
                .getResultList();
        return uuids.isEmpty() ? List.of() : RecruitmentInterview.list("uuid in ?1", uuids);
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

    private static List<ScorecardAttribute> focusAreas(RecruitmentPosition position) {
        List<ScorecardAttribute> template = position.getScorecardTemplate();
        return template != null ? template : List.of();
    }

    private static String fullName(RecruitmentCandidate candidate) {
        return (nullToEmpty(candidate.getFirstName()) + " " + nullToEmpty(candidate.getLastName()))
                .trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
