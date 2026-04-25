package dk.trustworks.intranet.recruitmentservice.domain.statemachines;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ApplicationStage;
import dk.trustworks.intranet.recruitmentservice.domain.enums.PipelineKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStateMachineTest {

    @Test
    void consultantPipelineLinearAdvance() {
        var allowed = ApplicationStateMachine.allowedTransitions(
                ApplicationStage.SCREENING, PipelineKind.CONSULTANT);
        assertTrue(allowed.contains(ApplicationStage.FIRST_INTERVIEW));
        assertFalse(allowed.contains(ApplicationStage.OFFER),
                "CONSULTANT pipeline must run interviews before OFFER");
        assertTrue(allowed.contains(ApplicationStage.REJECTED));
        assertTrue(allowed.contains(ApplicationStage.WITHDRAWN));
        assertTrue(allowed.contains(ApplicationStage.TALENT_POOL));
    }

    @Test
    void otherPipelineAllowsDirectScreeningToOffer() {
        var allowed = ApplicationStateMachine.allowedTransitions(
                ApplicationStage.SCREENING, PipelineKind.OTHER);
        assertTrue(allowed.contains(ApplicationStage.OFFER));
        assertTrue(allowed.contains(ApplicationStage.FIRST_INTERVIEW));
    }

    @Test
    void talentPoolOnlyFromScreeningOrContacted() {
        assertTrue(ApplicationStateMachine.allowedTransitions(
                ApplicationStage.CONTACTED, PipelineKind.CONSULTANT).contains(ApplicationStage.TALENT_POOL));
        assertFalse(ApplicationStateMachine.allowedTransitions(
                ApplicationStage.FIRST_INTERVIEW, PipelineKind.CONSULTANT).contains(ApplicationStage.TALENT_POOL));
    }

    @Test
    void terminalStagesHaveNoOnwardTransitions() {
        assertTrue(ApplicationStateMachine.allowedTransitions(
                ApplicationStage.CONVERTED, PipelineKind.CONSULTANT).isEmpty());
        assertTrue(ApplicationStateMachine.allowedTransitions(
                ApplicationStage.REJECTED, PipelineKind.CONSULTANT).isEmpty());
        assertTrue(ApplicationStateMachine.allowedTransitions(
                ApplicationStage.WITHDRAWN, PipelineKind.CONSULTANT).isEmpty());
    }

    @Test
    void illegalTransitionThrows() {
        assertThrows(InvalidTransitionException.class,
                () -> ApplicationStateMachine.assertTransitionAllowed(
                        ApplicationStage.SOURCED, ApplicationStage.OFFER, PipelineKind.CONSULTANT));
    }
}
