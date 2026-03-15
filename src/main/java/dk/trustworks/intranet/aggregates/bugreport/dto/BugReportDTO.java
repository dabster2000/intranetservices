package dk.trustworks.intranet.aggregates.bugreport.dto;

import java.time.LocalDateTime;

public record BugReportDTO(
    String uuid,
    String reporterUuid,
    String reporterName,
    String assigneeUuid,
    String assigneeName,
    String status,
    String title,
    String description,
    String stepsToReproduce,
    String expectedBehavior,
    String actualBehavior,
    String severity,
    String screenshotS3Key,
    String logExcerpt,
    String pageUrl,
    String userAgent,
    Integer viewportWidth,
    Integer viewportHeight,
    String consoleErrors,
    String userRoles,
    String aiRawResponse,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    long commentCount
) {}
