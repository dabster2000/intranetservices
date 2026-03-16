package dk.trustworks.intranet.aggregates.bugreport.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response DTO for the AI triage analysis of a bug report.
 * <p>
 * {@code userGuidance} is nullable -- only present for POSSIBLY_EXPECTED
 * and UNCERTAIN assessments.
 * <p>
 * {@code expectedBehaviorOptions} contains 1-3 AI-generated expected behavior
 * suggestions for LIKELY_BUG and UNCERTAIN assessments; empty for POSSIBLY_EXPECTED.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TriageResponse(
        String pageSummary,
        String assessment,
        String explanation,
        String suggestedSeverity,
        String severityReason,
        String userGuidance,
        List<String> expectedBehaviorOptions
) {}
