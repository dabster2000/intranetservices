package dk.trustworks.intranet.aggregates.bugreport.dto;

import dk.trustworks.intranet.aggregates.bugreport.entities.Severity;
import jakarta.validation.constraints.Size;

public record BugReportUpdateRequest(
    @Size(max = 500) String title,
    String description,
    String stepsToReproduce,
    String expectedBehavior,
    String actualBehavior,
    Severity severity,
    String status
) {}
