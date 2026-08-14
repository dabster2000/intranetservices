package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator.AttemptFact;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator.CompletionFact;
import dk.trustworks.intranet.competenceservice.model.DecisionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spec §5.3 "Force-retake interaction", asserted through §12.1's row
 * <em>"Force-retake off: prior completions carry forward; force-retake on: they do not."</em>
 *
 * <p><strong>What this class is and is not.</strong> The carry-forward itself is a database
 * write: {@code CompetenceContentService.publish} appends one
 * {@code competence_course_completion} row per affected person inside the publish transaction,
 * because V495's triggers refuse UPDATE and DELETE on that table outright. That write cannot be
 * exercised without a schema, so it belongs to — and is covered in — the {@code @QuarkusTest}
 * tier (§12.2). <em>This</em> class pins the rule that write exists to produce, at the only
 * layer where it is decidable without a database: given the rows the publish transaction leaves
 * behind, what does {@link CompetenceStatusCalculator#courseStatus} say?
 *
 * <p>Stated as a contract the DB write must satisfy:
 *
 * <ul>
 *   <li><strong>Force-retake on</strong> appends nothing. The person's newest completion still
 *       carries the <em>old</em> label, so §5.3's version comparison flips them to
 *       {@code RETAKE_REQUIRED} against the new active label. Nothing else is needed — the
 *       label comparison does the whole job on its own.</li>
 *   <li><strong>Force-retake off</strong> appends a completion carrying the <em>new</em> label
 *       and the <em>original</em> {@code completed_at}, so the person reads {@code COMPLETED}
 *       again.</li>
 *   <li><strong>And the renewal clock does not move.</strong> This is the property that
 *       actually matters, and the reason the appended row copies {@code completed_at} instead
 *       of stamping the publish time: cadence is evaluated at read time (§5.8), so a carried
 *       row stamped "now" would hand the entire audience a fresh year of compliance because
 *       somebody fixed a typo. A carried-forward completion older than the cadence must still
 *       read {@code OVERDUE}. That single assertion is what stops a correction from silently
 *       renewing everyone.</li>
 * </ul>
 *
 * <p>Every course assertion here runs the row list in both directions — see
 * {@link #assertCourseStatus}. That is not defensive padding: the appended row shares its
 * source row's {@code completed_at} exactly, and both rows land in the same
 * {@code (user, requirement)} bucket, so the "latest completion" the rule turns on is a tie
 * that no ordering in the read query can break. The rule must therefore hold whichever row the
 * storage engine hands back first, or the feature works for some people and not others.
 */
class CompetenceCarryForwardTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 9, 0);
    private static final int CADENCE = 365;

    /** The label people originally completed under. */
    private static final String V1 = "2026-06";
    /** The corrected version — a typo fix, published with force-retake off. */
    private static final String V2 = "2026-07";
    /** A second correction on top of the first. */
    private static final String V3 = "2026-08";

    private static CompletionFact completed(String label, int daysAgo) {
        return new CompletionFact(label, NOW.minusDays(daysAgo));
    }

    private static AttemptFact pass(String label, int daysAgo, DecisionType decision) {
        return new AttemptFact(label, NOW.minusDays(daysAgo), true, decision);
    }

    /**
     * The row the publish transaction appends: same person, same instant, new label. Written
     * as its own factory so every test below reads as a description of what the DB now holds,
     * and so the timestamp identity with the source row is impossible to fudge by accident —
     * {@code carriedForward} takes the exact source fact and only swaps the label.
     */
    private static CompletionFact carriedForward(CompletionFact source, String newLabel) {
        return new CompletionFact(newLabel, source.completedAt());
    }

    /**
     * Asserts the course status for a set of completion rows <em>and for the same rows in the
     * opposite order</em>.
     *
     * <p>{@code CompetenceStatusService.snapshot} reads completions with
     * {@code order by completed_at desc} and groups them per user and requirement; the
     * calculator then reduces the group with {@code Stream.max}. A carried-forward row and the
     * row it was copied from have identical {@code completed_at} values, so neither the SQL
     * {@code ORDER BY} nor the {@code max} reduction has anything to break the tie with — the
     * winner is whichever row the storage engine happened to return first. If the two
     * directions disagree, the carry-forward is a coin flip per employee.
     */
    private static void assertCourseStatus(CompetenceStatus.Course expected,
                                           String activeLabel,
                                           int cadenceDays,
                                           List<CompletionFact> rows) {
        assertEquals(expected,
                CompetenceStatusCalculator.courseStatus(activeLabel, rows, cadenceDays, NOW),
                () -> "active=" + activeLabel + " rows=" + describe(rows));

        List<CompletionFact> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        assertEquals(expected,
                CompetenceStatusCalculator.courseStatus(activeLabel, reversed, cadenceDays, NOW),
                () -> "active=" + activeLabel + " rows=" + describe(reversed)
                        + " — the status changed when nothing but the row order changed. "
                        + "A carried-forward completion shares its source row's completed_at "
                        + "exactly, so that order is whatever the storage engine returns and "
                        + "the rule must not depend on it.");
    }

    private static String describe(List<CompletionFact> rows) {
        return rows.stream()
                .map(r -> r.versionLabel() + "@-"
                        + java.time.temporal.ChronoUnit.DAYS.between(r.completedAt(), NOW) + "d")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("force-retake ON — nothing carries forward (§5.3)")
    class ForceRetakeOn {

        @Test
        @DisplayName("the audience flips to retake on the label alone, with no extra rows written")
        void newLabelFlipsTheAudience() {
            // publish(forcedRetake = true) appends nothing at all. The person's only
            // completion still says V1; the active version says V2.
            assertCourseStatus(CompetenceStatus.Course.RETAKE_REQUIRED,
                    V2, CADENCE, List.of(completed(V1, 10)));
        }

        @Test
        @DisplayName("how recently they completed the old version is irrelevant")
        void recencyDoesNotSaveThem() {
            // Completed yesterday, and still amber. Force-retake means the content changed
            // in a way that matters, so freshness against the *old* version is worth nothing.
            assertCourseStatus(CompetenceStatus.Course.RETAKE_REQUIRED,
                    V2, CADENCE, List.of(completed(V1, 1)));
            assertCourseStatus(CompetenceStatus.Course.RETAKE_REQUIRED,
                    V2, CADENCE, List.of(completed(V1, 0)));
        }

        @Test
        @DisplayName("someone already overdue reads retake, not overdue — retake is the action")
        void staleAndSupersededReadsRetake() {
            // §5.3 runs the version comparison before the cadence check. Both conditions hold
            // here; reporting 'overdue' would point them at the version they must not take.
            assertCourseStatus(CompetenceStatus.Course.RETAKE_REQUIRED,
                    V2, CADENCE, List.of(completed(V1, 400)));
        }

        @Test
        @DisplayName("a whole history of old-version completions still reads retake")
        void repeatedOldCompletionsDoNotAccumulateIntoCompliance() {
            assertCourseStatus(CompetenceStatus.Course.RETAKE_REQUIRED,
                    V2, CADENCE,
                    List.of(completed(V1, 700), completed(V1, 300), completed(V1, 5)));
        }

        @Test
        @DisplayName("someone who never completed anything is untouched by the publish")
        void neverCompletedStaysNotStarted() {
            assertCourseStatus(CompetenceStatus.Course.NOT_STARTED, V2, CADENCE, List.of());
        }
    }

    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("force-retake OFF — the appended row restores compliance (§5.3)")
    class ForceRetakeOff {

        @Test
        @DisplayName("the carried row reads as a completion of the new version")
        void carriedForwardReadsCompleted() {
            CompletionFact source = completed(V1, 10);
            assertCourseStatus(CompetenceStatus.Course.COMPLETED,
                    V2, CADENCE, List.of(source, carriedForward(source, V2)));
        }

        @Test
        @DisplayName("the appended row is the only difference between the two modes")
        void theAppendedRowIsTheWholeMechanism() {
            // Same person, same history, same active label. Drop the one appended row and the
            // status flips. This is what force-retake actually controls — nothing else in the
            // derivation looks at the flag.
            CompletionFact source = completed(V1, 10);

            assertCourseStatus(CompetenceStatus.Course.RETAKE_REQUIRED,
                    V2, CADENCE, List.of(source));
            assertCourseStatus(CompetenceStatus.Course.COMPLETED,
                    V2, CADENCE, List.of(source, carriedForward(source, V2)));
        }

        @Test
        @DisplayName("one appended row per person, not per historical completion")
        void onlyTheNewestCompletionIsCarried() {
            // carryCompletionsForward keeps the newest row per user and copies only that one,
            // so the older V1 rows stay behind as history and must not disturb the result.
            CompletionFact newest = completed(V1, 10);
            assertCourseStatus(CompetenceStatus.Course.COMPLETED,
                    V2, CADENCE,
                    List.of(completed(V1, 400), completed(V1, 120), newest,
                            carriedForward(newest, V2)));
        }

        @Test
        @DisplayName("corrections chain — a second typo fix carries the first one's row forward")
        void chainedCorrectionsKeepCarrying() {
            // The second publish reads the completions pointing at the *first* correction's
            // version, so the chain is V1 -> V2 -> V3 with one timestamp throughout.
            CompletionFact source = completed(V1, 30);
            CompletionFact firstCarry = carriedForward(source, V2);
            CompletionFact secondCarry = carriedForward(source, V3);

            assertCourseStatus(CompetenceStatus.Course.COMPLETED,
                    V3, CADENCE, List.of(source, firstCarry, secondCarry));
        }

        @Test
        @DisplayName("it cannot manufacture compliance for someone who never completed the course")
        void nothingToCarryIsANoOp() {
            // carryCompletionsForward iterates existing completions, so a person with none
            // gets no row and stays red. A correction must never create evidence.
            assertCourseStatus(CompetenceStatus.Course.NOT_STARTED, V2, CADENCE, List.of());
        }

        @Test
        @DisplayName("a person who joined the audience after the correction is still not started")
        void newJoinerUnaffected() {
            assertCourseStatus(CompetenceStatus.Course.NOT_STARTED, V3, CADENCE, List.of());
        }
    }

    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("the renewal clock must NOT be reset by a correction (§5.8) — the property that matters")
    class CadenceClockNotReset {

        @Test
        @DisplayName("a carried-forward completion older than the cadence still reads overdue")
        void carriedForwardDoesNotRenewCompliance() {
            // The one assertion this whole class exists for. The person read the microcourse
            // 400 days ago; an author fixed a typo this morning. They are still overdue.
            // If this ever goes green as COMPLETED, a typo fix has silently renewed the
            // compliance of everyone in the audience and the matrix is lying.
            CompletionFact source = completed(V1, 400);
            assertCourseStatus(CompetenceStatus.Course.OVERDUE,
                    V2, CADENCE, List.of(source, carriedForward(source, V2)));
        }

        @Test
        @DisplayName("stamping the publish time instead of copying it is what that failure looks like")
        void stampingNowWouldHaveRenewedEveryone() {
            // Not a requirement — a demonstration of the forbidden implementation, kept so the
            // contrast with the test above is explicit. Here the appended row carries the
            // publish instant rather than the employee's, and the same 400-day-stale person
            // reads green. This is exactly why carryCompletionsForward copies completed_at.
            CompletionFact source = completed(V1, 400);
            CompletionFact stampedAtPublishTime = completed(V2, 0);

            assertCourseStatus(CompetenceStatus.Course.COMPLETED,
                    V2, CADENCE, List.of(source, stampedAtPublishTime));
        }

        @Test
        @DisplayName("the cadence boundary survives the carry-forward unshifted")
        void boundaryIsUnchangedByACarryForward() {
            CompletionFact onTheBoundary = completed(V1, CADENCE);
            assertCourseStatus(CompetenceStatus.Course.COMPLETED,
                    V2, CADENCE, List.of(onTheBoundary, carriedForward(onTheBoundary, V2)));

            CompletionFact justPast = completed(V1, CADENCE + 1);
            assertCourseStatus(CompetenceStatus.Course.OVERDUE,
                    V2, CADENCE, List.of(justPast, carriedForward(justPast, V2)));
        }

        @Test
        @DisplayName("chained corrections do not accumulate into a renewal either")
        void chainedCorrectionsStillOverdue() {
            CompletionFact source = completed(V1, 400);
            assertCourseStatus(CompetenceStatus.Course.OVERDUE,
                    V3, CADENCE,
                    List.of(source, carriedForward(source, V2), carriedForward(source, V3)));
        }

        @Test
        @DisplayName("a per-requirement cadence override is applied to the carried row too")
        void shorterCadenceStillBites() {
            // effectiveCadenceDays(requirement) may be well under the global default; the
            // carried row is measured against whatever the caller passes, not a frozen value.
            CompletionFact source = completed(V1, 100);
            assertCourseStatus(CompetenceStatus.Course.OVERDUE,
                    V2, 90, List.of(source, carriedForward(source, V2)));
            assertCourseStatus(CompetenceStatus.Course.COMPLETED,
                    V2, 180, List.of(source, carriedForward(source, V2)));
        }
    }

    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("the tie the carry-forward creates on completed_at")
    class TieOnCompletedAt {

        /**
         * Called out on its own because it is the one thing about this feature that is easy to
         * get right in a unit test and wrong in production.
         *
         * <p>The appended row copies {@code completed_at} verbatim, so the source row and the
         * carried row are indistinguishable by time. {@code CompetenceStatusService.snapshot}
         * reads them with {@code order by completed_at desc} — which orders ties arbitrarily —
         * and {@link CompetenceStatusCalculator#latestCompletion} reduces with
         * {@code Stream.max}, which keeps the first element it saw when the comparison is
         * zero. Nothing in that path prefers the carried row over its source.
         *
         * <p>So the observable rule has to be order-independent, or a typo fix leaves half the
         * audience green and half amber with no pattern anyone can explain.
         */
        @Test
        @DisplayName("which of the two tied rows wins must not decide the person's status")
        void tieBreakIsNotOrderDependent() {
            CompletionFact source = completed(V1, 10);
            CompletionFact carried = carriedForward(source, V2);

            assertEquals(source.completedAt(), carried.completedAt(),
                    "the carried row must copy completed_at exactly — if it did not, this "
                            + "test would be asserting nothing");

            assertEquals(CompetenceStatus.Course.COMPLETED,
                    CompetenceStatusCalculator.courseStatus(V2, List.of(carried, source), CADENCE, NOW),
                    "carried row returned first");
            assertEquals(CompetenceStatus.Course.COMPLETED,
                    CompetenceStatusCalculator.courseStatus(V2, List.of(source, carried), CADENCE, NOW),
                    "source row returned first — the two tied rows must not give different "
                            + "answers, because the read query cannot order them");
        }

        @Test
        @DisplayName("the same tie on an overdue pair must resolve to overdue either way")
        void tieOnAnOverduePairIsAlsoStable() {
            CompletionFact source = completed(V1, 400);
            CompletionFact carried = carriedForward(source, V2);

            assertEquals(CompetenceStatus.Course.OVERDUE,
                    CompetenceStatusCalculator.courseStatus(V2, List.of(carried, source), CADENCE, NOW),
                    "carried row returned first");
            assertEquals(CompetenceStatus.Course.OVERDUE,
                    CompetenceStatusCalculator.courseStatus(V2, List.of(source, carried), CADENCE, NOW),
                    "source row returned first");
        }
    }

    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("the TEST track has no carry-forward at all (§5.4)")
    class TestTrackHasNoCarryForward {

        /**
         * {@code publish} only carries completions forward for {@code ContentKind.COURSE}.
         * Attempts are immutable — V495's row trigger permits the scoring write, the abandoned
         * flag and GDPR pseudonymisation and nothing else — so there is no append that could
         * make an old pass count against a new test label. Force-retake off therefore does
         * nothing on a test, and the publish dialog has to say so out loud.
         */
        @Test
        @DisplayName("an approved pass on the old label is amber no matter how the test was published")
        void approvedPassStillRequiresRetake() {
            assertEquals(CompetenceStatus.Test.RETAKE_REQUIRED,
                    CompetenceStatusCalculator.testStatus(V2,
                            List.of(pass(V1, 2, DecisionType.APPROVED)), CADENCE, NOW));
        }

        @Test
        @DisplayName("and there is no appended attempt that could rescue it")
        void nothingCanCarryAPassForward() {
            // Contrast with ForceRetakeOff#theAppendedRowIsTheWholeMechanism: on the course
            // side an extra row exists to be written, here the table refuses one.
            assertEquals(CompetenceStatus.Test.RETAKE_REQUIRED,
                    CompetenceStatusCalculator.testStatus(V3,
                            List.of(pass(V1, 200, DecisionType.APPROVED),
                                    pass(V2, 20, DecisionType.APPROVED)), CADENCE, NOW));
        }

        @Test
        @DisplayName("republishing the test under the same label leaves the pass alone")
        void sameLabelIsTheOnlyWayATestPassSurvivesARepublish() {
            // The comparison is on the label, so a same-label republish is the *only* lever an
            // author has on the test side — which is why §5.3's dialog wording matters.
            assertEquals(CompetenceStatus.Test.APPROVED,
                    CompetenceStatusCalculator.testStatus(V1,
                            List.of(pass(V1, 2, DecisionType.APPROVED)), CADENCE, NOW));
        }
    }
}
