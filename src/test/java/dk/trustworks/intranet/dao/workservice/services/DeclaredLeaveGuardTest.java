package dk.trustworks.intranet.dao.workservice.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static dk.trustworks.intranet.dao.workservice.services.WorkService.SICKNESS;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.VACATION;
import static dk.trustworks.intranet.dao.workservice.services.WorkService.WORK_HOURS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Spec §4.2.2 / §4.2.6: a leave registration above the declared day is refused for a
 * declaring STUDENT, the same row passes untouched for everyone else, and SHADOW is inert
 * (the {@code declaring} flag is false for every user in SHADOW — see
 * {@code DeclaredAvailabilityPolicyTest}).
 */
class DeclaredLeaveGuardTest {

    private static final BigDecimal FIVE = new BigDecimal("5.00");

    @Test
    void declaringStudent_leaveWithinDeclaredDay_passes() {
        assertNull(DeclaredLeaveGuard.check(true, SICKNESS, 5.0, FIVE));
        assertNull(DeclaredLeaveGuard.check(true, SICKNESS, 2.5, FIVE));
        assertNull(DeclaredLeaveGuard.check(true, VACATION, 5.0, FIVE));
    }

    @Test
    void declaringStudent_leaveAboveDeclaredDay_isRefusedNamingTheLimit() {
        assertEquals("Sick leave cannot exceed your declared 5-hour day.",
                DeclaredLeaveGuard.check(true, SICKNESS, 7.4, FIVE));
        assertEquals("Vacation cannot exceed your declared 7,5-hour day.",
                DeclaredLeaveGuard.check(true, VACATION, 8.0, new BigDecimal("7.50")));
    }

    @Test
    void declaringStudent_undeclaredDay_isToldToDeclareFirst() {
        String message = DeclaredLeaveGuard.check(true, SICKNESS, 3.0, BigDecimal.ZERO);
        assertEquals("This day has no declared hours. Declare the day on your profile first (Profile → Availability), then register the absence.", message);
        assertEquals(message, DeclaredLeaveGuard.check(true, SICKNESS, 3.0, null));
    }

    @Test
    void notDeclaring_sameRowPassesUntouched() {
        // A CONSULTANT (or any STUDENT in SHADOW / before the floor) keeps today's behaviour:
        // no server-side bound at all.
        assertNull(DeclaredLeaveGuard.check(false, SICKNESS, 7.4, new BigDecimal("3.00")));
        assertNull(DeclaredLeaveGuard.check(false, SICKNESS, 7.4, BigDecimal.ZERO));
        assertNull(DeclaredLeaveGuard.check(false, VACATION, 7.4, null));
    }

    @Test
    void nonLeaveTasksAndZeroHoursAreNeverBounded() {
        assertNull(DeclaredLeaveGuard.check(true, WORK_HOURS, 12.0, FIVE));
        assertNull(DeclaredLeaveGuard.check(true, SICKNESS, 0.0, BigDecimal.ZERO));
    }
}
