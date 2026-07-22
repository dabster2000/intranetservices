package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope for the two P8 answers endpoints ({@code IApplicationAnswers}
 * in the FE↔BE contract): {@code GET /recruitment/applications/{uuid}/answers}
 * (position-form leg) and {@code GET /recruitment/candidates/{uuid}/answers}
 * (the V437 candidate-scoped leg for unsolicited applicants).
 */
public record FormAnswersResponse(
        List<FormAnswer> answers
) {
}
