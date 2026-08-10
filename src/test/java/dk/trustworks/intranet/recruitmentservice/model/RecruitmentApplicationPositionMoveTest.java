package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication.PositionMove;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The re-filing rule in isolation: moving an application onto another
 * position keeps the SAME row (so everything keyed on its uuid follows),
 * preserves the stage whenever the target pipeline has it, clamps
 * <em>backwards</em> when it does not, and refuses on the states where
 * re-filing makes no sense (closed, hired, already there).
 */
class RecruitmentApplicationPositionMoveTest {

    /** The default practice-track pipeline. */
    private static final List<String> FULL_SET = List.of(
            "SCREENING", "INTERVIEW_1", "INTERVIEW_2", "OFFER", "HIRED");
    /** A trimmed staff-track pipeline (single interview round). */
    private static final List<String> TRIMMED_SET = List.of(
            "SCREENING", "INTERVIEW_1", "OFFER", "HIRED");
    /** The shortest legal pipeline — mandatory stages only. */
    private static final List<String> MINIMAL_SET = List.of("SCREENING", "OFFER", "HIRED");

    private RecruitmentApplication openApplication(RecruitmentStage stage) {
        RecruitmentApplication application = new RecruitmentApplication();
        application.setUuid("app-under-test");
        application.setCandidateUuid("candidate");
        application.setPositionUuid("position-a");
        application.setStage(stage);
        application.setStageEnteredAt(LocalDateTime.now().minusDays(3));
        return application;
    }

    // ---- The identity guarantee -----------------------------------------------

    @Test
    void move_repointsTheSameRow_neverClosesIt() {
        RecruitmentApplication application = openApplication(RecruitmentStage.INTERVIEW_1);
        PositionMove move = application.moveToPosition("position-b", FULL_SET);

        assertEquals("position-b", application.getPositionUuid());
        assertEquals("app-under-test", application.getUuid(),
                "the application identity must survive — interviews and scorecards key on it");
        assertFalse(application.isTerminal(),
                "a move is not a terminal: the candidate never left a pipeline");
        assertEquals("position-a", move.fromPositionUuid());
        assertEquals("position-b", move.toPositionUuid());
    }

    // ---- Stage preservation and clamping ---------------------------------------

    @Test
    void stageIsKept_whenTargetPipelineHasIt() {
        RecruitmentApplication application = openApplication(RecruitmentStage.INTERVIEW_1);
        LocalDateTime before = application.getStageEnteredAt();

        PositionMove move = application.moveToPosition("position-b", TRIMMED_SET);

        assertEquals(RecruitmentStage.INTERVIEW_1, move.toStage());
        assertEquals(RecruitmentStage.INTERVIEW_1, application.getStage());
        assertFalse(move.stageClamped());
        assertEquals(before, application.getStageEnteredAt(),
                "an unchanged stage keeps its timestamp — idle detection still measures the real wait");
    }

    @Test
    void stageIsClampedBackwards_whenTargetPipelineLacksIt() {
        // INTERVIEW_2 does not exist in the trimmed set: the latest earlier
        // stage it does have is INTERVIEW_1.
        RecruitmentApplication application = openApplication(RecruitmentStage.INTERVIEW_2);
        LocalDateTime before = application.getStageEnteredAt();

        PositionMove move = application.moveToPosition("position-b", TRIMMED_SET);

        assertEquals(RecruitmentStage.INTERVIEW_2, move.fromStage());
        assertEquals(RecruitmentStage.INTERVIEW_1, move.toStage());
        assertTrue(move.stageClamped());
        assertTrue(application.getStageEnteredAt().isAfter(before),
                "a clamp genuinely enters a different stage — the clock restarts");
    }

    @Test
    void clampNeverMovesForward() {
        // INTERVIEW_1 is absent from the minimal set. The only earlier stage
        // is SCREENING; OFFER is later and must never be chosen.
        RecruitmentApplication application = openApplication(RecruitmentStage.INTERVIEW_1);
        PositionMove move = application.moveToPosition("position-b", MINIMAL_SET);

        assertEquals(RecruitmentStage.SCREENING, move.toStage(),
                "a move must not silently advance a candidate");
        assertTrue(move.stageClamped());
    }

    @Test
    void clampFallsBackToFirstStage_whenNothingEarlierExists() {
        // A (hypothetical) pipeline that starts past the current stage.
        RecruitmentApplication application = openApplication(RecruitmentStage.SCREENING);
        PositionMove move = application.moveToPosition("position-b",
                List.of("INTERVIEW_1", "OFFER", "HIRED"));

        assertEquals(RecruitmentStage.INTERVIEW_1, move.toStage());
        assertTrue(move.stageClamped());
    }

    @Test
    void hiredIsNeverALandingStage() {
        // A degenerate target whose only non-HIRED stage is OFFER: an
        // application at OFFER keeps OFFER, and HIRED is never selected.
        RecruitmentApplication application = openApplication(RecruitmentStage.OFFER);
        PositionMove move = application.moveToPosition("position-b", List.of("OFFER", "HIRED"));

        assertEquals(RecruitmentStage.OFFER, move.toStage());
        assertFalse(move.stageClamped());
    }

    // ---- Refusals ---------------------------------------------------------------

    @Test
    void move_onTerminalApplication_isRejected() {
        RecruitmentApplication application = openApplication(RecruitmentStage.INTERVIEW_1);
        application.withdraw();

        assertThrows(BusinessRuleViolation.class,
                () -> application.moveToPosition("position-b", FULL_SET),
                "a closed application has nothing to re-file");
        assertEquals("position-a", application.getPositionUuid(), "position unchanged");
    }

    @Test
    void move_onHiredApplication_isRejected() {
        RecruitmentApplication application = openApplication(RecruitmentStage.OFFER);
        application.markHired();

        assertThrows(BusinessRuleViolation.class,
                () -> application.moveToPosition("position-b", FULL_SET),
                "a completed hire stays on the position it was made against");
        assertEquals("position-a", application.getPositionUuid(), "position unchanged");
        assertEquals(RecruitmentStage.HIRED, application.getStage(), "stage unchanged");
    }

    @Test
    void move_toTheSamePosition_isRejected() {
        RecruitmentApplication application = openApplication(RecruitmentStage.INTERVIEW_1);

        assertThrows(BusinessRuleViolation.class,
                () -> application.moveToPosition("position-a", FULL_SET),
                "a no-op move is a state error, not a silent success");
    }
}
