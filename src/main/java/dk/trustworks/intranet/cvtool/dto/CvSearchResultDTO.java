package dk.trustworks.intranet.cvtool.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Search result for CV search endpoint.
 * Contains employee identification and match context.
 */
@RegisterForReflection
public record CvSearchResultDTO(
    String useruuid,
    String employeeName,
    String employeeTitle,
    List<String> matchedFields,
    String matchSnippet
) {}
