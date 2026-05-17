package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.*;

public record AddAllowListEntryRequest(
    @NotBlank @Size(max = 80)  String ruleId,
    @NotBlank @Size(max = 200) String merchantNamePattern,
    @Pattern(regexp = "EXACT|CONTAINS") String matchKind,
    @Size(max = 1000) String notes
) {}
