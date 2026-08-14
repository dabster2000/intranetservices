package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator.AttemptFact;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator.CompletionFact;
import dk.trustworks.intranet.competenceservice.model.DecisionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §5.3–§5.6. Table-driven over every branch, because these five small decisions are
 * what the whole module's colour, gating and evidence rest on.
 */
class CompetenceStatusCalculatorTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 9, 0);
    private static final int CADENCE = 365;

    private static CompletionFact completed(String label, int daysAgo) {
        return new CompletionFact(label, NOW.minusDays(daysAgo));
    }

    private static AttemptFact pass(String label, int daysAgo, DecisionType decision) {
        return new AttemptFact(label, NOW.minusDays(daysAgo), true, decision);
    }

    private static AttemptFact fail(String label, int daysAgo) {
        return new AttemptFact(label, NOW.minusDays(daysAgo), false, null);
    }

    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("microcourse status (§5.3)")
    class CourseStatus {

        @Test
        void noActiveVersionIsNotPublished() {
            assertEquals(CompetenceStatus.Course.NOT_PUBLISHED,
                    CompetenceStatusCalculator.courseStatus(null, List.of(), CADENCE, NOW));
        }

        @Test
        void noCompletionIsNotStarted() {
            assertEquals(CompetenceStatus.Course.NOT_STARTED,
                    CompetenceStatusCalculator.courseStatus("2026-08", List.of(), CADENCE, NOW));
        }

        @Test
        void currentAndFreshIsCompleted() {
            assertEquals(CompetenceStatus.Course.COMPLETED,
                    CompetenceStatusCalculator.courseStatus("2026-08",
                            List.of(completed("2026-08", 10)), CADENCE, NOW));
        }

        @Test
        void olderVersionRequiresRetake() {
            assertEquals(CompetenceStatus.Course.RETAKE_REQUIRED,
                    CompetenceStatusCalculator.courseStatus("2026-09",
                            List.of(completed("2026-08", 10)), CADENCE, NOW));
        }

        @Test
        void currentVersionPastCadenceIsOverdue() {
            assertEquals(CompetenceStatus.Course.OVERDUE,
                    CompetenceStatusCalculator.courseStatus("2026-08",
                            List.of(completed("2026-08", 400)), CADENCE, NOW));
        }

        @Test
        @DisplayName("version comparison runs BEFORE the cadence check")
        void staleAndOldVersionReadsAsRetakeNotOverdue() {
            // Both conditions hold. The retake is the action the person must take, so
            // reporting 'overdue' would send them to the wrong place.
            assertEquals(CompetenceStatus.Course.RETAKE_REQUIRED,
                    CompetenceStatusCalculator.courseStatus("2026-09",
                            List.of(completed("2026-08", 400)), CADENCE, NOW));
        }

        @Test
        void latestCompletionWins() {
            assertEquals(CompetenceStatus.Course.COMPLETED,
                    CompetenceStatusCalculator.courseStatus("2026-09",
                            List.of(completed("2026-08", 100), completed("2026-09", 5)), CADENCE, NOW));
        }

        @Test
        @DisplayName("exactly at the cadence boundary is not yet overdue")
        void boundaryIsInclusive() {
            assertEquals(CompetenceStatus.Course.COMPLETED,
                    CompetenceStatusCalculator.courseStatus("2026-08",
                            List.of(completed("2026-08", CADENCE)), CADENCE, NOW));
            assertEquals(CompetenceStatus.Course.OVERDUE,
                    CompetenceStatusCalculator.courseStatus("2026-08",
                            List.of(completed("2026-08", CADENCE + 1)), CADENCE, NOW));
        }
    }

    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("test status (§5.4)")
    class TestStatus {

        @Test
        void noActiveVersionIsNotPublished() {
            assertEquals(CompetenceStatus.Test.NOT_PUBLISHED,
                    CompetenceStatusCalculator.testStatus(null, List.of(), CADENCE, NOW));
        }

        @Test
        void noAttemptsIsNotPassed() {
            assertEquals(CompetenceStatus.Test.NOT_PASSED,
                    CompetenceStatusCalculator.testStatus("2026-08", List.of(), CADENCE, NOW));
        }

        @Test
        void onlyFailedAttemptsIsNotPassed() {
            assertEquals(CompetenceStatus.Test.NOT_PASSED,
                    CompetenceStatusCalculator.testStatus("2026-08",
                            List.of(fail("2026-08", 3), fail("2026-08", 1)), CADENCE, NOW));
        }

        @Test
        void passAwaitingDecisionIsAwaitingApproval() {
            assertEquals(CompetenceStatus.Test.AWAITING_APPROVAL,
                    CompetenceStatusCalculator.testStatus("2026-08",
                            List.of(pass("2026-08", 2, null)), CADENCE, NOW));
        }

        @Test
        void approvedPassIsApproved() {
            assertEquals(CompetenceStatus.Test.APPROVED,
                    CompetenceStatusCalculator.testStatus("2026-08",
                            List.of(pass("2026-08", 2, DecisionType.APPROVED)), CADENCE, NOW));
        }

        @Test
        @DisplayName("a revoked pass stops counting as evidence")
        void revokedPassIsNotPassed() {
            assertEquals(CompetenceStatus.Test.NOT_PASSED,
                    CompetenceStatusCalculator.testStatus("2026-08",
                            List.of(pass("2026-08", 2, DecisionType.REVOKED)), CADENCE, NOW));
        }

        @Test
        void revokedPassFallsBackToAnEarlierGoodOne() {
            assertEquals(CompetenceStatus.Test.APPROVED,
                    CompetenceStatusCalculator.testStatus("2026-08",
                            List.of(pass("2026-08", 2, DecisionType.REVOKED),
                                    pass("2026-08", 30, DecisionType.APPROVED)), CADENCE, NOW));
        }

        @Test
        void olderVersionRequiresRetake() {
            assertEquals(CompetenceStatus.Test.RETAKE_REQUIRED,
                    CompetenceStatusCalculator.testStatus("2026-09",
                            List.of(pass("2026-08", 2, DecisionType.APPROVED)), CADENCE, NOW));
        }

        @Test
        void staleApprovedPassIsOverdue() {
            assertEquals(CompetenceStatus.Test.OVERDUE,
                    CompetenceStatusCalculator.testStatus("2026-08",
                            List.of(pass("2026-08", 400, DecisionType.APPROVED)), CADENCE, NOW));
        }
    }

    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("matrix cell (§5.5) — ranked by urgency, not progress")
    class CellStatus {

        @Test
        void notPublishedOutranksEverything() {
            assertEquals(CompetenceStatus.Cell.NOT_PUBLISHED,
                    CompetenceStatusCalculator.cellStatus(
                            CompetenceStatus.Course.NOT_PUBLISHED, CompetenceStatus.Test.APPROVED));
            assertEquals(CompetenceStatus.Cell.NOT_PUBLISHED,
                    CompetenceStatusCalculator.cellStatus(
                            CompetenceStatus.Course.COMPLETED, CompetenceStatus.Test.NOT_PUBLISHED));
        }

        @Test
        void overdueOutranksRetake() {
            assertEquals(CompetenceStatus.Cell.OVERDUE,
                    CompetenceStatusCalculator.cellStatus(
                            CompetenceStatus.Course.OVERDUE, CompetenceStatus.Test.RETAKE_REQUIRED));
        }

        @Test
        void retakeOutranksEverythingBelow() {
            assertEquals(CompetenceStatus.Cell.RETAKE_REQUIRED,
                    CompetenceStatusCalculator.cellStatus(
                            CompetenceStatus.Course.COMPLETED, CompetenceStatus.Test.RETAKE_REQUIRED));
        }

        @Test
        void nothingDoneIsNotStarted() {
            assertEquals(CompetenceStatus.Cell.NOT_STARTED,
                    CompetenceStatusCalculator.cellStatus(
                            CompetenceStatus.Course.NOT_STARTED, CompetenceStatus.Test.NOT_PASSED));
        }

        @Test
        void courseDoneButNoPass() {
            assertEquals(CompetenceStatus.Cell.COURSE_COMPLETED,
                    CompetenceStatusCalculator.cellStatus(
                            CompetenceStatus.Course.COMPLETED, CompetenceStatus.Test.NOT_PASSED));
        }

        @Test
        void passAwaitingApproval() {
            assertEquals(CompetenceStatus.Cell.AWAITING_APPROVAL,
                    CompetenceStatusCalculator.cellStatus(
                            CompetenceStatus.Course.COMPLETED, CompetenceStatus.Test.AWAITING_APPROVAL));
        }

        @Test
        void approvedIsTheOnlyGreen() {
            assertEquals(CompetenceStatus.Cell.APPROVED,
                    CompetenceStatusCalculator.cellStatus(
                            CompetenceStatus.Course.COMPLETED, CompetenceStatus.Test.APPROVED));
        }

        @Test
        @DisplayName("a spotless person on a republished requirement still reads amber")
        void republishedRequirementTurnsAGoodRecordAmber() {
            assertEquals(CompetenceStatus.Cell.RETAKE_REQUIRED,
                    CompetenceStatusCalculator.cellStatus(
                            CompetenceStatus.Course.RETAKE_REQUIRED, CompetenceStatus.Test.APPROVED));
        }
    }

    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("the hard gate (§5.6)")
    class Gate {

        @Test
        void onlyAGreenCourseOpensTheGate() {
            assertTrue(CompetenceStatusCalculator.courseGateOpen(CompetenceStatus.Course.COMPLETED));
            for (CompetenceStatus.Course closed : List.of(
                    CompetenceStatus.Course.NOT_PUBLISHED,
                    CompetenceStatus.Course.NOT_STARTED,
                    CompetenceStatus.Course.RETAKE_REQUIRED,
                    CompetenceStatus.Course.OVERDUE)) {
                assertFalse(CompetenceStatusCalculator.courseGateOpen(closed),
                        closed + " must not open the test");
            }
        }
    }

    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("same-label publish (§5.3)")
    class SameLabelPublish {

        @Test
        @DisplayName("republishing under the same label leaves everyone green")
        void sameLabelDoesNotMoveAnyone() {
            // The comparison is on the label, not on the version uuid. This is why the
            // publish dialog has to say so out loud: an author who bumps the content but
            // not the label silently retrains nobody.
            assertEquals(CompetenceStatus.Course.COMPLETED,
                    CompetenceStatusCalculator.courseStatus("2026-08",
                            List.of(completed("2026-08", 10)), CADENCE, NOW));
            assertEquals(CompetenceStatus.Test.APPROVED,
                    CompetenceStatusCalculator.testStatus("2026-08",
                            List.of(pass("2026-08", 10, DecisionType.APPROVED)), CADENCE, NOW));
        }
    }
}
