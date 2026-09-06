package dk.trustworks.intranet.aggregates.internalassignment.services;

import dk.trustworks.intranet.aggregates.internalassignment.dto.InternalAssignmentRequest;
import dk.trustworks.intranet.aggregates.internalassignment.services.InternalAssignmentValidator.Sizing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sizing (D9) and validation for internal assignments (spec §4.3.1–4.3.2). */
class InternalAssignmentValidatorTest {

    // Aug–Sep 2026: 3 Aug (Mon) .. 30 Sep (Wed) = 43 weekdays
    private static final LocalDate FROM = LocalDate.of(2026, 8, 3);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private static InternalAssignmentRequest byTotal(String total) {
        return new InternalAssignmentRequest("JK-onboarding", null, FROM, TO, null, new BigDecimal(total), true, null);
    }

    private static InternalAssignmentRequest byWeek(String perWeek) {
        return new InternalAssignmentRequest("ISAE support", "sponsor-1", FROM, TO, new BigDecimal(perWeek), null, false, "notes");
    }

    @Test
    void weekdaysAreCountedInclusively() {
        assertEquals(43, InternalAssignmentValidator.weekdaysInclusive(FROM, TO));
        assertEquals(5, InternalAssignmentValidator.weekdaysInclusive(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11)));
        assertEquals(0, InternalAssignmentValidator.weekdaysInclusive(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)));
        assertEquals(0, InternalAssignmentValidator.weekdaysInclusive(TO, FROM));
    }

    @Test
    void totalHoursAreStoredAsHoursPerWeekAndKeptForDisplay() {
        // 40 h over 43 weekdays × 5 = 4.65 h/week
        Sizing sizing = InternalAssignmentValidator.sizingOf(byTotal("40"));
        assertEquals(new BigDecimal("4.65"), sizing.hoursPerWeek());
        assertEquals(new BigDecimal("40.00"), sizing.estimatedTotalHours());
    }

    @Test
    void hoursPerWeekIsStoredAsIs() {
        Sizing sizing = InternalAssignmentValidator.sizingOf(byWeek("5"));
        assertEquals(new BigDecimal("5.00"), sizing.hoursPerWeek());
        assertNull(sizing.estimatedTotalHours());
    }

    @Test
    void aWeekendOnlyPeriodCannotBeSpread() {
        assertEquals(new BigDecimal("0.00"), InternalAssignmentValidator.hoursPerWeekFromTotal(
                new BigDecimal("8"), LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11)));
    }

    @Test
    void validRequestsHaveNoProblems() {
        assertTrue(InternalAssignmentValidator.validate(byTotal("40")).isEmpty());
        assertTrue(InternalAssignmentValidator.validate(byWeek("5")).isEmpty());
    }

    @Test
    void exactlyOneSizingIsRequired() {
        List<String> none = InternalAssignmentValidator.validate(
                new InternalAssignmentRequest("t", null, FROM, TO, null, null, false, null));
        assertTrue(none.contains("Give exactly one of hoursPerWeek or totalHours"));
        List<String> both = InternalAssignmentValidator.validate(
                new InternalAssignmentRequest("t", null, FROM, TO, BigDecimal.ONE, BigDecimal.TEN, false, null));
        assertTrue(both.contains("Give exactly one of hoursPerWeek or totalHours"));
    }

    @Test
    void titlePeriodAndBoundsAreChecked() {
        List<String> problems = InternalAssignmentValidator.validate(
                new InternalAssignmentRequest(" ", null, TO, FROM, new BigDecimal("-1"), null, false, "x".repeat(4001)));
        assertTrue(problems.contains("title is required"));
        assertTrue(problems.contains("activeTo must not be before activeFrom"));
        assertTrue(problems.contains("hoursPerWeek must not be negative"));
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("notes must be at most")));

        assertTrue(InternalAssignmentValidator.validate(
                new InternalAssignmentRequest("t", null, FROM, FROM.plusYears(3), BigDecimal.ONE, null, false, null))
                .contains("The period must be at most two years"));
        assertTrue(InternalAssignmentValidator.validate(null).contains("A request body is required"));
    }
}
