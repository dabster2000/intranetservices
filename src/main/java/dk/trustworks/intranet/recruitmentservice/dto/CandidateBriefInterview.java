package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One interview on the restricted brief ({@link CandidateBriefResponse}):
 * the viewer's <em>own</em> non-cancelled interview with this candidate.
 * Interviews the viewer is not assigned to never appear, so the brief can
 * never be used to discover who else is meeting the candidate beyond one's
 * own co-interviewers.
 * <p>
 * {@code applicationStage} is deliberately absent — an interviewer forms a
 * view without knowing where the process stands (D10).
 *
 * @param focusAreas the position's scorecard template — what to probe for
 * @param ownScorecardSubmitted whether the viewer has already filed theirs;
 *                              other people's cards are never counted or
 *                              exposed here
 */
public record CandidateBriefInterview(
        String interviewUuid,
        String applicationUuid,
        String positionTitle,
        RecruitmentInterviewKind kind,
        Integer round,
        LocalDateTime scheduledAt,
        String location,
        RecruitmentInterviewStatus status,
        List<ScorecardAttribute> focusAreas,
        List<String> coInterviewerNames,
        boolean scorecardRequired,
        boolean ownScorecardSubmitted
) {
}
