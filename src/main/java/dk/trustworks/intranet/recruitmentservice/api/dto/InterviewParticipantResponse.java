package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.InterviewParticipant;
import dk.trustworks.intranet.recruitmentservice.domain.enums.*;

public class InterviewParticipantResponse {
    public String uuid;
    public String userUuid;
    public ParticipantRole roleInInterview;
    public Boolean isRequiredScorer;
    public ParticipantInvitationStatus invitationStatus;

    public static InterviewParticipantResponse from(InterviewParticipant p) {
        InterviewParticipantResponse r = new InterviewParticipantResponse();
        r.uuid = p.uuid;
        r.userUuid = p.userUuid;
        r.roleInInterview = p.roleInInterview;
        r.isRequiredScorer = p.isRequiredScorer;
        r.invitationStatus = p.invitationStatus;
        return r;
    }
}
