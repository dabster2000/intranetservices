package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ResponsibilityKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OpenRoleAssignmentRequest(@NotBlank String userUuid, @NotNull ResponsibilityKind responsibilityKind) {}
