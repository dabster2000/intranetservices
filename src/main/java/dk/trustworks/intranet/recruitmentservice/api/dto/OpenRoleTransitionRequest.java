package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import jakarta.validation.constraints.NotNull;

public record OpenRoleTransitionRequest(@NotNull RoleStatus toStatus, String reason) {}
