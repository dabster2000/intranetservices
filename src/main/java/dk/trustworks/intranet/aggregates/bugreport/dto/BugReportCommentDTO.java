package dk.trustworks.intranet.aggregates.bugreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.utils.json.UtcInstant;

import java.time.LocalDateTime;

public record BugReportCommentDTO(
    String uuid,
    String reportUuid,
    String authorUuid,
    String authorName,
    String content,
    @JsonProperty("isSystem") boolean isSystem,
    /** UTC instant — stamped by {@code BugReportComment.create} / {@code @PrePersist}. */
    @UtcInstant LocalDateTime createdAt
) {}
