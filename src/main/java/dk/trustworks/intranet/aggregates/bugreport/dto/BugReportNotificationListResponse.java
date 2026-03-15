package dk.trustworks.intranet.aggregates.bugreport.dto;

import java.util.List;

public record BugReportNotificationListResponse(
    List<BugReportNotificationDTO> notifications,
    long unreadCount
) {}
