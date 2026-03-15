package dk.trustworks.intranet.aggregates.bugreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record SuggestRequest(
    @NotBlank String field,
    @NotNull Map<String, String> currentFields
) {}
