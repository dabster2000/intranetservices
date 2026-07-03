package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.TeamSickLeaveTrackingDTO.MonthlySickDays;
import dk.trustworks.intranet.aggregates.finance.dto.TeamSickLeaveTrackingDTO.SickPeriod;
import dk.trustworks.intranet.aggregates.finance.services.SickLeaveCalculator.DayFact;
import dk.trustworks.intranet.aggregates.finance.services.SickLeaveCalculator.UserSickResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for the Funktionærloven 120-day sick-day counting — no Quarkus, no DB.
 * Covers the two H10/M3 defects: fractional partial days and the weekend/worked-day bridging.
 *
 * <p>Convention for fixtures: a full-time weekday has gross_available = 7.4h; a
 * weekend/holiday has gross_available = 0h; registered_billable_hours is the "worked" signal.
 */
class SickLeaveCalculatorTest {

    private static final double DELTA = 1e-6;

    // ---- fixture builders --------------------------------------------------

    /** Full-time weekday sick for {@code hours} hours (gross 7.4). */
    private static DayFact sick(String date, double hours) {
        return new DayFact(LocalDate.parse(date), hours, 7.4, 0.0);
    }

    /** Part-time weekday sick: {@code sickHours} of a {@code gross}-hour scheduled day. */
    private static DayFact sickPartTime(String date, double sickHours, double gross) {
        return new DayFact(LocalDate.parse(date), sickHours, gross, 0.0);
    }

    /** Non-working day (weekend / zero-availability holiday) with no work. */
    private static DayFact nonWorking(String date) {
        return new DayFact(LocalDate.parse(date), 0.0, 0.0, 0.0);
    }

    /** Non-working day on which billable work was nonetheless registered. */
    private static DayFact nonWorkingWithWork(String date, double billable) {
        return new DayFact(LocalDate.parse(date), 0.0, 0.0, billable);
    }

    /** Ordinary working weekday (gross 7.4), optionally with registered work. */
    private static DayFact workingWeekday(String date, double billable) {
        return new DayFact(LocalDate.parse(date), 0.0, 7.4, billable);
    }

    private static Map<String, Double> trendMap(UserSickResult r) {
        return r.monthlyTrend().stream()
                .collect(Collectors.toMap(MonthlySickDays::month, MonthlySickDays::sickDays));
    }

    // ---- fractional counting (defect #1) -----------------------------------

    @Test void fullDayCountsAsOne() {
        assertEquals(1.0, SickLeaveCalculator.effectiveSickDay(sick("2025-12-08", 7.4)), DELTA);
    }

    @Test void halfDayCountsAsHalf() {
        assertEquals(0.5, SickLeaveCalculator.effectiveSickDay(sick("2025-12-08", 3.7)), DELTA);
    }

    @Test void overFullDayIsCappedAtOne() {
        assertEquals(1.0, SickLeaveCalculator.effectiveSickDay(sick("2025-12-08", 9.0)), DELTA);
    }

    @Test void partTimeFullDayCountsAsOne() {
        // A 6h/day part-timer sick their whole scheduled day is a full sick day, not 6/7.4.
        assertEquals(1.0, SickLeaveCalculator.effectiveSickDay(sickPartTime("2025-12-08", 6.0, 6.0)), DELTA);
        assertEquals(0.5, SickLeaveCalculator.effectiveSickDay(sickPartTime("2025-12-08", 3.0, 6.0)), DELTA);
    }

