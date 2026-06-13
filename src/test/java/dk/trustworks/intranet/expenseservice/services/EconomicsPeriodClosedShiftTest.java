package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.model.Expense;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the closed-period auto-shift contract: the detector recognises e-conomic's
 * closed-period error variants, and the shift idempotency key is distinct from both
 * the standard and orphan-retry keys so e-conomic's cache treats it as fresh.
 */
class EconomicsPeriodClosedShiftTest {

    private EconomicsService serviceFor(String envId) {
        EconomicsService s = new EconomicsService();
        s.environmentId = envId;
        return s;
    }

    @Test
    void period_closed_error_detected_on_known_errorcodes() {
        EconomicsService s = serviceFor("production");

        assertTrue(s.isPeriodClosedError("{\"errorCode\":\"AccountingYearClosed\",\"status\":400}"));
        assertTrue(s.isPeriodClosedError("{\"errorCode\":\"EntryDateInClosedPeriod\",\"status\":400}"));
        assertTrue(s.isPeriodClosedError("{\"errorCode\":\"DateInClosedPeriod\",\"status\":400}"));
        assertTrue(s.isPeriodClosedError("{\"errorCode\":\"PeriodClosed\",\"status\":400}"));
        assertTrue(s.isPeriodClosedError("{\"errorCode\":\"ClosedAccountingYear\",\"status\":400}"));
    }

    @Test
    void period_closed_detector_ignores_other_400s() {
        EconomicsService s = serviceFor("production");

        assertFalse(s.isPeriodClosedError(null));
        assertFalse(s.isPeriodClosedError(""));
        assertFalse(s.isPeriodClosedError("{\"errorCode\":\"URLChanged\",\"status\":400}"));
        assertFalse(s.isPeriodClosedError("{\"errorCode\":\"ValidationFailed\",\"status\":400}"));
    }

    @Test
    void period_shift_key_distinct_from_standard_and_orphan_keys() {
        EconomicsService s = serviceFor("production");
        Expense e = new Expense();
        e.setUuid("abc-123");

        String standard = s.buildIdempotencyKey(e, 9);
        String shift1 = s.buildPeriodShiftIdempotencyKey(e, 1);
        String shift7 = s.buildPeriodShiftIdempotencyKey(e, 7);

        assertEquals("production-expense-abc-123-j9", standard);
        assertEquals("production-expense-abc-123-period-shift-1", shift1);
        assertEquals("production-expense-abc-123-period-shift-7", shift7);
    }

    @Test
    void period_shift_key_is_environment_scoped() {
        Expense e = new Expense();
        e.setUuid("abc-123");

        assertEquals("staging-expense-abc-123-period-shift-3",
                serviceFor("staging").buildPeriodShiftIdempotencyKey(e, 3));
        assertEquals("production-expense-abc-123-period-shift-3",
                serviceFor("production").buildPeriodShiftIdempotencyKey(e, 3));
    }
}
