package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Wire shape of a recruitment application. Position facts (title, track,
 * stage set) are denormalized in so the UI can render a stage stepper and
 * a readable row without a second fetch — they are derived at read time
 * from the position row, never stored on the application.
 */
public record ApplicationResponse(
        String uuid,
        String candidateUuid,
        String positionUuid,
        String positionTitle,
        RecruitmentHiringTrack positionTrack,
        /** The position's ordered stage codes — drives stage steppers/pickers. */
        List<String> positionStageSet,
        RecruitmentStage stage,
        RecruitmentApplicationTerminal terminal,
        RecruitmentRejectionReason rejectionReasonCode,
        String assignedTeamUuid,
        LocalDate expectedStartDate,
        LocalDateTime stageEnteredAt,
        LocalDateTime createdAt
) {
}
