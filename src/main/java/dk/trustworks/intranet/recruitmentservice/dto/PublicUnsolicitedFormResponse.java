package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.services.PublicApplyQuestions;

import java.util.List;

/**
 * Wire shape of {@code GET /apply/unsolicited} (P5): the default question
 * set plus the active practices the applicant may express a preference
 * for. Practices expose {@code uuid} + {@code name} ONLY — registry
 * internals (codes, sort order, audit fields) never reach the public
 * surface.
 */
public record PublicUnsolicitedFormResponse(
        List<PublicApplyQuestions.Question> questions,
        List<PracticeOption> practices
) {

    /** One selectable practice: canonical uuid + display name, nothing else. */
    public record PracticeOption(String uuid, String name) {
    }
}
