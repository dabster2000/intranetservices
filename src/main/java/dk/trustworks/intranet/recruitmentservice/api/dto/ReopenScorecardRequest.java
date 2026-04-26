package dk.trustworks.intranet.recruitmentservice.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReopenScorecardRequest(@NotBlank String reason) {}
