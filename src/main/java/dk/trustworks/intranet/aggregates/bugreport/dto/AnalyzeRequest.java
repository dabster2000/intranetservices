package dk.trustworks.intranet.aggregates.bugreport.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for AI triage analysis. Contains the user-provided fields
 * from the bug report form that are sent to the AI for analysis.
 * <p>
 * {@code severity} is optional -- defaults to MEDIUM if not provided.
 */
public record AnalyzeRequest(
        @NotBlank String title,
        @NotBlank String description,
        @NotBlank String stepsToReproduce,
        @NotBlank String actualBehavior,
        String severity
) {}
