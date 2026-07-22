package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication.MoveDirection;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication.StageMove;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P4 stage machine in isolation (spec §4.2 invariants 1 and 3): moves
 * validate against the position's ordered stage SET (never a global list),
 * back moves are flagged, HIRED is unreachable, and a terminal application
 * rejects every further mutation.
 */
class RecruitmentApplicationStageMachineTest {

    /** The default practice-track pipeline. */
    private static final List<String> FULL_SET = List.of(
            "SCREENING", "INTERVIEW_1", "INTERVIEW_2", "OFFER", "HIRED");
    /** A trimmed staff-track pipeline (single interview round). */
    private static final List<String> TRIMMED_SET = List.of(
            "SCREENING", "INTERVIEW_1", "OFFER", "HIRED");

    private RecruitmentApplication openApplication(RecruitmentStage stage) {
        RecruitmentApplication application = new RecruitmentApplication();
        application.setUuid("app-under-test");
        application.setCandidateUuid("candidate");
        application.setPositionUuid("position");
        application.setStage(stage);
        application.setStageEnteredAt(LocalDateTime.now().minusDays(3));
        return application;
    }

    // ---- Legal moves --------------------------------------------------------------

    @Test
    void singleForwardMove_isForwardAndNotSkipped() {
        RecruitmentApplication application = openApplication(RecruitmentStage.SCREENING);
        StageMove move = application.moveToStage(RecruitmentStage.INTERVIEW_1, FULL_SET);

        assertEquals(RecruitmentStage.SCREENING, move.from());
        assertEquals(RecruitmentStage.INTERVIEW_1, move.to());
        assertEquals(MoveDirection.FORWARD, move.direction());
        assertFalse(move.skippedStages());
        assertEquals(RecruitmentStage.INTERVIEW_1, application.getStage());
    }

    @Test
    void forwardSkip_isFlaggedSkipped() {
        RecruitmentApplication application = openApplication(RecruitmentStage.SCREENING);
        StageMove move = application.moveToStage(RecruitmentStage.OFFER, FULL_SET);
        assertEquals(MoveDirection.FORWARD, move.direction());
        assertTrue(move.skippedStages(), "SCREENING → OFFER skips two stages");
    }

    @Test
    void backMove_isFlaggedBack_neverSilent() {
        RecruitmentApplication application = openApplication(RecruitmentStage.INTERVIEW_2);
        StageMove move = application.moveToStage(RecruitmentStage.INTERVIEW_1, FULL_SET);
        assertEquals(MoveDirection.BACK, move.direction());
        assertFalse(move.skippedStages());
    }

    @Test
    void moveUpdatesStageEnteredAt_forIdleDetection() {
        RecruitmentApplication application = openApplication(RecruitmentStage.SCREENING);
        LocalDateTime before = application.getStageEnteredAt();
        application.moveToStage(RecruitmentStage.INTERVIEW_1, FULL_SET);
        assertTrue(application.getStageEnteredAt().isAfter(before),
                "stage_entered_at must reset on every move");
    }

    @Test
    void trimmedStageSet_movesFollowTheSubset() {
        // INTERVIEW_1 → OFFER is a single step in the trimmed set even though
        // the canonical order has INTERVIEW_2 in between.
        RecruitmentApplication application = openApplication(RecruitmentStage.INTERVIEW_1);
        StageMove move = application.moveToStage(RecruitmentStage.OFFER, TRIMMED_SET);
        assertEquals(MoveDirection.FORWARD, move.direction());
        assertFalse(move.skippedStages(), "adjacent in the position's SET = not a skip");
    }

    // ---- Illegal moves ---------------------------------------------------------------

    @Test
    void hired_isUnreachableViaStageMove() {
        RecruitmentApplication application = openApplication(RecruitmentStage.OFFER);
        assertThrows(BusinessRuleViolation.class,
                () -> application.moveToStage(RecruitmentStage.HIRED, FULL_SET),
                "HIRED only via signing completion → conversion (spec §4.2 invariant 3)");
    }

    @Test
    void stageOutsideThePositionsSet_isRejected() {
        RecruitmentApplication application = openApplication(RecruitmentStage.SCREENING);
        assertThrows(BusinessRuleViolation.class,
                () -> application.moveToStage(RecruitmentStage.INTERVIEW_2, TRIMMED_SET),
                "INTERVIEW_2 is not part of the trimmed set");
    }

    @Test
    void noOpMove_isRejected() {
        RecruitmentApplication application = openApplication(RecruitmentStage.SCREENING);
        assertThrows(BusinessRuleViolation.class,
                () -> application.moveToStage(RecruitmentStage.SCREENING, FULL_SET));
    }

    // ---- Terminals --------------------------------------------------------------------

    @Test
    void reject_requiresReasonCode_andSetsTerminal() {
        RecruitmentApplication application = openApplication(RecruitmentStage.INTERVIEW_1);
        assertThrows(NullPointerException.class, () -> application.reject(null));

        application.reject(RecruitmentRejectionReason.PROFILE_MISMATCH);
        assertEquals(RecruitmentApplicationTerminal.REJECTED, application.getTerminal());
        assertEquals(RecruitmentRejectionReason.PROFILE_MISMATCH, application.getRejectionReasonCode());
        assertEquals(RecruitmentStage.INTERVIEW_1, application.getStage(),
                "the stage where the application ended is preserved for reporting");
    }

    @Test
    void withdrawAndReturnToPool_setTheirTerminals() {
        RecruitmentApplication withdrawn = openApplication(RecruitmentStage.SCREENING);
        withdrawn.withdraw();
        assertEquals(RecruitmentApplicationTerminal.WITHDRAWN, withdrawn.getTerminal());
        assertNull(withdrawn.getRejectionReasonCode());

        RecruitmentApplication pooled = openApplication(RecruitmentStage.OFFER);
        pooled.returnToPool();
        assertEquals(RecruitmentApplicationTerminal.RETURNED_TO_POOL, pooled.getTerminal());
    }

    @Test
    void terminalApplication_rejectsEveryFurtherMutation() {
        RecruitmentApplication application = openApplication(RecruitmentStage.INTERVIEW_1);
        application.reject(RecruitmentRejectionReason.OTHER);

        assertThrows(BusinessRuleViolation.class,
                () -> application.moveToStage(RecruitmentStage.INTERVIEW_2, FULL_SET));
        assertThrows(BusinessRuleViolation.class,
                () -> application.reject(RecruitmentRejectionReason.TIMING));
        assertThrows(BusinessRuleViolation.class, application::withdraw);
        assertThrows(BusinessRuleViolation.class, application::returnToPool);
        assertThrows(BusinessRuleViolation.class, () -> application.assignTeam("team"));
    }

    @Test
    void assignTeam_onOpenApplication_setsTheTeam() {
        RecruitmentApplication application = openApplication(RecruitmentStage.OFFER);
        application.assignTeam("team-uuid");
        assertEquals("team-uuid", application.getAssignedTeamUuid());
    }
}
