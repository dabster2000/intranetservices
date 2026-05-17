package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;

public record AIParameterDTO(
    String key, @NotBlank String value, String valueType, String description,
    OffsetDateTime updatedAt, String updatedBy) {}
