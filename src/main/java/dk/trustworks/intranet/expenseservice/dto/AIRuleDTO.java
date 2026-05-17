package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record AIRuleDTO(
    String ruleId,
    @NotBlank @Size(max=128) String displayName,
    @NotBlank String description,
    @NotBlank String severity,
    @NotBlank String resolutionType,
    int priority,
    boolean active,
    OffsetDateTime updatedAt,
    String updatedBy) {}
