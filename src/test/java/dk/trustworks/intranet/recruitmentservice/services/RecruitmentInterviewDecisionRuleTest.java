package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the blind rule's "after decision" condition (V519 extension): a
 * pending recorded decision unlocks exactly like the stage move always
 * did — and clearing it (which every stage move does) re-locks. DB-free
 * because content visibility is a rule worth pinning in the deploy-gate
 * tier.
 */
class RecruitmentInterviewDecisionRuleTest {

    @Test
    void pendingRecordedDecision_countsAsDecided() {
        RecruitmentInterview interview = round(1);
        interview.setDecidedAt(LocalDateTime.of(2026, 8, 19, 9, 0));
        assertTrue(RecruitmentInterviewService.decisionMade(
                application(RecruitmentStage.INTERVIEW_1), interview));
    }

    @Test
    void sameStage_noPendingDecision_isNotDecided() {
        assertFalse(RecruitmentInterviewService.decisionMade(
                application(RecruitmentStage.INTERVIEW_1), round(1)));
    }

    @Test
    void stageMovedPastRound_isDecided_asBefore() {
        assertTrue(RecruitmentInterviewService.decisionMade(
                application(RecruitmentStage.INTERVIEW_2), round(1)));
    }

    /** A back-move re-locks: stage moves clear pending decisions, so the
     * rewound round presents as undecided again. */
    @Test
    void backMovedRound_withClearedDecision_reLocks() {
        RecruitmentInterview interview = round(2);
        // cleared by the move (RecruitmentApplicationService#clearPendingDecisions)
        interview.setDecision(null);
        interview.setDecidedAt(null);
        assertFalse(RecruitmentInterviewService.decisionMade(
                application(RecruitmentStage.INTERVIEW_2), interview));
    }

    @Test
    void terminalAndHired_stayDecided() {
        RecruitmentApplication terminal = application(RecruitmentStage.INTERVIEW_1);
        terminal.setTerminal(RecruitmentApplicationTerminal.REJECTED);
        assertTrue(RecruitmentInterviewService.decisionMade(terminal, round(1)));
        assertTrue(RecruitmentInterviewService.decisionMade(
                application(RecruitmentStage.HIRED), round(1)));
    }

    private static RecruitmentApplication application(RecruitmentStage stage) {
        RecruitmentApplication application = new RecruitmentApplication();
        application.setUuid("app-1");
        application.setStage(stage);
        return application;
    }

    private static RecruitmentInterview round(int round) {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid("interview-" + round);
        interview.setApplicationUuid("app-1");
        interview.setKind(RecruitmentInterviewKind.ROUND);
        interview.setRound(round);
        return interview;
    }
}
