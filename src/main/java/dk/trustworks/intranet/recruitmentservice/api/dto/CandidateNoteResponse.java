package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.CandidateNote;

import java.time.LocalDateTime;

public record CandidateNoteResponse(
        String uuid,
        String candidateUuid,
        String authorUuid,
        String body,
        CandidateNote.Visibility visibility,
        LocalDateTime createdAt) {

    public static CandidateNoteResponse from(CandidateNote n) {
        return new CandidateNoteResponse(
                n.uuid, n.candidateUuid, n.authorUuid, n.body, n.visibility, n.createdAt);
    }
}
