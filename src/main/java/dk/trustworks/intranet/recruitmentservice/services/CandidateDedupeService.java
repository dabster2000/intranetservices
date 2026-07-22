package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.DedupeCheckResponse;
import dk.trustworks.intranet.recruitmentservice.dto.DedupeMatch;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.util.LinkedInUrls;
import jakarta.enterprise.context.ApplicationScoped;

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
 */
@ApplicationScoped
public class CandidateDedupeService {

    public DedupeCheckResponse check(String email, String linkedinUrl) {
        // Keyed by type+uuid so a candidate matching on both identifiers
        // appears once (first match wins; EMAIL is checked first).
        Map<String, DedupeMatch> matches = new LinkedHashMap<>();

        String normalizedEmail = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (normalizedEmail != null && !normalizedEmail.isEmpty()) {
            for (RecruitmentCandidate c : RecruitmentCandidate.<RecruitmentCandidate>list(
                    "LOWER(email) = ?1", normalizedEmail)) {
                putCandidate(matches, c, DedupeMatch.MatchedOn.EMAIL);
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
                    putCandidate(matches, c, DedupeMatch.MatchedOn.LINKEDIN);
                }
            }
        }

        return new DedupeCheckResponse(new ArrayList<>(matches.values()));
    }

    private static void putCandidate(Map<String, DedupeMatch> matches,
                                     RecruitmentCandidate c,
                                     DedupeMatch.MatchedOn matchedOn) {
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
