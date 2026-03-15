package dk.trustworks.intranet.aggregates.bugreport.dto;

import jakarta.validation.constraints.NotBlank;

public record AssignRequest(
    @NotBlank String assigneeUuid
) {}
