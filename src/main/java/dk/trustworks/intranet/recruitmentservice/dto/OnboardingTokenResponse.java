package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadToken;

import java.time.LocalDateTime;
import java.util.List;

public record OnboardingTokenResponse(
        String uuid,
        String candidateUuid,
        String userUuid,
        boolean showDriversLicense,
        boolean showHealthInsurance,
        boolean showCriminalRecord,
        LocalDateTime expiresAt,
        boolean expired,
        LocalDateTime createdAt,
        Submitted submitted
) {
    public record Submitted(boolean driversLicense, boolean healthInsurance, boolean criminalRecord) {}

    public static OnboardingTokenResponse from(OnboardingUploadToken t) {
        List<OnboardingUploadSubmission> submissions = OnboardingUploadSubmission.findByToken(t.getUuid());
        boolean dl = false, hi = false, cr = false;
        for (OnboardingUploadSubmission s : submissions) {
            switch (s.getDocumentType()) {
                case DRIVERS_LICENSE -> dl = true;
                case HEALTH_INSURANCE -> hi = true;
                case CRIMINAL_RECORD -> cr = true;
            }
        }
        return new OnboardingTokenResponse(
                t.getUuid(),
                t.getCandidateUuid(),
                t.getUserUuid(),
                t.isShowDriversLicense(),
                t.isShowHealthInsurance(),
                t.isShowCriminalRecord(),
                t.getExpiresAt(),
                t.isExpired(),
                t.getCreatedAt(),
                new Submitted(dl, hi, cr)
        );
    }
}
