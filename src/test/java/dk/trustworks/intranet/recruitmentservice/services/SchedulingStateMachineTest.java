package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.CANCELLED;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.DRAFT;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.EXPIRED;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.FINALIZING;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.HANDED_BACK;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.HOLDING_OPTIONS;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.READY_FOR_CANDIDATE;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.SCHEDULED;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.SEARCHING;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.WAITING_FOR_CANDIDATE;
import static dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingRequestStatus.WAITING_FOR_INTERVIEWERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The EXHAUSTIVE transition matrix of the Method B request state machine
 * (plan §8.2, spec §15): every ordered pair of states is asserted, so a
 * transition added or dropped by accident fails loud here. Pure unit
 * test — the DB-free tier that gates deploys.
 */
class SchedulingStateMachineTest {

    /** The full expected matrix — the spec §15 flow, verbatim. */
    private static final Map<SchedulingRequestStatus, Set<SchedulingRequestStatus>> EXPECTED =
            new EnumMap<>(SchedulingRequestStatus.class);

    static {
        EXPECTED.put(DRAFT, EnumSet.of(SEARCHING, HANDED_BACK, CANCELLED));
        EXPECTED.put(SEARCHING, EnumSet.of(WAITING_FOR_INTERVIEWERS, HANDED_BACK, CANCELLED));
        EXPECTED.put(WAITING_FOR_INTERVIEWERS,
                EnumSet.of(SEARCHING, HOLDING_OPTIONS, READY_FOR_CANDIDATE,
                        HANDED_BACK, CANCELLED));
        EXPECTED.put(HOLDING_OPTIONS,
                EnumSet.of(READY_FOR_CANDIDATE, WAITING_FOR_INTERVIEWERS, SEARCHING,
                        HANDED_BACK, CANCELLED));
        EXPECTED.put(READY_FOR_CANDIDATE,
                EnumSet.of(WAITING_FOR_CANDIDATE, HOLDING_OPTIONS,
                        WAITING_FOR_INTERVIEWERS, SEARCHING, HANDED_BACK, CANCELLED));
        EXPECTED.put(WAITING_FOR_CANDIDATE,
                EnumSet.of(FINALIZING, EXPIRED, HANDED_BACK, CANCELLED));
        EXPECTED.put(FINALIZING,
                EnumSet.of(SCHEDULED, WAITING_FOR_CANDIDATE, SEARCHING,
                        HANDED_BACK, CANCELLED));
        EXPECTED.put(SCHEDULED, EnumSet.noneOf(SchedulingRequestStatus.class));
        EXPECTED.put(HANDED_BACK, EnumSet.noneOf(SchedulingRequestStatus.class));
        EXPECTED.put(EXPIRED, EnumSet.noneOf(SchedulingRequestStatus.class));
        EXPECTED.put(CANCELLED, EnumSet.noneOf(SchedulingRequestStatus.class));
    }

    @Test
    void matrix_isExhaustivelyCorrect() {
        for (SchedulingRequestStatus from : SchedulingRequestStatus.values()) {
            for (SchedulingRequestStatus to : SchedulingRequestStatus.values()) {
                boolean expected = EXPECTED.get(from).contains(to);
                assertEquals(expected, SchedulingStateMachine.canTransition(from, to),
                        "transition " + from + " -> " + to);
            }
        }
    }

    @Test
    void everyStateIsMapped() {
        for (SchedulingRequestStatus from : SchedulingRequestStatus.values()) {
            assertEquals(EXPECTED.get(from), SchedulingStateMachine.allowedTargets(from),
                    "targets of " + from);
        }
    }

    @Test
    void terminalStates_haveNoExits_andMatchIsTerminal() {
        for (SchedulingRequestStatus status : SchedulingRequestStatus.values()) {
            assertEquals(status.isTerminal(),
                    SchedulingStateMachine.allowedTargets(status).isEmpty(),
                    "terminal consistency of " + status);
        }
    }

    @Test
    void selfTransitions_neverExist() {
        for (SchedulingRequestStatus status : SchedulingRequestStatus.values()) {
            assertFalse(SchedulingStateMachine.canTransition(status, status),
                    "self transition of " + status);
        }
    }

    @Test
    void cancelReachableFromEveryNonTerminalState() {
        for (SchedulingRequestStatus status : SchedulingRequestStatus.values()) {
            if (!status.isTerminal()) {
                assertTrue(SchedulingStateMachine.canTransition(status, CANCELLED),
                        status + " must be cancellable");
            }
        }
    }

    @Test
    void require_throwsOnIllegal_passesOnLegal() {
        assertThrows(IllegalStateException.class,
                () -> SchedulingStateMachine.require(DRAFT, SCHEDULED));
        SchedulingStateMachine.require(DRAFT, SEARCHING); // no throw
    }
}