    @Test void rollingTotalSumsFractions() {
        // Three partial days must total 1.5, not the old flat 3.0.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-12-08", 3.7),
                sick("2025-12-09", 3.7),
                sick("2025-12-10", 3.7)));
        assertEquals(1.5, r.rollingTotal(), DELTA);
        assertEquals(1, r.periods().size());
    }

    // ---- bridging: weekends inside a full-sick period (correct case) --------

    @Test void bridgesWeekendBetweenTwoFullSickDays() {
        // Fri full sick, Sat+Sun weekend (no work), Mon full sick → one 4-day period.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-12-05", 7.4),   // Friday
                nonWorking("2025-12-06"),  // Saturday
                nonWorking("2025-12-07"),  // Sunday
                sick("2025-12-08", 7.4))); // Monday
        assertEquals(4.0, r.rollingTotal(), DELTA);
        assertEquals(1, r.periods().size());
        SickPeriod p = r.periods().get(0);
        assertEquals(LocalDate.parse("2025-12-05"), p.startDate());
        assertEquals(LocalDate.parse("2025-12-08"), p.endDate());
        assertEquals(4.0, p.totalDays(), DELTA);
    }

    @Test void bridgesZeroAvailabilityWeekdayHoliday() {
        // Wed full sick, Thu is a zero-availability holiday (no work), Fri full sick → bridged.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-12-10", 7.4),   // Wednesday
                nonWorking("2025-12-11"),  // holiday, gross 0
                sick("2025-12-12", 7.4))); // Friday
        assertEquals(3.0, r.rollingTotal(), DELTA);
        assertEquals(1, r.periods().size());
    }

    @Test void bridgedDaysSplitAcrossMonthBoundary() {
        // Fri 2025-01-31 sick, weekend into February, Mon 2025-02-03 sick.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-01-31", 7.4),   // Friday
                nonWorking("2025-02-01"),  // Saturday
                nonWorking("2025-02-02"),  // Sunday
                sick("2025-02-03", 7.4))); // Monday
        assertEquals(4.0, r.rollingTotal(), DELTA);
        Map<String, Double> t = trendMap(r);
        assertEquals(1.0, t.get("2025-01"), DELTA);
        assertEquals(3.0, t.get("2025-02"), DELTA);
    }

    // ---- bridging: the H10 defects it must NOT do --------------------------

    @Test void doesNotBridgeWorkedWeekdaysBetweenSickDays() {
        // Mon full sick, Tue+Wed ordinary worked weekdays, Thu full sick.
        // Old code bridged Tue/Wed as sick; corrected code must not.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-12-08", 7.4),          // Monday
                workingWeekday("2025-12-09", 9.0), // Tuesday, billed 9h
                workingWeekday("2025-12-10", 7.0), // Wednesday, billed 7h
                sick("2025-12-11", 7.4)));         // Thursday
        assertEquals(2.0, r.rollingTotal(), DELTA);
        assertEquals(2, r.periods().size(), "return-to-work must split the period");
    }

    @Test void doesNotBridgeAbsentWorkingWeekdays() {
        // Same shape but the worked weekdays simply aren't in the fetched rows
        // (production only fetches sick + zero-availability rows). Weekday gap → no bridge.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-12-08", 7.4),   // Monday
                sick("2025-12-11", 7.4))); // Thursday, Tue/Wed absent
        assertEquals(2.0, r.rollingTotal(), DELTA);
        assertEquals(2, r.periods().size());
    }

    @Test void doesNotBridgeWeekendWithRegisteredWork() {
        // Employee billed work on the Saturday inside the gap → weekend is not bridged.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-12-05", 7.4),                  // Friday
                nonWorkingWithWork("2025-12-06", 4.0),    // Saturday, billed 4h
                nonWorking("2025-12-07"),                 // Sunday
                sick("2025-12-08", 7.4)));                // Monday
        assertEquals(2.0, r.rollingTotal(), DELTA);
        assertEquals(2, r.periods().size());
    }

    @Test void doesNotBridgeWhenBoundaryIsPartial() {
        // Friday only a half sick day → the weekend is not part of a full-sick period.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-12-05", 3.7),   // Friday, half day
                nonWorking("2025-12-06"),
                nonWorking("2025-12-07"),
                sick("2025-12-08", 7.4))); // Monday, full
        assertEquals(1.5, r.rollingTotal(), DELTA);
        assertEquals(2, r.periods().size());
    }

    // ---- period detection edge cases ---------------------------------------

    @Test void consecutiveFullDaysAreOnePeriod() {
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-12-08", 7.4),
                sick("2025-12-09", 7.4),
                sick("2025-12-10", 7.4)));
        assertEquals(3.0, r.rollingTotal(), DELTA);
        assertEquals(1, r.periods().size());
    }

    @Test void gapLargerThanMaxIsNotBridged() {
        // Two full sick days a week apart → two separate periods, nothing bridged.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-12-01", 7.4),
                sick("2025-12-08", 7.4)));
        assertEquals(2.0, r.rollingTotal(), DELTA);
        assertEquals(2, r.periods().size());
    }

    @Test void emptyInputYieldsEmptyResult() {
        UserSickResult r = SickLeaveCalculator.compute(List.of());
        assertEquals(0.0, r.rollingTotal(), DELTA);
        assertTrue(r.periods().isEmpty());
        assertTrue(r.monthlyTrend().isEmpty());
    }

    @Test void weekendsOnlyNoSickDaysYieldEmpty() {
        // Zero-availability rows with no sick day anywhere must produce nothing.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                nonWorking("2025-12-06"),
                nonWorking("2025-12-07")));
        assertEquals(0.0, r.rollingTotal(), DELTA);
        assertTrue(r.periods().isEmpty());
    }

    @Test void unorderedInputIsHandled() {
        // compute() must not assume sorted input.
        UserSickResult r = SickLeaveCalculator.compute(List.of(
                sick("2025-12-08", 7.4),
                nonWorking("2025-12-07"),
                sick("2025-12-05", 7.4),
                nonWorking("2025-12-06")));
        assertEquals(4.0, r.rollingTotal(), DELTA);
        assertEquals(1, r.periods().size());
    }
}
