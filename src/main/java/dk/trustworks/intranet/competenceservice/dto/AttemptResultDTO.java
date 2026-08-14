package dk.trustworks.intranet.competenceservice.dto;

import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The verdict of a submitted attempt (spec §6.1).
 *
 * <p>Everything here was decided on the server against the version frozen onto the attempt,
 * and against the threshold frozen at start rather than the current global setting — so
 * raising the threshold this afternoon cannot retroactively fail somebody who passed this
 * morning (§5.8, §6.4).
 *
 * <p>Still no correctness data: the result says how many were right, never which ones. A
 * per-question breakdown would hand out the answer key one attempt at a time, and the module
 * has no retake limit to make that expensive. The learning value the breakdown would add is
 * covered by the microcourse, which the candidate must re-read anyway before the gate reopens
 * on the next cadence.
 *
 * <p>Both the fraction and the whole percent travel. The fraction is the number the
 * comparison used; the percent is what every surface shows, rounded by
 * {@link CompetencePercent} so a badge can never render as a pass the scorer did not grant.
 *
 * <p>A pass is not yet evidence — it is {@code AWAITING_APPROVAL} until a leader decides
 * (§5.10). The result page has to say so, which is why nothing here is called "approved".
 *
 * @param correctCount     how many were right, {@code 0} for an attempt that predates scoring
 * @param questionCount    how many were asked, frozen at start
 * @param score            {@code correctCount / questionCount} as a fraction, 4 dp
 * @param scorePercent     {@code score} as a whole percent, rounded down
 * @param threshold        the pass threshold frozen onto this attempt, as a fraction
 * @param thresholdPercent {@code threshold} as a whole percent, rounded up
 * @param passed           the authority; the percentages are display only
 */
public record AttemptResultDTO(String uuid,
                               String requirementUuid,
                               String compId,
                               String kref,
                               String requirementName,
                               String versionLabel,
                               int correctCount,
                               int questionCount,
                               BigDecimal score,
                               int scorePercent,
                               BigDecimal threshold,
                               int thresholdPercent,
                               boolean passed,
                               LocalDateTime submittedAt) {

    /**
     * @param attempt     a submitted attempt — {@code submittedAt}, {@code score} and
     *                    {@code passed} are set by {@code CompetenceAttemptService.submit}
     * @param requirement may be {@code null} if the requirement vanished under the attempt,
     *                    which the FK prevents
     */
    public static AttemptResultDTO of(CompetenceAttempt attempt, CompetenceRequirement requirement) {
        BigDecimal score = attempt.getScore();
        BigDecimal threshold = attempt.getThresholdSnapshot();
        return new AttemptResultDTO(
                attempt.getUuid(),
                attempt.getRequirementUuid(),
                requirement == null ? null : requirement.getCompId(),
                attempt.getKref(),
                requirement == null ? null : requirement.getName(),
                attempt.getVersionLabel(),
                attempt.getCorrectCount() == null ? 0 : attempt.getCorrectCount(),
                attempt.getQuestionCount(),
                score,
                CompetencePercent.score(score),
                threshold,
                CompetencePercent.threshold(threshold),
                attempt.isPassed(),
                attempt.getSubmittedAt());
    }
}
