package dk.trustworks.intranet.competenceservice.dto;

import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttemptDecision;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.DecisionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row of the caller's own attempt history (spec §6.1, {@code GET /me/attempts}).
 *
 * <p>The list is the employee's own record of what they sat and what came of it — their copy
 * of the evidence, which is also what makes the module defensible under §10.9: nobody holds a
 * compliance file about a person that the person cannot read.
 *
 * <p>Only the caller's own rows ever reach this DTO. There is no useruuid parameter on the
 * endpoint, so there is no widening to attempt (§10.1).
 *
 * <p>No {@code @JsonInclude(NON_NULL)} here on purpose: {@code decision == null} is the
 * meaningful "PENDING" state that the badge switches on, and a key that disappears is easy to
 * mistake for a shape change. Same for {@code decidedAt}.
 *
 * @param versionLabel the test version this attempt was taken under. It stays on the row when
 *                     the content is republished, which is what keeps old evidence readable —
 *                     an attempt says which paper it was, not which paper is current.
 * @param decision     the latest ledger decision, or {@code null} for PENDING. A REVOKED row
 *                     is shown as revoked rather than dropped: an employee whose approval was
 *                     taken away has to be able to see that it was, and when.
 * @param decidedAt    when that decision was written, {@code null} while pending
 */
public record AttemptHistoryDTO(String uuid,
                                String compId,
                                String kref,
                                String requirementName,
                                String versionLabel,
                                LocalDateTime submittedAt,
                                BigDecimal score,
                                int scorePercent,
                                boolean passed,
                                DecisionType decision,
                                LocalDateTime decidedAt) {

    /**
     * @param requirement may be {@code null} if the requirement vanished under the attempt
     * @param latest      the deciding ledger row from
     *                    {@code CompetenceStatusService.latestDecisionsFor}, or {@code null}
     *                    when the attempt has never been decided
     */
    public static AttemptHistoryDTO of(CompetenceAttempt attempt,
                                       CompetenceRequirement requirement,
                                       CompetenceAttemptDecision latest) {
        BigDecimal score = attempt.getScore();
        return new AttemptHistoryDTO(
                attempt.getUuid(),
                requirement == null ? null : requirement.getCompId(),
                attempt.getKref(),
                requirement == null ? null : requirement.getName(),
                attempt.getVersionLabel(),
                attempt.getSubmittedAt(),
                score,
                CompetencePercent.score(score),
                attempt.isPassed(),
                latest == null ? null : latest.getDecision(),
                latest == null ? null : latest.getDecidedAt());
    }
}
