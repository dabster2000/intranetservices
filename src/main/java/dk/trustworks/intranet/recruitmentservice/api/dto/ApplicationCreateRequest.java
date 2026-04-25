package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApplicationCreateRequest(
        @NotBlank String candidateUuid,
        @NotBlank String roleUuid,
        @NotNull ApplicationType applicationType,
        String referrerUserUuid) {}
