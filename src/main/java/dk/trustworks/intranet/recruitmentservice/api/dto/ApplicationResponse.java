package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Application;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationType;

import java.time.LocalDateTime;

public record ApplicationResponse(
        String uuid, String candidateUuid, String roleUuid,
        ApplicationType applicationType, String referrerUserUuid,
        ApplicationStage stage, String screeningOutcome, String screeningOverrideReason,
        LocalDateTime lastStageChangeAt, LocalDateTime acceptedAt, LocalDateTime convertedAt,
        String closedReason, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static ApplicationResponse from(Application a) {
        return new ApplicationResponse(
                a.uuid, a.candidateUuid, a.roleUuid, a.applicationType, a.referrerUserUuid,
                a.stage, a.screeningOutcome, a.screeningOverrideReason,
                a.lastStageChangeAt, a.acceptedAt, a.convertedAt, a.closedReason,
                a.createdAt, a.updatedAt);
    }
}
