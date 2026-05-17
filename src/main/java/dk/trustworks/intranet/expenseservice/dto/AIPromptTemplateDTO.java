package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;

public record AIPromptTemplateDTO(
    String templateKey, @NotBlank String body, int currentVersion,
    OffsetDateTime updatedAt, String updatedBy) {}
