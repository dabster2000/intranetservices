package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;

public record ConvertResponse(
        String newUserUuid,
        String candidateUuid,
        CandidateStatus status,
        int signingCasesTransferred
) {
    public static ConvertResponse hired(String newUserUuid, String candidateUuid, int signingCasesTransferred) {
        return new ConvertResponse(newUserUuid, candidateUuid, CandidateStatus.HIRED, signingCasesTransferred);
    }
}
