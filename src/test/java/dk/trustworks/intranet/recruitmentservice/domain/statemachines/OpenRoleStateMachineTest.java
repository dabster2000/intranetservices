package dk.trustworks.intranet.recruitmentservice.domain.statemachines;

import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenRoleStateMachineTest {

    @Test
    void draftCanGoToSourcingOrCancelled() {
        Set<RoleStatus> allowed = OpenRoleStateMachine.allowedTransitions(RoleStatus.DRAFT);
        assertEquals(Set.of(RoleStatus.SOURCING, RoleStatus.CANCELLED), allowed);
    }

    @Test
    void sourcingMayAdvanceToInterviewingPauseOrCancel() {
        // Slice 3a: SOURCING → INTERVIEWING is auto-advanced by
        // OpenRoleService.advanceToInterviewingIfSourcing when the first
        // interview is scheduled. The Slice 1 derived-state-only model is
        // superseded — the explicit forward transition is now legal.
        Set<RoleStatus> allowed = OpenRoleStateMachine.allowedTransitions(RoleStatus.SOURCING);
        assertEquals(Set.of(RoleStatus.INTERVIEWING, RoleStatus.PAUSED, RoleStatus.CANCELLED), allowed);
    }

    @Test
    void sourcingToInterviewingIsLegal() {
        assertDoesNotThrow(() ->
                OpenRoleStateMachine.assertTransitionAllowed(RoleStatus.SOURCING, RoleStatus.INTERVIEWING));
    }

    @Test
    void filledIsTerminalExceptForCancellationGuard() {
        Set<RoleStatus> allowed = OpenRoleStateMachine.allowedTransitions(RoleStatus.FILLED);
        assertEquals(Set.of(), allowed,
                "FILLED is terminal in v1 — only the cancellation precondition lives elsewhere");
    }

    @Test
    void illegalTransitionThrowsWithAllowedList() {
        InvalidTransitionException ex = assertThrows(InvalidTransitionException.class,
                () -> OpenRoleStateMachine.assertTransitionAllowed(RoleStatus.FILLED, RoleStatus.SOURCING));
        assertTrue(ex.getMessage().contains("FILLED"));
        assertTrue(ex.allowedTransitions().isEmpty());
    }

    @Test
    void pausedReturnsToSourcingByDefault() {
        // Slice 1: no children yet — paused → resume → sourcing
        assertEquals(RoleStatus.SOURCING,
                OpenRoleStateMachine.deriveResumedStatus(false, false, false));
    }

    @Test
    void pausedReturnsToOfferIfOfferExists() {
        assertEquals(RoleStatus.OFFER,
                OpenRoleStateMachine.deriveResumedStatus(true, true, true));
    }

    @Test
    void pausedReturnsToInterviewingIfOnlyInterviewExists() {
        assertEquals(RoleStatus.INTERVIEWING,
                OpenRoleStateMachine.deriveResumedStatus(false, true, false));
    }
}
