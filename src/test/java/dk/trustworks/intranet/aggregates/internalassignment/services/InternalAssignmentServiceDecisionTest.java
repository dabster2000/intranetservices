package dk.trustworks.intranet.aggregates.internalassignment.services;

import dk.trustworks.intranet.aggregates.internalassignment.model.InternalAssignment.Status;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The approve / reject gate (spec §4.3.4, §4.3.6): {@code teams:write} reach over the
 * assignee, never the assignee themselves, and a state machine that cannot be replayed.
 * The rule is a pure function so it is tested without a database; the service's own
 * {@code decide} call is the single enforcement point every endpoint passes through.
 */
class InternalAssignmentServiceDecisionTest {

    private static final String LEAD = "lead-uuid";
    private static final String JUNIOR = "junior-uuid";

    @Test
    void aLeadWithReachApprovesADraft() {
        assertNull(InternalAssignmentService.decide(LEAD, JUNIOR, true, Status.DRAFT, Status.APPROVED));
        assertNull(InternalAssignmentService.decide(LEAD, JUNIOR, true, Status.DRAFT, Status.REJECTED));
        assertNull(InternalAssignmentService.decide(LEAD, JUNIOR, true, Status.APPROVED, Status.REJECTED));
    }

    @Test
    void selfApprovalIsRefused() {
        assertEquals("You cannot approve your own assignment",
                InternalAssignmentService.decide(JUNIOR, JUNIOR, true, Status.DRAFT, Status.APPROVED));
        assertEquals("You cannot reject your own assignment",
                InternalAssignmentService.decide(JUNIOR.toUpperCase(), JUNIOR, true, Status.DRAFT, Status.REJECTED));
    }

    @Test
    void outsideReachIsRefusedBeforeTheSelfCheckLeaksAnything() {
        assertEquals("Outside your data scope",
                InternalAssignmentService.decide(LEAD, JUNIOR, false, Status.DRAFT, Status.APPROVED));
        assertEquals("Outside your data scope",
                InternalAssignmentService.decide(JUNIOR, JUNIOR, false, Status.DRAFT, Status.APPROVED));
    }

    @Test
    void headerlessCallersCannotDecide() {
        assertEquals("X-Requested-By is required — decisions always act for a named user",
                InternalAssignmentService.decide(null, JUNIOR, true, Status.DRAFT, Status.APPROVED));
    }

    @Test
    void stateMachineRefusesReplaysAndResurrection() {
        assertEquals("The assignment is already approved",
                InternalAssignmentService.decide(LEAD, JUNIOR, true, Status.APPROVED, Status.APPROVED));
        assertEquals("The assignment is already rejected",
                InternalAssignmentService.decide(LEAD, JUNIOR, true, Status.REJECTED, Status.REJECTED));
        assertEquals("A rejected assignment cannot be approved — ask the junior to create it again",
                InternalAssignmentService.decide(LEAD, JUNIOR, true, Status.REJECTED, Status.APPROVED));
    }

    @Test
    void affectedDaysAreTheWeekdaysOfThePeriod() {
        List<LocalDate> days = InternalAssignmentService.weekdays(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 13));
        // Fri 9, (Sat 10, Sun 11 skipped), Mon 12, Tue 13
        assertEquals(List.of(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13)), days);
        assertEquals(0, InternalAssignmentService.weekdays(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 9)).size());
    }
}
