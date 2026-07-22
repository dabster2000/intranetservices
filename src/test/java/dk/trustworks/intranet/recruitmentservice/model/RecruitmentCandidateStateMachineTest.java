package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the {@link RecruitmentCandidate} state machine.
 * <p>
 * Verifies the once-only transition guard ({@code guardActive}) for each of
 * the three terminal-state mutators ({@code decline}, {@code withdraw},
 * {@code markHired}) and the happy-path field updates. No Quarkus context —
 * the entity is instantiated directly via its no-args constructor.
 */
class RecruitmentCandidateStateMachineTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private RecruitmentCandidate active() {
        RecruitmentCandidate c = new RecruitmentCandidate();
        c.setUuid(UUID.randomUUID().toString());
        c.setStatus(CandidateStatus.ACTIVE);
        return c;
    }

    // -- Happy path: decline --

    @Test
    void decline_fromActive_setsStatusAndReason() {
        RecruitmentCandidate c = active();

        c.decline("Not a fit", ACTOR);

        assertEquals(CandidateStatus.DECLINED, c.getStatus());
        assertEquals("Not a fit", c.getDeclineReason());
        assertTrue(c.isTerminal());
    }

    // -- Happy path: withdraw --

    @Test
    void withdraw_fromActive_setsStatusAndReason() {
        RecruitmentCandidate c = active();

        c.withdraw("Accepted other offer", ACTOR);

        assertEquals(CandidateStatus.WITHDRAWN, c.getStatus());
        assertEquals("Accepted other offer", c.getDeclineReason());
        assertTrue(c.isTerminal());
    }

    // -- Happy path: markHired --

    @Test
    void markHired_fromActive_setsStatusAndConvertedUserUuid() {
        RecruitmentCandidate c = active();
        UUID newUserUuid = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

        c.markHired(newUserUuid, ACTOR);

        assertEquals(CandidateStatus.HIRED, c.getStatus());
        assertEquals(newUserUuid.toString(), c.getConvertedUserUuid());
        assertTrue(c.isTerminal());
    }

    // -- Guards: decline rejected from non-ACTIVE --

    @ParameterizedTest
    @EnumSource(value = CandidateStatus.class, mode = Mode.EXCLUDE, names = "ACTIVE")
    void decline_fromNonActive_throwsBusinessRuleViolation(CandidateStatus terminalStatus) {
        RecruitmentCandidate c = active();
        c.setStatus(terminalStatus);

        assertThrows(BusinessRuleViolation.class,
                () -> c.decline("reason", ACTOR));
    }

    // -- Guards: withdraw rejected from non-ACTIVE --

    @ParameterizedTest
    @EnumSource(value = CandidateStatus.class, mode = Mode.EXCLUDE, names = "ACTIVE")
    void withdraw_fromNonActive_throwsBusinessRuleViolation(CandidateStatus terminalStatus) {
        RecruitmentCandidate c = active();
        c.setStatus(terminalStatus);

        assertThrows(BusinessRuleViolation.class,
                () -> c.withdraw("reason", ACTOR));
    }

    // -- Guards: markHired rejected from non-ACTIVE --

    @ParameterizedTest
    @EnumSource(value = CandidateStatus.class, mode = Mode.EXCLUDE, names = "ACTIVE")
    void markHired_fromNonActive_throwsBusinessRuleViolation(CandidateStatus terminalStatus) {
        RecruitmentCandidate c = active();
        c.setStatus(terminalStatus);
        UUID newUserUuid = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

        assertThrows(BusinessRuleViolation.class,
                () -> c.markHired(newUserUuid, ACTOR));
    }

    // -- isTerminal contract --

    @Test
    void isTerminal_isFalseForActiveAndPooled_isTrueForOthers() {
        // POOLED joined the vocabulary in the ATS expansion (plan §P3): a
        // talent-pool candidate is parked, not terminal — they re-enter the
        // funnel via unpool. ANONYMIZED (P19 end state) is terminal.
        RecruitmentCandidate c = active();
        assertEquals(false, c.isTerminal());

        for (CandidateStatus s : CandidateStatus.values()) {
            if (s == CandidateStatus.ACTIVE) continue;
            c.setStatus(s);
            if (s == CandidateStatus.POOLED) {
                assertEquals(false, c.isTerminal(), "POOLED is not terminal");
            } else {
                assertTrue(c.isTerminal(), "Expected terminal=true for status=" + s);
            }
        }
    }
}
