package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.model.DecisionType;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Derives every status in the module. Deliberately pure — no CDI, no database, no clock
 * of its own — so the whole of spec §5.3–§5.5 is table-testable in the DB-free tier that
 * gates the deploy.
 *
 * <p>Two ordering decisions are load-bearing and easy to get wrong:
 *
 * <ol>
 *   <li><strong>The version comparison runs before the cadence check.</strong> Someone
 *       whose completion is both stale <em>and</em> against an old version reads as
 *       "retake required", not "overdue" — the retake is what they must do, and telling
 *       them the older thing first would send them to the wrong action.</li>
 *   <li><strong>The cell ranks by urgency, not progress.</strong> A person who has done
 *       everything on a requirement whose test version just changed is amber, even
 *       though their own history is spotless.</li>
 * </ol>
 *
 * <p>Force-retake is not handled here. Publishing with force-retake off re-stamps prior
 * completions onto the new version inside the publish transaction, so by the time this
 * runs the data already says what it means and no special case is needed.
 */
public final class CompetenceStatusCalculator {

    private CompetenceStatusCalculator() {
    }

    /**
     * One completion, reduced to what the derivation needs.
     *
     * @param versionLabel the label of the version that was read
     * @param completedAt  when
     */
    public record CompletionFact(String versionLabel, LocalDateTime completedAt) {
    }

    /**
     * One submitted attempt, reduced to what the derivation needs.
     *
     * @param versionLabel   the test version the attempt was taken under
     * @param submittedAt    when it was scored
     * @param passed         whether the score met the threshold frozen on the attempt
     * @param latestDecision the deciding ledger row, or {@code null} for PENDING
     */
    public record AttemptFact(String versionLabel,
                              LocalDateTime submittedAt,
                              boolean passed,
                              DecisionType latestDecision) {

        /** A pass only counts as evidence while it has not been revoked. */
        public boolean countsAsPass() {
            return passed && latestDecision != DecisionType.REVOKED;
        }

        public boolean isApproved() {
            return latestDecision == DecisionType.APPROVED;
        }
    }

    // -----------------------------------------------------------------------
    // Microcourse (spec §5.3)
    // -----------------------------------------------------------------------

    public static CompetenceStatus.Course courseStatus(String activeVersionLabel,
                                                       List<CompletionFact> completions,
                                                       int cadenceDays,
                                                       LocalDateTime now) {
        if (activeVersionLabel == null) {
            return CompetenceStatus.Course.NOT_PUBLISHED;
        }
        CompletionFact latest = latestCompletion(completions, activeVersionLabel);
        if (latest == null) {
            return CompetenceStatus.Course.NOT_STARTED;
        }
        // Version before cadence, deliberately.
        if (!Objects.equals(latest.versionLabel(), activeVersionLabel)) {
            return CompetenceStatus.Course.RETAKE_REQUIRED;
        }
        if (isOverdue(latest.completedAt(), cadenceDays, now)) {
            return CompetenceStatus.Course.OVERDUE;
        }
        return CompetenceStatus.Course.COMPLETED;
    }

    // -----------------------------------------------------------------------
    // Test (spec §5.4)
    // -----------------------------------------------------------------------

    public static CompetenceStatus.Test testStatus(String activeVersionLabel,
                                                   List<AttemptFact> attempts,
                                                   int cadenceDays,
                                                   LocalDateTime now) {
        if (activeVersionLabel == null) {
            return CompetenceStatus.Test.NOT_PUBLISHED;
        }
        AttemptFact latestPass = latestQualifyingPass(attempts);
        if (latestPass == null) {
            return CompetenceStatus.Test.NOT_PASSED;
        }
        if (!Objects.equals(latestPass.versionLabel(), activeVersionLabel)) {
            return CompetenceStatus.Test.RETAKE_REQUIRED;
        }
        if (isOverdue(latestPass.submittedAt(), cadenceDays, now)) {
            return CompetenceStatus.Test.OVERDUE;
        }
        return latestPass.isApproved()
                ? CompetenceStatus.Test.APPROVED
                : CompetenceStatus.Test.AWAITING_APPROVAL;
    }

