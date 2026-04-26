package dk.trustworks.intranet.recruitmentservice.domain.statemachines;

import dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus;
import org.junit.jupiter.api.Test;
import static dk.trustworks.intranet.recruitmentservice.domain.enums.InterviewStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class InterviewStateMachineTest {

    private final InterviewStateMachine fsm = new InterviewStateMachine();

    @Test
    void scheduledToHeld_isLegal() {
        assertTrue(fsm.isLegalTransition(SCHEDULED, HELD));
    }

    @Test
    void heldToRoundedUp_isLegal() {
        assertTrue(fsm.isLegalTransition(HELD, ROUNDED_UP));
    }

    @Test
    void scheduledToCancelled_isLegal() {
        assertTrue(fsm.isLegalTransition(SCHEDULED, CANCELLED));
    }

    @Test
    void heldToCancelled_isLegal() {
        assertTrue(fsm.isLegalTransition(HELD, CANCELLED));
    }

    @Test
    void cancelledToScheduled_isLegal_forReschedule() {
        assertTrue(fsm.isLegalTransition(CANCELLED, SCHEDULED));
    }

    @Test
    void roundedUpIsTerminal_anyOnwardTransitionIllegal() {
        for (InterviewStatus to : InterviewStatus.values()) {
            assertFalse(fsm.isLegalTransition(ROUNDED_UP, to),
                "ROUNDED_UP→" + to + " must be illegal");
        }
    }

    @Test
    void scheduledToRoundedUp_isIllegal_mustGoThroughHeld() {
        assertFalse(fsm.isLegalTransition(SCHEDULED, ROUNDED_UP));
    }

    @Test
    void allowedTransitionsFromScheduled_returnsHeldAndCancelled() {
        var allowed = fsm.allowedTransitions(SCHEDULED);
        assertEquals(2, allowed.size());
        assertTrue(allowed.contains(HELD));
        assertTrue(allowed.contains(CANCELLED));
    }
}
