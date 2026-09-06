package dk.trustworks.intranet.bi.services;

import dk.trustworks.intranet.bi.services.AvailabilityDayResolver.DayAvailability;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The fallback matrix behind spec §4.1.3 / D7: declaring or not × weekday / weekend /
 * Friday / October shutdown × declared / undeclared. DB-free.
 */
class AvailabilityDayResolverTest {

    private static final int ALLOCATION_37 = 37;
    private static final int ALLOCATION_15 = 15;

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 7);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);
    /** 1 Oct 2026 is a Thursday — the first Thursday in October is the shutdown day. */
    private static final LocalDate OCTOBER_SHUTDOWN = LocalDate.of(2026, 10, 1);

    // ── not declaring: today's maths, untouched ───────────────────────────

    @Test
    void notDeclaring_weekday_isAllocationOverFive() {
        DayAvailability r = AvailabilityDayResolver.resolve(false, ALLOCATION_37, WEDNESDAY, null);
        assertEquals(7.4, r.fullAvailability(), 1e-9);
        assertEquals(0.0, r.unavailableHours(), 1e-9);
    }

    @Test
    void notDeclaring_juniorAllocation_isSpreadFlatAcrossWeekdays() {
        // The F1 defect itself: 15 h/week reads as 3 h every weekday.
        DayAvailability r = AvailabilityDayResolver.resolve(false, ALLOCATION_15, WEDNESDAY, null);
        assertEquals(3.0, r.fullAvailability(), 1e-9);
    }

    @Test
    void notDeclaring_weekend_isZero() {
        DayAvailability r = AvailabilityDayResolver.resolve(false, ALLOCATION_37, SATURDAY, null);
        assertEquals(0.0, r.fullAvailability(), 1e-9);
        assertEquals(0.0, r.unavailableHours(), 1e-9);
    }

    @Test
    void notDeclaring_friday_deductsTwoHoursCappedAtAvailability() {
        assertEquals(2.0, AvailabilityDayResolver.resolve(false, ALLOCATION_37, FRIDAY, null).unavailableHours(), 1e-9);
        // A 3 h phantom Friday loses 2 of its 3 hours — the class of error WP1 removes for juniors.
        assertEquals(2.0, AvailabilityDayResolver.resolve(false, ALLOCATION_15, FRIDAY, null).unavailableHours(), 1e-9);
        assertEquals(1.0, AvailabilityDayResolver.resolve(false, 5, FRIDAY, null).unavailableHours(), 1e-9);
    }

    @Test
    void notDeclaring_octoberShutdown_deductsFullDayCappedAtAvailability() {
        assertEquals(7.4, AvailabilityDayResolver.resolve(false, ALLOCATION_37, OCTOBER_SHUTDOWN, null).unavailableHours(), 1e-9);
        assertEquals(3.0, AvailabilityDayResolver.resolve(false, ALLOCATION_15, OCTOBER_SHUTDOWN, null).unavailableHours(), 1e-9);
    }

    @Test
    void notDeclaring_ignoresAnyDeclarationHandedToIt() {
        // SHADOW mode / non-STUDENT: the declaration must never leak into the maths.
        DayAvailability r = AvailabilityDayResolver.resolve(false, ALLOCATION_37, WEDNESDAY, new BigDecimal("2.00"));
        assertEquals(7.4, r.fullAvailability(), 1e-9);
    }

    // ── declaring: the declaration, or zero ───────────────────────────────

    @Test
    void declaring_declaredWeekday_isTheDeclaration() {
        DayAvailability r = AvailabilityDayResolver.resolve(true, ALLOCATION_15, WEDNESDAY, new BigDecimal("7.50"));
        assertEquals(7.5, r.fullAvailability(), 1e-9);
        assertEquals(0.0, r.unavailableHours(), 1e-9);
    }

    @Test
    void declaring_undeclaredWeekday_isZero_notAllocationOverFive() {
        // D7: a missing row means "not available", never the phantom 3 h.
        DayAvailability r = AvailabilityDayResolver.resolve(true, ALLOCATION_15, WEDNESDAY, null);
        assertEquals(0.0, r.fullAvailability(), 1e-9);
    }

    @Test
    void declaring_explicitZeroAndMissingRowResolveIdentically() {
        DayAvailability zero = AvailabilityDayResolver.resolve(true, ALLOCATION_15, WEDNESDAY, BigDecimal.ZERO);
        DayAvailability missing = AvailabilityDayResolver.resolve(true, ALLOCATION_15, WEDNESDAY, null);
        assertEquals(zero, missing);
    }

    @Test
    void declaring_declaredSaturday_isHonoured() {
        DayAvailability r = AvailabilityDayResolver.resolve(true, ALLOCATION_15, SATURDAY, new BigDecimal("4.00"));
        assertEquals(4.0, r.fullAvailability(), 1e-9);
    }

    @Test
    void declaring_undeclaredWeekend_isZero() {
        assertEquals(0.0, AvailabilityDayResolver.resolve(true, ALLOCATION_15, SATURDAY, null).fullAvailability(), 1e-9);
    }

    @Test
    void declaring_friday_hasNoTwoHourDeduction() {
        DayAvailability r = AvailabilityDayResolver.resolve(true, ALLOCATION_15, FRIDAY, new BigDecimal("6.00"));
        assertEquals(6.0, r.fullAvailability(), 1e-9);
        assertEquals(0.0, r.unavailableHours(), 1e-9);
    }

    @Test
    void declaring_octoberShutdown_hasNoDeduction() {
        DayAvailability r = AvailabilityDayResolver.resolve(true, ALLOCATION_15, OCTOBER_SHUTDOWN, new BigDecimal("5.00"));
        assertEquals(5.0, r.fullAvailability(), 1e-9);
        assertEquals(0.0, r.unavailableHours(), 1e-9);
    }

    @Test
    void declaring_negativeDeclarationClampsToZero() {
        // The DB CHECK forbids it; belt and braces at the resolver too.
        assertEquals(0.0, AvailabilityDayResolver.resolve(true, ALLOCATION_15, WEDNESDAY, new BigDecimal("-1")).fullAvailability(), 1e-9);
    }
}
