package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * The authorization-filtered candidate name search behind the P14 Slack
 * surfaces — {@code /candidates} lookup and the capture modal's
 * {@code external_select}. One rule, enforced here for both: every hit
 * must pass {@link RecruitmentVisibility#canReadCandidateProfile} — the
 * exact P8 read matrix (recruiter tier, involvement, interview
 * assignment, hired-file narrowing, hard circle filter). No caller gets
 * a row the intranet profile page would 404.
 * <p>
 * ANONYMIZED candidates are excluded up front (their names are scrubbed —
 * nothing useful to match), and LIKE wildcards in the query are escaped
 * so {@code %} cannot become a match-everything probe.
 */
@ApplicationScoped
public class SlackCandidateSearch {

    /** Rows fetched per DB page while filling the authorized cap. */
    static final int SCAN_PAGE_SIZE = 50;
    /** Hard stop: never scan more than this many rows per search. */
    static final int MAX_SCANNED = 200;

    @Inject
    RecruitmentVisibility visibility;

    /**
     * Case-insensitive name/email search, visibility-filtered, capped.
     * Scans DB pages until {@code cap} authorized hits are found or the
     * scan budget runs out — the filter is per-row (profile matrix), so
     * a pure query-level cap would under-fill for low-access callers.
     *
     * @return at most {@code cap} candidates the actor may read
     */
    public List<RecruitmentCandidate> search(String actorUuid, String query, int cap) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() < 2) {
            return List.of();
        }
        String like = "%" + escapeLike(trimmed.toLowerCase()) + "%";
        List<RecruitmentCandidate> hits = new ArrayList<>(cap);
        int scanned = 0;
        for (int page = 0; scanned < MAX_SCANNED && hits.size() < cap; page++) {
            List<RecruitmentCandidate> batch = RecruitmentCandidate
                    .<RecruitmentCandidate>find("""
                                    status <> ?1 and (LOWER(firstName) LIKE ?2 escape '\\'
                                    or LOWER(lastName) LIKE ?2 escape '\\'
                                    or LOWER(CONCAT(firstName, ' ', lastName)) LIKE ?2 escape '\\'
                                    or LOWER(email) LIKE ?2 escape '\\')
                                    """,
                            Sort.descending("createdAt"),
                            CandidateStatus.ANONYMIZED, like)
                    .page(Page.of(page, SCAN_PAGE_SIZE))
                    .list();
            if (batch.isEmpty()) {
                break;
            }
            scanned += batch.size();
            for (RecruitmentCandidate candidate : batch) {
                if (hits.size() >= cap) {
                    break;
                }
                if (visibility.canReadCandidateProfile(actorUuid, candidate)) {
                    hits.add(candidate);
                }
            }
            if (batch.size() < SCAN_PAGE_SIZE) {
                break;
            }
        }
        return hits;
    }

    /** Escapes LIKE wildcards so user input matches literally. */
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
