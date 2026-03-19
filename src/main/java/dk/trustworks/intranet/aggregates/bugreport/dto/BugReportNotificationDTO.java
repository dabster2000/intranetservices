package dk.trustworks.intranet.aggregates.bugreport.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

public record BugReportNotificationDTO(
    String uuid,
    String userUuid,
    String reportUuid,
    String type,
    String message,
    @JsonProperty("isRead") boolean isRead,
    LocalDateTime createdAt
) {}
