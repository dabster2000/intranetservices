package dk.trustworks.intranet.aggregates.bugreport.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response DTO for the AI triage analysis of a bug report.
 * Contains the AI's assessment plus pre-filled bug report fields.
 * <p>
 * {@code userGuidance} is nullable -- only present for POSSIBLY_EXPECTED
 * and USER_GUIDANCE_NEEDED assessments.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TriageResponse(
        String pageSummary,
        String observation,
        String expectedBehavior,
        String assessment,
        String assessmentExplanation,
        String suggestedSeverity,
        String severityReason,
        String userGuidance,
        String title,
        String description,
        String stepsToReproduce,
        String actualBehavior
) {}
