package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One interview on the wire (P11) — profile Interviews tab and scheduling
 * dialogs. Scorecard CONTENT never rides here (blind rule); only progress
 * counters and the viewer-specific flags the UI needs to explain itself.
 *
 * @param ownScorecardSubmitted whether the VIEWER has submitted theirs
 * @param viewerAssigned        whether the viewer is an assigned interviewer
 * @param debriefUnlocked       whether the blind rule unlocks scorecard
 *                              content for the viewer right now
 * @param calendarSynced        whether an Outlook event exists (Graph toggle)
 */
public record InterviewResponse(
        String uuid,
        String applicationUuid,
        String candidateUuid,
        String positionUuid,
        String positionTitle,
        RecruitmentInterviewKind kind,
        Integer round,
        LocalDateTime scheduledAt,
        String location,
        String roomEmail,
        RecruitmentInterviewStatus status,
        List<InterviewerInfo> interviewers,
        boolean scorecardRequired,
        int submittedCount,
        int expectedCount,
        boolean ownScorecardSubmitted,
        boolean viewerAssigned,
        boolean debriefUnlocked,
        boolean calendarSynced
) {

    /** An assigned interviewer + their submission state (never the content). */
    public record InterviewerInfo(String uuid, String name, boolean scorecardSubmitted) {
    }
}
