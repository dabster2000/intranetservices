package dk.trustworks.intranet.aggregates.bugreport.dto;

import java.util.List;

public record BugReportListResponse(
    List<BugReportDTO> reports,
    long totalCount,
    int page,
    int size
) {}
