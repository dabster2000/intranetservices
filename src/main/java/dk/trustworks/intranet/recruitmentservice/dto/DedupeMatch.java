package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;

/**
 * One potential duplicate found by the dedupe check. Employee matches are
 * flagged distinctly ({@code type=EMPLOYEE}) — creating a candidate for a
 * current colleague is almost always a mistake (plan §P3 DoD).
 *
 * @param type      CANDIDATE (existing recruitment_candidates row) or
 *                  EMPLOYEE (users row matched by email)
 * @param uuid      the matched row's uuid (candidate uuid or user uuid)
 * @param name      display name of the match
 * @param status    candidate lifecycle status; {@code null} for employees
 * @param matchedOn which identifier matched: EMAIL or LINKEDIN
 */
public record DedupeMatch(
        MatchType type,
        String uuid,
        String name,
        CandidateStatus status,
        MatchedOn matchedOn
) {
    public enum MatchType { CANDIDATE, EMPLOYEE }

    public enum MatchedOn { EMAIL, LINKEDIN }
}
