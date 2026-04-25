package dk.trustworks.intranet.recruitmentservice.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ApplicationScreeningDecisionRequest(@NotBlank String outcome, String overrideReason) {}
