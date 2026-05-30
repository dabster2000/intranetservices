package dk.trustworks.intranet.expenseservice.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
    @Pattern(regexp = "BLOCK|SOFT_FLAG|OFF", message = "outcomeMode must be BLOCK, SOFT_FLAG, or OFF")
    String outcomeMode,
    @DecimalMin("0.0") @DecimalMax("1.0") Double confidenceThreshold,
    OffsetDateTime updatedAt,
    String updatedBy) {}
