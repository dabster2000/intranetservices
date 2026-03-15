package dk.trustworks.intranet.aggregates.bugreport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BugReportCommentCreateRequest(
    @NotBlank @Size(max = 5000) String content
) {}
