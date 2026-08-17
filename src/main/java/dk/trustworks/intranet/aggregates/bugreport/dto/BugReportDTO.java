package dk.trustworks.intranet.aggregates.bugreport.dto;

import dk.trustworks.intranet.utils.json.UtcInstant;

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
    Boolean previouslyWorked,
    /** UTC instant — stamped by {@code BugReport.createDraft} / {@code @PrePersist}. */
    @UtcInstant LocalDateTime createdAt,
    /**
     * UTC instant — restamped by every state transition and by {@code @PreUpdate}.
     * <p>Doubles as the {@code If-Match} concurrency token. Safe to zone-designate because
     * {@code BugReportResource.parseIfMatch} reads it through
     * {@link dk.trustworks.intranet.utils.TemporalParams#parseUtcInstant}, which accepts the
     * bare shape and an explicit offset alike — so tabs opened before this change, and task
     * revisions still serving the old shape during a rolling deploy, keep working.
     */
    @UtcInstant LocalDateTime updatedAt,
    long commentCount
) {}
