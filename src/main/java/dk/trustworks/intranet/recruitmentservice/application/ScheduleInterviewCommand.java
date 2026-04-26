package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantRole;

import java.time.LocalDateTime;
import java.util.List;

public record ScheduleInterviewCommand(
        String applicationUuid,
        InterviewRoundType roundType,
        LocalDateTime scheduledAt,
        Integer durationMinutes,
        List<Participant> participants,
        String prepNotes
) {
    public record Participant(String userUuid, ParticipantRole roleInInterview, boolean isRequiredScorer) {}
}
