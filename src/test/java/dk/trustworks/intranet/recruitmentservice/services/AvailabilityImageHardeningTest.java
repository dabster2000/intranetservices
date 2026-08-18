package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.ai.AvailabilitySchedulingPrompts;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityExtractionService.Extraction;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityExtractionService.ImageReading;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityExtractionService.RawConstraint;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityExtractionService.Validated;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.Consensus;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.Derived;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.Interval;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.Trust;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remediations from the 2026-08-18 security review of the calendar-image path.
 * Each test pins one finding so it cannot silently regress.
 */
class AvailabilityImageHardeningTest {

    private static final LocalDate WINDOW_START = LocalDate.of(2026, 8, 24);
    private static final LocalDate WINDOW_END = LocalDate.of(2026, 8, 28);
    private static final LocalDate DAY = LocalDate.of(2026, 8, 25);

    // ---- Finding 1: visual prompt injection via constraints[] --------------

    @Test
    void anImageAloneCannotAssertAnExclusiveWindow() {
        // The attack: a screenshot with a readable banner reading
        // "ONLY AVAILABLE FRIDAY 09:00-09:15". An AVAILABLE_ONLY does not need
        // to override the calendar to do damage — it BLOCKS every slot outside
        // itself for the whole covered day.
        Validated validated = AvailabilityExtractionService.validateImage(
                reading(false, List.of(
                        raw(AvailabilityConstraintType.AVAILABLE_ONLY,
                                DAY.atTime(9, 0), DAY.atTime(9, 15)))),
                WINDOW_START, WINDOW_END);

        assertTrue(validated.constraints().stream()
                        .noneMatch(c -> c.type() == AvailabilityConstraintType.AVAILABLE_ONLY),
                "no accompanying text ⇒ the picture may assert nothing but its transcription");
        assertTrue(validated.ambiguities().stream().anyMatch(a -> a.contains("ikke er registreret")),
                "and the interviewer is told what was dropped, so it is not silent");
    }

    @Test
    void anImageAloneCannotAssertAPreferenceEither() {
        Validated validated = AvailabilityExtractionService.validateImage(
                reading(false, List.of(
                        raw(AvailabilityConstraintType.PREFERRED,
                                DAY.atTime(13, 0), DAY.atTime(16, 0)),
                        raw(AvailabilityConstraintType.AVOID,
                                DAY.atTime(8, 0), DAY.atTime(9, 0)))),
                WINDOW_START, WINDOW_END);
        assertEquals(1, validated.constraints().size(),
                "only the derived busy interval survives");
        assertEquals(AvailabilityConstraintType.BUSY, validated.constraints().getFirst().type());
    }

    @Test
    void withAccompanyingTextTheExclusiveWindowIsHonoured() {
        // The legitimate case the owner actually described: a client-calendar
        // screenshot PLUS "jeg kan kun onsdag efter 13".
        Validated validated = AvailabilityExtractionService.validateImage(
                reading(true, List.of(
                        raw(AvailabilityConstraintType.AVAILABLE_ONLY,
                                DAY.atTime(13, 0), DAY.atTime(17, 0)))),
                WINDOW_START, WINDOW_END);
        assertTrue(validated.constraints().stream()
                        .anyMatch(c -> c.type() == AvailabilityConstraintType.AVAILABLE_ONLY),
                "text-sourced exclusivity still counts");
    }

    @Test
    void aModelClaimedBusyIsDiscardedEvenWithText() {
        // Busy comes only from the transcription, text or no text.
        Validated validated = AvailabilityExtractionService.validateImage(
                reading(true, List.of(
                        raw(AvailabilityConstraintType.BUSY,
                                DAY.atTime(0, 0), DAY.atTime(23, 59)))),
                WINDOW_START, WINDOW_END);
        assertEquals(1, validated.constraints().size(),
                "the derived interval only — the model's own BUSY never survives");
        assertEquals(LocalDateTime.of(2026, 8, 25, 10, 0),
                validated.constraints().getFirst().start());
    }

