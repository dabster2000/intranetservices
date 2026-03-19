package dk.trustworks.intranet.aggregates.bugreport.dto;

import jakarta.validation.constraints.NotBlank;

public record StatusChangeRequest(
    @NotBlank String status
) {}
