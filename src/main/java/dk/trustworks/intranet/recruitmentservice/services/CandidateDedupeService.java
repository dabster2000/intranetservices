package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.DedupeCheckResponse;
import dk.trustworks.intranet.recruitmentservice.dto.DedupeMatch;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.util.LinkedInUrls;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pre-create duplicate check (plan §P3): matches an email and/or a
 * LinkedIn reference against existing candidates <em>and</em> employees,
 * returning match candidates for the UI's confirmation step. The check is
 * advisory — creation is never blocked (recruiters may legitimately
 * re-enter a returning candidate); the UI asks for explicit confirmation
 * instead.
 * <ul>
 *   <li><b>Email:</b> case-insensitive exact match against
 *       {@code recruitment_candidates.email} and {@code user.email}
 *       (employee matches flagged {@code type=EMPLOYEE}).</li>
 *   <li><b>LinkedIn:</b> normalized {@code /in/} slug comparison
 *       ({@link LinkedInUrls}) against stored candidate URLs — URL
 *       variants (https/www/locale/trailing slash/case) all match. The
 *       users table has no LinkedIn column, so employees match by email
 *       only.</li>
 * </ul>
 *
 * <h3>The check is per-viewer</h3>
 * It takes an arbitrary identifier and answers with a candidate uuid and a
 * full name, so an unfiltered version is an identity oracle: probe an
 * executive's email, learn that they are a candidate, and — before the
 * attach path grew its own candidate gate — de-cloak them by attaching them
 * to a position you run. Candidate matches are therefore passed through
 * {@link RecruitmentVisibility#canReadCandidateProfile}, the same rule the
 * profile, timeline and document reads use. Employee matches are
 * unfiltered: those are {@code user} rows from the staff directory, not
 * recruitment data.
 * <p>
 * A dropped match is invisible, not flagged — the caller cannot tell a
 * filtered duplicate from no duplicate, which is the point. The cost is
 * accepted: a caller outside the circle may create a duplicate of a
 * candidate they were never allowed to know about, and the recruiter
 * merges it later. Leaking the name is the worse failure.
 */
@ApplicationScoped
public class CandidateDedupeService {

    @Inject
    RecruitmentVisibility visibility;

    /**
     * The check as a human sees it: candidate matches are filtered to what
     * {@code viewerUuid} may read.
     *
     * @param viewerUuid the {@code X-Requested-By} user; a null/blank viewer
     *                   fails closed to zero candidate matches (every caller
     *                   of this path is the BFF, which always resolves the
     *                   session user)
     */
    public DedupeCheckResponse check(String email, String linkedinUrl, String viewerUuid) {
        return collect(email, linkedinUrl, viewerUuid, true);
    }

    /**
     * The unfiltered check, for the <b>system</b> reuse decision on the
     * public {@code /apply} path: "is this email already a candidate row we
     * should attach to instead of minting a duplicate?"
     *
     * <p>Deliberately package-private, and deliberately unfiltered. There is
     * no viewer here — the caller is an anonymous applicant, so a
     * viewer-filtered check would find nothing and every returning applicant
     * would create a second candidate row. Nothing from this result reaches
     * the applicant: {@code PublicApplyService} consumes the uuid and throws
     * the rest away. Any path that returns matches TO a person must use
     * {@link #check(String, String, String)}.
     */
    DedupeCheckResponse checkForSystemReuse(String email, String linkedinUrl) {
        return collect(email, linkedinUrl, null, false);
    }

    private DedupeCheckResponse collect(String email, String linkedinUrl, String viewerUuid,
                                        boolean applyViewerFilter) {
        // Keyed by type+uuid so a candidate matching on both identifiers
        // appears once (first match wins; EMAIL is checked first).
        Map<String, DedupeMatch> matches = new LinkedHashMap<>();

        String normalizedEmail = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail != null && !normalizedEmail.isEmpty()) {
            for (RecruitmentCandidate c : RecruitmentCandidate.<RecruitmentCandidate>list(
                    "LOWER(email) = ?1", normalizedEmail)) {
                putCandidate(matches, c, DedupeMatch.MatchedOn.EMAIL, viewerUuid, applyViewerFilter);
            }
            for (User u : User.<User>list("LOWER(email) = ?1", normalizedEmail)) {
                matches.putIfAbsent("EMPLOYEE:" + u.getUuid(), new DedupeMatch(
                        DedupeMatch.MatchType.EMPLOYEE,
                        u.getUuid(),
                        u.getFullname(),
                        null,
                        DedupeMatch.MatchedOn.EMAIL));
            }
        }

        String slug = LinkedInUrls.extractSlug(linkedinUrl);
        if (slug != null) {
            // Slug normalization can't be expressed in SQL — the candidate
            // table is small (hundreds of rows), so compare in Java over the
            // rows that have a URL at all.
            for (RecruitmentCandidate c : RecruitmentCandidate.<RecruitmentCandidate>list(
                    "linkedinUrl is not null")) {
                if (slug.equals(LinkedInUrls.extractSlug(c.getLinkedinUrl()))) {
                    putCandidate(matches, c, DedupeMatch.MatchedOn.LINKEDIN, viewerUuid, applyViewerFilter);
                }
            }
        }

        return new DedupeCheckResponse(new ArrayList<>(matches.values()));
    }

    /**
     * The single funnel every candidate match passes through — which is why
     * the visibility filter lives here rather than at the two call sites.
     * Package-private so the filter can be exercised without a database.
     */
    void putCandidate(Map<String, DedupeMatch> matches,
                      RecruitmentCandidate c,
                      DedupeMatch.MatchedOn matchedOn,
                      String viewerUuid,
                      boolean applyViewerFilter) {
        if (applyViewerFilter && !visibility.canReadCandidateProfile(viewerUuid, c)) {
            return;
        }
        matches.putIfAbsent("CANDIDATE:" + c.getUuid(), new DedupeMatch(
                DedupeMatch.MatchType.CANDIDATE,
                c.getUuid(),
                (nullSafe(c.getFirstName()) + " " + nullSafe(c.getLastName())).trim(),
                c.getStatus(),
                matchedOn));
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
