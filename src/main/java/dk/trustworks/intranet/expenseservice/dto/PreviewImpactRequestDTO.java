package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.*;

public record PreviewImpactRequestDTO(
    @NotBlank String ruleId,
    @NotBlank String parameter,
    @NotNull Double oldValue,
    @NotNull Double newValue,
    @Min(1) @Max(90) int windowDays
) {}
