package dk.trustworks.intranet.aggregates.availability.services;

import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityCopyRequest;
import dk.trustworks.intranet.aggregates.availability.dto.DeclaredAvailabilityUpsertRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §4.1.5: hours ∈ [0, 24] in 0.25 steps, server-enforced; a bulk PUT is refused whole. */
class DeclaredAvailabilityValidatorTest {

    private static final LocalDate MON = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUE = MON.plusDays(1);

    private static DeclaredAvailabilityUpsertRequest item(LocalDate day, String hours) {
        return new DeclaredAvailabilityUpsertRequest(day, hours == null ? null : new BigDecimal(hours), null);
    }

    @Test
    void quarterHourStepsWithinBoundsAreValid() {
        for (String ok : new String[]{"0", "0.25", "0.5", "3.75", "7.5", "24", "24.00"}) {
            assertNull(DeclaredAvailabilityValidator.validateHours(new BigDecimal(ok)), ok);
        }
    }

    @Test
    void offStepOrOutOfBoundsHoursAreRejected() {
        assertEquals("hours must be a multiple of 0.25", DeclaredAvailabilityValidator.validateHours(new BigDecimal("7.4")));
        assertEquals("hours must be a multiple of 0.25", DeclaredAvailabilityValidator.validateHours(new BigDecimal("0.1")));
        assertEquals("hours must be between 0 and 24", DeclaredAvailabilityValidator.validateHours(new BigDecimal("24.25")));
        assertEquals("hours must be between 0 and 24", DeclaredAvailabilityValidator.validateHours(new BigDecimal("-0.25")));
        assertEquals("hours is required", DeclaredAvailabilityValidator.validateHours(null));
    }

    @Test
    void validBatchHasNoProblems() {
        assertTrue(DeclaredAvailabilityValidator.validateUpsert(List.of(item(MON, "7.5"), item(TUE, "0"))).isEmpty());
    }

    @Test
    void oneBadItemRefusesTheWholeBatch() {
        // Atomicity by construction: the problems list names the bad item and the caller
        // gets a 400 before any row is written.
        List<String> problems = DeclaredAvailabilityValidator.validateUpsert(
                List.of(item(MON, "7.5"), item(TUE, "7.4")));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("item 2:"), problems.get(0));
    }

    @Test
    void emptyBatchIsRejected() {
        assertFalse(DeclaredAvailabilityValidator.validateUpsert(List.of()).isEmpty());
        assertFalse(DeclaredAvailabilityValidator.validateUpsert(null).isEmpty());
    }

    @Test
    void duplicateDaysAndMissingDaysAreRejected() {
        List<String> problems = DeclaredAvailabilityValidator.validateUpsert(
                List.of(item(MON, "1"), item(MON, "2"), item(null, "3")));
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).contains("appears more than once"));
        assertTrue(problems.get(1).contains("day is required"));
    }

    @Test
    void overlongNoteIsRejected() {
        String note = "x".repeat(256);
        List<String> problems = DeclaredAvailabilityValidator.validateUpsert(
                List.of(new DeclaredAvailabilityUpsertRequest(MON, BigDecimal.ONE, note)));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("note"));
    }

    @Test
    void oversizedBatchIsRejected() {
        List<DeclaredAvailabilityUpsertRequest> items = new ArrayList<>();
        for (int i = 0; i <= DeclaredAvailabilityValidator.MAX_BATCH_SIZE; i++) {
            items.add(item(MON.plusDays(i), "1"));
        }
        assertFalse(DeclaredAvailabilityValidator.validateUpsert(items).isEmpty());
    }

    @Test
    void copyRequiresMondaysAndDistinctTargets() {
        assertTrue(DeclaredAvailabilityValidator.validateCopy(
                new DeclaredAvailabilityCopyRequest(MON, List.of(MON.plusWeeks(1), MON.plusWeeks(2)))).isEmpty());

        List<String> problems = DeclaredAvailabilityValidator.validateCopy(
                new DeclaredAvailabilityCopyRequest(TUE, Arrays.asList(MON, MON.plusWeeks(1), MON.plusWeeks(1), TUE, null)));
        assertTrue(problems.contains("sourceWeekStart must be a Monday"));
        assertTrue(problems.contains(MON.plusWeeks(1) + " appears more than once"));
        assertTrue(problems.contains(TUE + " is not a Monday"));
        assertTrue(problems.contains("A target week is missing"));
    }

    @Test
    void copySourceCannotBeATarget() {
        List<String> problems = DeclaredAvailabilityValidator.validateCopy(
                new DeclaredAvailabilityCopyRequest(MON, List.of(MON)));
        assertEquals(List.of("The source week cannot be a target"), problems);
    }

    @Test
    void copyNeedsAtLeastOneTarget() {
        assertFalse(DeclaredAvailabilityValidator.validateCopy(new DeclaredAvailabilityCopyRequest(MON, List.of())).isEmpty());
        assertFalse(DeclaredAvailabilityValidator.validateCopy(null).isEmpty());
    }
}
