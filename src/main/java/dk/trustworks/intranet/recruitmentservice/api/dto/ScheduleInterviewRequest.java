package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewRoundType;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ParticipantRole;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

public record ScheduleInterviewRequest(
    @NotBlank String applicationUuid,
    @NotNull InterviewRoundType roundType,
    @NotNull @Future LocalDateTime scheduledAt,
    @Min(15) @Max(240) Integer durationMinutes,
    @NotNull @Size(min = 1) List<ParticipantRequest> participants,
    String prepNotes
) {
    public record ParticipantRequest(
        @NotBlank String userUuid,
        @NotNull ParticipantRole roleInInterview,
        @NotNull Boolean isRequiredScorer
    ) {}
}
