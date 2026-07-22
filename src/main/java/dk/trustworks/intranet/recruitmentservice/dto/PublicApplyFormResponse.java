package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.services.PublicApplyQuestions;

import java.util.List;

/**
 * Wire shape of {@code GET /apply/{slug}} (P5): the minimal facts an
 * anonymous applicant needs to render the position form. Deliberately
 * tiny — nothing else about the position (status, track, owner, …) may
 * leak to the public surface.
 *
 * @param title        the position title
 * @param practiceName the registry practice name; {@code null} for
 *                     positions without a practice
 * @param questions    the ordered default question set
 */
public record PublicApplyFormResponse(
        String title,
        String practiceName,
        List<PublicApplyQuestions.Question> questions
) {
}