    // -----------------------------------------------------------------------
    // Matrix cell (spec §5.5)
    // -----------------------------------------------------------------------

    public static CompetenceStatus.Cell cellStatus(CompetenceStatus.Course course,
                                                   CompetenceStatus.Test test) {
        if (course == CompetenceStatus.Course.NOT_PUBLISHED
                || test == CompetenceStatus.Test.NOT_PUBLISHED) {
            return CompetenceStatus.Cell.NOT_PUBLISHED;
        }
        if (course == CompetenceStatus.Course.OVERDUE || test == CompetenceStatus.Test.OVERDUE) {
            return CompetenceStatus.Cell.OVERDUE;
        }
        if (course == CompetenceStatus.Course.RETAKE_REQUIRED
                || test == CompetenceStatus.Test.RETAKE_REQUIRED) {
            return CompetenceStatus.Cell.RETAKE_REQUIRED;
        }
        boolean hasCompletion = course == CompetenceStatus.Course.COMPLETED;
        boolean hasPass = test == CompetenceStatus.Test.APPROVED
                || test == CompetenceStatus.Test.AWAITING_APPROVAL;
        if (!hasCompletion && !hasPass) {
            return CompetenceStatus.Cell.NOT_STARTED;
        }
        if (hasCompletion && !hasPass) {
            return CompetenceStatus.Cell.COURSE_COMPLETED;
        }
        return test == CompetenceStatus.Test.APPROVED
                ? CompetenceStatus.Cell.APPROVED
                : CompetenceStatus.Cell.AWAITING_APPROVAL;
    }

    // -----------------------------------------------------------------------
    // The course gate (spec §5.6)
    // -----------------------------------------------------------------------

    /**
     * Whether a test attempt may start. Green microcourse only — re-evaluated on every
     * start, so someone whose completion fell due mid-session is gated again on their
     * next attempt rather than riding a stale check.
     */
    public static boolean courseGateOpen(CompetenceStatus.Course course) {
        return course == CompetenceStatus.Course.COMPLETED;
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /**
     * The newest completion, with ties on {@code completedAt} broken in favour of the row
     * carrying the active version label.
     *
     * <p>The tie-break is load-bearing, not tidiness. Publishing with force-retake off appends
     * a completion that copies the source row's {@code completed_at} verbatim, so the carried
     * row and the row it came from are indistinguishable by time. {@code Stream.max} keeps the
     * first of two equal elements, and the read query's {@code order by completed_at desc}
     * orders ties arbitrarily — so without this second key the same data reads
     * {@code COMPLETED} or {@code RETAKE_REQUIRED} depending on which row the storage engine
     * happened to return first, and a typo fix leaves half the audience green and half amber.
     *
     * <p>Preferring the active label only decides <em>which</em> of two equally recent rows is
     * read; it cannot invent recency, so a carried-forward completion that is past its cadence
     * still reads {@code OVERDUE} and a correction never renews anyone's compliance.
     */
    static CompletionFact latestCompletion(List<CompletionFact> completions,
                                           String activeVersionLabel) {
        if (completions == null || completions.isEmpty()) {
            return null;
        }
        Comparator<CompletionFact> byRecencyThenActiveVersion =
                Comparator.comparing(CompletionFact::completedAt)
                        .thenComparing(c -> Objects.equals(c.versionLabel(), activeVersionLabel));
        return completions.stream()
                .filter(c -> c.completedAt() != null)
                .max(byRecencyThenActiveVersion)
                .orElse(null);
    }

    static AttemptFact latestQualifyingPass(List<AttemptFact> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return null;
        }
        return attempts.stream()
                .filter(a -> a.submittedAt() != null)
                .filter(AttemptFact::countsAsPass)
                .max(Comparator.comparing(AttemptFact::submittedAt))
                .orElse(null);
    }

    /**
     * Cadence is evaluated at read time, which is why lowering it can turn a matrix
     * green→red the same second — unlike the threshold, which is frozen per attempt.
     */
    static boolean isOverdue(LocalDateTime at, int cadenceDays, LocalDateTime now) {
        if (at == null || cadenceDays <= 0) {
            return false;
        }
        return ChronoUnit.DAYS.between(at, now) > cadenceDays;
    }
}
