package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One row of the caller's own interview list (P11 kit, spec §6.1
 * {@code /recruitment/interviews}): everything an interviewer needs to walk
 * in prepared — who, for which position, when, where, the focus areas
 * (= the position's scorecard-template attributes) and the CV handle. The
 * caller is by definition assigned, so candidate name is appropriate here.
 *
 * @param focusAreas the position's scorecard template — what to probe for
 * @param cvFileUuid latest CV document, downloadable via the P8 document
 *                   endpoint (interviewer assignment grants access); null
 *                   when the candidate has no CV on file
 */
public record MyInterviewRow(
        String interviewUuid,
        String applicationUuid,
        String candidateUuid,
        String candidateName,
        String positionUuid,
        String positionTitle,
        RecruitmentInterviewKind kind,
        Integer round,
        LocalDateTime scheduledAt,
        String location,
        RecruitmentInterviewStatus status,
        String applicationStage,
        List<ScorecardAttribute> focusAreas,
        String cvFileUuid,
        boolean scorecardRequired,
        boolean ownScorecardSubmitted,
        List<String> coInterviewerNames
) {
}