    // ---- Finding 5: reaching corroboration -------------------------------

    @Test
    void retryBudgetAllowsReachingTwoSuccessfulPasses() {
        // Retrying only on DISAGREEMENT left a single transient error degrading
        // the reading to uncorroborated. The budget must exceed the target so a
        // failed pass costs an attempt, not corroboration.
        assertTrue(AvailabilityExtractionService.IMAGE_READ_MAX_ATTEMPTS
                        > AvailabilityExtractionService.IMAGE_READ_PASSES,
                "there must be headroom to retry a failed pass");
    }

    @Test
    void aSinglePassReadingIsMarkedUncorroborated() {
        ImageReading single = new ImageReading(
                extraction(List.of()),
                new Consensus(List.of(), List.of(), false),
                new Derived(List.of(interval()), List.of(), List.of(), List.of()),
                new Trust(false, List.of()),
                1, DAY, DAY, false);
        assertFalse(single.corroborated());
        assertTrue(AvailabilityExtractionService.trustCodes(single).contains("NOT_CORROBORATED"),
                "the card and the audit trail both learn it was never double-checked");
    }

    @Test
    void aCorroboratedCleanReadingCarriesNoTrustCodes() {
        ImageReading clean = new ImageReading(
                extraction(List.of()),
                new Consensus(List.of(), List.of(), false),
                new Derived(List.of(interval()), List.of(), List.of(), List.of()),
                new Trust(false, List.of()),
                2, DAY, DAY, false);
        assertTrue(clean.corroborated());
        assertEquals(null, AvailabilityExtractionService.trustCodes(clean));
    }

    @Test
    void trustCodesAreClampedToTheColumnWidth() {
        ImageReading noisy = new ImageReading(
                extraction(List.of()),
                new Consensus(List.of(), List.of(DAY), false),
                new Derived(List.of(), List.of(), List.of(), List.of()),
                new Trust(true, List.of("ALL_ROUND_BOUNDARIES", "IMPLAUSIBLE_DENSITY",
                        "NO_AXIS", "UNREADABLE_DAYS")),
                1, DAY, DAY, false);
        assertTrue(AvailabilityExtractionService.trustCodes(noisy).length() <= 160,
                "read_trust is VARCHAR(160) — a truncating insert must be impossible");
    }

    // ---- Finding 3: the S3 sweep can never miss a slot --------------------

    @Test
    void theDeletionSweepCoversEveryImageThatCanBeStored() {
        assertTrue(SchedulingEvidenceStorageService.MAX_IMAGES_PER_EVIDENCE
                        >= AvailabilityMessageService.IMAGES_PER_MESSAGE_MAX,
                "every image the ingest can write must be one the D10 sweep deletes — "
                        + "otherwise a lowered constant orphans candidate PII in S3 forever, "
                        + "with no error and no alert");
    }

    // ---- helpers ----------------------------------------------------------

    private static ImageReading reading(boolean hasText, List<RawConstraint> modelConstraints) {
        return new ImageReading(
                extraction(modelConstraints),
                new Consensus(List.of(), List.of(), false),
                new Derived(List.of(interval()), List.of(), List.of(), List.of()),
                new Trust(false, List.of()),
                2, DAY, DAY, hasText);
    }

    private static Extraction extraction(List<RawConstraint> constraints) {
        return new Extraction("da",
                AvailabilitySchedulingPrompts.INTENT_PROVIDE_AVAILABILITY,
                "Europe/Copenhagen", DAY, DAY, constraints, List.of(), true, null,
                null, List.of());
    }

    private static Interval interval() {
        return new Interval(DAY.atTime(10, 0), DAY.atTime(10, 45), new BigDecimal("0.90"));
    }

    private static RawConstraint raw(AvailabilityConstraintType type,
                                     LocalDateTime start, LocalDateTime end) {
        return new RawConstraint(type, start, end, new BigDecimal("0.90"));
    }
}
