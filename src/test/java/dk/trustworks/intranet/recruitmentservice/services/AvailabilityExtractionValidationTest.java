package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.ai.AvailabilitySchedulingPrompts;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityExtractionService.Extraction;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityExtractionService.RawConstraint;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityExtractionService.Validated;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan §12.3 backend gate, DB-free: the model's output is claims —
 * intent allowlist, window ± 1 week, sane counts and the forced-
 * confirmation rules decide what may become evidence. A hostile or
 * hallucinating model must land in REJECT_*, never in constraints.
 */
class AvailabilityExtractionValidationTest {

    private static final LocalDate WINDOW_START = LocalDate.of(2026, 8, 17);
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 8, 28);

    // ---- Intent allowlist -------------------------------------------------

    @Test
    void unknownAndOffListIntents_reject() {
        Validated offList = validate(extraction("TAKE_OVER_THE_CALENDAR", List.of(busy(18))));
        assertEquals(AvailabilitySchedulingPrompts.INTENT_UNKNOWN, offList.intent());
        assertEquals(AvailabilityExtractionService.REJECT_UNKNOWN_INTENT, offList.rejectReason());
        assertTrue(offList.constraints().isEmpty(), "no constraints survive a rejected intent");

        Validated unknown = validate(extraction(
                AvailabilitySchedulingPrompts.INTENT_UNKNOWN, List.of()));
        assertEquals(AvailabilityExtractionService.REJECT_UNKNOWN_INTENT, unknown.rejectReason());
    }

    @Test
    void routedAndButtonIntents_passWithoutConstraintsOrRejection() {
        for (String intent : List.of(
                AvailabilitySchedulingPrompts.INTENT_ASK_QUESTION,
                AvailabilitySchedulingPrompts.INTENT_ESCALATE,
                AvailabilitySchedulingPrompts.INTENT_SUGGEST_REPLACEMENT,
                AvailabilitySchedulingPrompts.INTENT_CANCEL_PARTICIPATION,
                AvailabilitySchedulingPrompts.INTENT_APPROVE_SLOT,
                AvailabilitySchedulingPrompts.INTENT_DECLINE_SLOT)) {
            Validated validated = validate(extraction(intent, List.of()));
            assertNull(validated.rejectReason(), intent);
            assertTrue(validated.constraints().isEmpty(), intent);
        }
    }

    // ---- Window and sanity bounds ----------------------------------------

    @Test
    void constraintsOutsideWindowPlusSlack_reject() {
        // The window ± 1 week rule: 2026-08-10 .. 2026-09-04 is legal.
        Validated inside = validate(extraction(
                AvailabilitySchedulingPrompts.INTENT_ADD_BUSY_INTERVAL,
                List.of(busyOn(LocalDate.of(2026, 8, 10)))));
        assertNull(inside.rejectReason());

        Validated before = validate(extraction(
                AvailabilitySchedulingPrompts.INTENT_ADD_BUSY_INTERVAL,
                List.of(busyOn(LocalDate.of(2026, 8, 9)))));
        assertEquals(AvailabilityExtractionService.REJECT_OUTSIDE_WINDOW, before.rejectReason());

        Validated after = validate(extraction(
                AvailabilitySchedulingPrompts.INTENT_ADD_BUSY_INTERVAL,
                List.of(busyOn(LocalDate.of(2026, 9, 5)))));
        assertEquals(AvailabilityExtractionService.REJECT_OUTSIDE_WINDOW, after.rejectReason());
    }

    @Test
    void emptyOversizedAndInvertedConstraintSets_reject() {
        Validated empty = validate(extraction(
                AvailabilitySchedulingPrompts.INTENT_PROVIDE_AVAILABILITY, List.of()));
        assertEquals(AvailabilityExtractionService.REJECT_NO_CONSTRAINTS, empty.rejectReason());

        List<RawConstraint> tooMany = new ArrayList<>();
        for (int i = 0; i < AvailabilityExtractionService.MAX_CONSTRAINTS + 1; i++) {
            tooMany.add(busy(17 + (i % 10)));
        }
        Validated oversized = validate(extraction(
                AvailabilitySchedulingPrompts.INTENT_PROVIDE_AVAILABILITY, tooMany));
        assertEquals(AvailabilityExtractionService.REJECT_TOO_MANY_CONSTRAINTS,
                oversized.rejectReason());

        RawConstraint inverted = new RawConstraint(AvailabilityConstraintType.BUSY,
                WINDOW_START.atTime(12, 0), WINDOW_START.atTime(9, 0), BigDecimal.ONE);
        Validated bad = validate(extraction(
                AvailabilitySchedulingPrompts.INTENT_ADD_BUSY_INTERVAL, List.of(inverted)));
        assertEquals(AvailabilityExtractionService.REJECT_INVALID_INTERVAL, bad.rejectReason());
    }

    // ---- Covered range ----------------------------------------------------

    @Test
    void missingCoveredRange_defaultsToTheConstraintSpan() {
        Validated validated = validate(extraction(
                AvailabilitySchedulingPrompts.INTENT_PROVIDE_AVAILABILITY,
                List.of(busy(18), busy(20))));
        assertEquals(LocalDate.of(2026, 8, 18), validated.coveredFrom());
        assertEquals(LocalDate.of(2026, 8, 20), validated.coveredTo());
    }

    @Test
    void invertedCoveredRange_rejects() {
        Extraction extraction = new Extraction("da",
                AvailabilitySchedulingPrompts.INTENT_PROVIDE_AVAILABILITY,
                "Europe/Copenhagen", LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 18),
                List.of(busy(19)), List.of(), false, null);
        assertEquals(AvailabilityExtractionService.REJECT_INVALID_COVERED_RANGE,
                validate(extraction).rejectReason());
    }

    // ---- Forced confirmation (the D9 text rule) ---------------------------

    @Test
    void cleanExplicitStatement_keepsTheModelsConfirmationVerdict() {
        Validated validated = validate(extraction(
                AvailabilitySchedulingPrompts.INTENT_ADD_BUSY_INTERVAL, List.of(busy(18))));
        assertFalse(validated.requiresConfirmation(),
                "an unambiguous, confident, Copenhagen-time statement may auto-confirm");
    }

    @Test
    void ambiguities_lowConfidence_andForeignTimezone_eachForceConfirmation() {
        Extraction ambiguous = new Extraction("da",
                AvailabilitySchedulingPrompts.INTENT_ADD_BUSY_INTERVAL, "Europe/Copenhagen",
                null, null, List.of(busy(18)), List.of("Sluttidspunktet er uklart."),
                false, null);
        assertTrue(validate(ambiguous).requiresConfirmation());

        RawConstraint vague = new RawConstraint(AvailabilityConstraintType.BUSY,
                WINDOW_START.atTime(9, 0), WINDOW_START.atTime(12, 0), new BigDecimal("0.60"));
        Extraction lowConfidence = new Extraction("da",
                AvailabilitySchedulingPrompts.INTENT_ADD_BUSY_INTERVAL, "Europe/Copenhagen",
                null, null, List.of(vague), List.of(), false, null);
        assertTrue(validate(lowConfidence).requiresConfirmation());

        Extraction foreignTz = new Extraction("en",
                AvailabilitySchedulingPrompts.INTENT_ADD_BUSY_INTERVAL, "America/New_York",
                null, null, List.of(busy(18)), List.of(), false, null);
        assertTrue(validate(foreignTz).requiresConfirmation());
    }

    @Test
    void language_normalizesToDaOrEn() {
        Extraction odd = new Extraction("de",
                AvailabilitySchedulingPrompts.INTENT_ADD_BUSY_INTERVAL, "Europe/Copenhagen",
                null, null, List.of(busy(18)), List.of(), false, null);
        assertEquals("da", validate(odd).language());
    }

    // ---- Helpers ----------------------------------------------------------

    private static Validated validate(Extraction extraction) {
        return AvailabilityExtractionService.validate(extraction, WINDOW_START, WINDOW_END);
    }

    private static Extraction extraction(String intent, List<RawConstraint> constraints) {
        return new Extraction("da", intent, "Europe/Copenhagen", null, null,
                constraints, List.of(), false, null);
    }

    /** A confident busy 09:00–12:00 on the given August day. */
    private static RawConstraint busy(int augustDay) {
        return busyOn(LocalDate.of(2026, 8, augustDay));
    }

    private static RawConstraint busyOn(LocalDate day) {
        return new RawConstraint(AvailabilityConstraintType.BUSY,
                day.atTime(9, 0), day.atTime(12, 0), BigDecimal.ONE.setScale(2));
    }
}
