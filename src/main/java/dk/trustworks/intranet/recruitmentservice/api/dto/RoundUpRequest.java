package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.RoundUpDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RoundUpRequest(
    @NotNull RoundUpDecision decision,
    @NotBlank String summary
) {}
