package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadToken;

import java.time.LocalDateTime;

public record OnboardingTokenResponse(
        String uuid,
        String candidateUuid,
        String userUuid,
        boolean showDriversLicense,
        boolean showHealthInsurance,
        boolean showCriminalRecord,
        LocalDateTime expiresAt,
        boolean expired,
        LocalDateTime createdAt
) {
    public static OnboardingTokenResponse from(OnboardingUploadToken t) {
        return new OnboardingTokenResponse(
                t.getUuid(),
                t.getCandidateUuid(),
                t.getUserUuid(),
                t.isShowDriversLicense(),
                t.isShowHealthInsurance(),
                t.isShowCriminalRecord(),
                t.getExpiresAt(),
                t.isExpired(),
                t.getCreatedAt()
        );
    }
}
