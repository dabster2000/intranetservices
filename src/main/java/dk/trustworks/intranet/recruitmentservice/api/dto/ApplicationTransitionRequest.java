package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import jakarta.validation.constraints.NotNull;

public record ApplicationTransitionRequest(@NotNull ApplicationStage toStage, String reason, String closedReason) {}
