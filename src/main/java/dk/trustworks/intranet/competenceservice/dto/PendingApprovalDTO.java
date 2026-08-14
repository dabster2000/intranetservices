package dk.trustworks.intranet.competenceservice.dto;

import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * One row of the approval queue (spec §6.2, {@code GET /admin/attempts/pending}).
 *
 * <p>This is personal data about a named colleague — a passed test with a score, waiting for
 * somebody to attest it. It reaches a caller only with {@code competence:approve} and only for
 * subjects inside {@code AuthorizationService.resolveReach}, resolved server-side from live
 * team relationships (§3.3, §10.1).
 *
 * <p>The subject's {@code useruuid} travels because the decision endpoint needs no such thing
 * — decisions address attempt uuids — but the queue does: the self-approval refusal is a
 * per-item server rule (§5.10), and the client showing the row disabled instead of letting a
 * leader click into a refusal is a courtesy the client can only extend if it knows whose
 * attempt it is looking at.
 *
 * <p>The actor's own passed attempt is deliberately in this list rather than hidden. It
 * belongs in somebody's queue; it just cannot be actioned here, and refusing it visibly
 * teaches the rule the module itself teaches.
 *
 * @param employeeName resolved for display. Attacker-influenced in principle, which is why the
 *                     CSV export prefixes formula-leading cells (§10.3) — a JSON response has
 *                     no such hazard, and React escapes it.
 * @param versionLabel the test version sat, so an approver knows which paper they are
 *                     attesting; it stays fixed on the attempt when content is republished
 * @param score        the fraction the scorer computed
 * @param threshold    the threshold frozen onto the attempt at start, not today's setting —
 *                     approving an old attempt attests the bar it actually cleared
 * @param ageDays      whole days waiting. The queue sorts oldest first, and this is the number
 *                     that makes a stale queue visible rather than a date an approver has to
 *                     subtract from today in their head.
 */
public record PendingApprovalDTO(String attemptUuid,
                                 String useruuid,
                                 String employeeName,
                                 String compId,
                                 String kref,
                                 String requirementName,
                                 String versionLabel,
                                 LocalDateTime submittedAt,
                                 BigDecimal score,
                                 int scorePercent,
                                 BigDecimal threshold,
                                 int thresholdPercent,
                                 long ageDays) {

    /**
     * @param requirement  may be {@code null} if the requirement vanished under the attempt,
     *                     which the FK prevents — the queue still renders, without the name
     * @param employeeName display name of the subject, resolved by the caller in batch
     * @param now          evaluation instant, passed in so a whole queue is aged against one
     *                     clock and a test can pin it
     */
    public static PendingApprovalDTO of(CompetenceAttempt attempt,
                                        CompetenceRequirement requirement,
                                        String employeeName,
                                        LocalDateTime now) {
        BigDecimal score = attempt.getScore();
        BigDecimal threshold = attempt.getThresholdSnapshot();
        LocalDateTime submittedAt = attempt.getSubmittedAt();
        return new PendingApprovalDTO(
                attempt.getUuid(),
                attempt.getUseruuid(),
                employeeName,
                requirement == null ? null : requirement.getCompId(),
                attempt.getKref(),
                requirement == null ? null : requirement.getName(),
                attempt.getVersionLabel(),
                submittedAt,
                score,
                CompetencePercent.score(score),
                threshold,
                CompetencePercent.threshold(threshold),
                submittedAt == null ? 0L : ChronoUnit.DAYS.between(submittedAt, now));
    }
}
