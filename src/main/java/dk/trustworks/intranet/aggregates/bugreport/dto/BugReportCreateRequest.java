package dk.trustworks.intranet.aggregates.bugreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BugReportCreateRequest(
    @NotBlank String reporterUuid,
    @NotBlank String screenshotBase64,
    @Size(max = 50) String screenshotMimeType,
    @Size(max = 2000) String pageUrl,
    @Size(max = 1000) String userAgent,
    Integer viewportWidth,
    Integer viewportHeight,
    String consoleErrors,
    @Size(max = 500) String userRoles
) {}
