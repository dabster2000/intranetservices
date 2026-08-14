package dk.trustworks.intranet.competenceservice.dto;

import dk.trustworks.intranet.competenceservice.content.CompetenceContent;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * An in-progress attempt, as the test page renders it (spec §6.1).
 *
 * <p>Returned both when an attempt is started and when it is resumed, and identical in both
 * cases — the same questions in the same order, because the order was recorded at start
 * (see {@link CompetenceLearnerMapper}).
 *
 * <p>Carries {@link LearnerQuestion}s, so no correctness flag is present anywhere in this
 * object graph. That is a property of the types, not of this mapping (§6.4, §10.2).
 *
 * <p>Nothing about scoring appears here — not the threshold frozen on the row, not the number
 * needed to pass. The candidate is told the threshold on the requirement page before they
 * start; repeating it inside the attempt would put a number next to the questions that a
 * skimming reader could mistake for a hint.
 *
 * <p>No {@code @JsonInclude(NON_NULL)}: the shape is fixed and the frontend destructures it,
 * so a missing key would be a harder failure than a null one.
 *
 * @param uuid            the attempt — what a submission and a resume address
 * @param requirementUuid the krav this attempt belongs to
 * @param compId          slug, for routing
 * @param kref            the SKI reference, denormalised onto the attempt at start
 * @param requirementName the krav's display name
 * @param versionLabel    the test version frozen onto this attempt — shown so a candidate
 *                        can say which version they sat
 * @param questionCount   frozen at start; equals {@code questions.size()} unless the frozen
 *                        payload became unreadable, in which case the count is still the
 *                        truth about what was asked
 * @param startedAt       when the clock started; the reaper abandons attempts older than
 *                        {@code competence.attempt-timeout-minutes}
 * @param questions       in payload order, options in this attempt's recorded order
 */
public record AttemptDTO(String uuid,
                         String requirementUuid,
                         String compId,
                         String kref,
                         String requirementName,
                         String versionLabel,
                         int questionCount,
                         LocalDateTime startedAt,
                         List<LearnerQuestion> questions) {

    public AttemptDTO {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    /**
     * The single mapping from a stored attempt to the learner wire shape.
     *
     * @param attempt      the row, owned by the caller (verified by
     *                     {@code CompetenceAttemptService.requireOwned})
     * @param requirement  may be {@code null} only if the requirement vanished under the
     *                     attempt, which the FK prevents; the name and slug then come out
     *                     null rather than throwing on a page the candidate is mid-test on
     * @param frozenPayload the version the attempt was started under — never the current
     *                      ACTIVE one, or republishing mid-attempt would change the paper
     *                      under the candidate
     * @param optionOrder  the recorded shuffle; empty means payload order
     */
    public static AttemptDTO of(CompetenceAttempt attempt,
                                CompetenceRequirement requirement,
                                CompetenceContent.TestPayload frozenPayload,
                                Map<String, List<String>> optionOrder) {
        return new AttemptDTO(
                attempt.getUuid(),
                attempt.getRequirementUuid(),
                requirement == null ? null : requirement.getCompId(),
                attempt.getKref(),
                requirement == null ? null : requirement.getName(),
                attempt.getVersionLabel(),
                attempt.getQuestionCount(),
                attempt.getStartedAt(),
                CompetenceLearnerMapper.questions(frozenPayload, optionOrder));
    }
}
