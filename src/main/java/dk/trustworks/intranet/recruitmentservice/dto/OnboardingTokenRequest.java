package dk.trustworks.intranet.recruitmentservice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record OnboardingTokenRequest(
        String candidateUuid,
        String userUuid,
        boolean showDriversLicense,
        boolean showHealthInsurance,
        boolean showCriminalRecord,
        @Min(1) @Max(365) int expiresInDays
) {}
