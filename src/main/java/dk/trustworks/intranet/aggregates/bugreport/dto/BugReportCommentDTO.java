package dk.trustworks.intranet.aggregates.bugreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record BugReportCommentDTO(
    String uuid,
    String reportUuid,
    String authorUuid,
    String authorName,
    String content,
    @JsonProperty("isSystem") boolean isSystem,
    LocalDateTime createdAt
) {}
