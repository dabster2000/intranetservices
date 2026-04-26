package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.*;
import dk.trustworks.intranet.recruitmentservice.domain.enums.*;

import java.time.LocalDateTime;
import java.util.List;

public class InterviewResponse {
    public String uuid;
    public String applicationUuid;
    public Integer roundNumber;
    public InterviewRoundType roundType;
    public LocalDateTime scheduledAt;
    public Integer durationMinutes;
    public String outlookEventId;             // always null in 3a
    public String interviewKitArtifactUuid;
    public InterviewStatus status;
    public RoundUpDecision roundUpDecision;
    public LocalDateTime roundUpAt;
    public String roundUpSummary;
    public Integer rescheduleCount;
    public List<InterviewParticipantResponse> participants;

    public static InterviewResponse from(Interview iv, List<InterviewParticipant> ps) {
        InterviewResponse r = new InterviewResponse();
        r.uuid = iv.uuid;
        r.applicationUuid = iv.applicationUuid;
        r.roundNumber = iv.roundNumber;
        r.roundType = iv.roundType;
        r.scheduledAt = iv.scheduledAt;
        r.durationMinutes = iv.durationMinutes;
        r.outlookEventId = iv.outlookEventId;
        r.interviewKitArtifactUuid = iv.interviewKitArtifactUuid;
        r.status = iv.status;
        r.roundUpDecision = iv.roundUpDecision;
        r.roundUpAt = iv.roundUpAt;
        r.roundUpSummary = iv.roundUpSummary;
        r.rescheduleCount = iv.rescheduleCount;
        r.participants = ps == null ? List.of()
            : ps.stream().map(InterviewParticipantResponse::from).toList();
        return r;
    }
}
