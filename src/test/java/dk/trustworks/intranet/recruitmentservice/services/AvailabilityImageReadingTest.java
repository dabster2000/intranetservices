package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.ai.AvailabilitySchedulingPrompts;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.Axis;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.Block;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.Consensus;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.DayRead;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.Derived;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.Interval;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilityImageReading.Trust;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v2 calendar-image reading, DB-free. The regression fixture is the real
 * 2026-08-18 production misread: an interviewer sent two week-screenshots of a
 * CLIENT calendar and got back two empty weekdays reported as fully booked,
 * three meetings placed in the wrong hour, and a multi-day trip collapsed to a
 * single day. Because a BUSY claim outranks the Graph calendar in
 * {@link MultiSlotPlanner}, confirming that reading would have deleted the
 * interviewer's whole week.
 * <p>
 * The transcriptions below are hand-authored from those screenshots — TIMES
 * ONLY. The originals name colleagues and a candidate in their event titles,
 * which is exactly what the image prompt forbids the model from copying, so no
 * title is reproduced here either.
 * <p>
 * The metric these tests actually protect is FABRICATED BUSY MINUTES ON
 * GENUINELY FREE DAYS. It was very large in production; it must be zero.
 */
class AvailabilityImageReadingTest {

    private static final Axis CALIBRATED =
            new Axis(LocalTime.of(8, 0), LocalTime.of(18, 0), "GMT+2");

    // ---- Ground truth: week of 24–28 August 2026 --------------------------
    // Mon 24 EMPTY, Wed 26 EMPTY. That is the whole point of the fixture.

    private static List<DayRead> weekOneTruth() {
        return List.of(
                free(LocalDate.of(2026, 8, 24)),
                partial(LocalDate.of(2026, 8, 25),
                        block("2026-08-25T09:00", "2026-08-25T09:45"),
                        block("2026-08-25T10:00", "2026-08-25T10:45"),
                        block("2026-08-25T13:00", "2026-08-25T14:00"),
                        block("2026-08-25T14:00", "2026-08-25T15:30")),
                free(LocalDate.of(2026, 8, 26)),
                partial(LocalDate.of(2026, 8, 27),
                        block("2026-08-27T09:00", "2026-08-27T09:45"),
                        block("2026-08-27T15:00", "2026-08-27T16:00")),
                partial(LocalDate.of(2026, 8, 28),
                        block("2026-08-28T09:00", "2026-08-28T10:00"),
                        block("2026-08-28T10:00", "2026-08-28T12:00"),
                        block("2026-08-28T12:00", "2026-08-28T14:00"),
                        block("2026-08-28T14:00", "2026-08-28T15:30")));
    }

    @Test
    void emptyWeekdaysProduceNoBusyTime_theProductionFabrication() {
        Derived derived = AvailabilityImageReading.derive(CALIBRATED, weekOneTruth());

        assertTrue(busyOn(derived, LocalDate.of(2026, 8, 24)).isEmpty(),
                "Monday 24 is empty in the calendar and must yield no busy interval");
        assertTrue(busyOn(derived, LocalDate.of(2026, 8, 26)).isEmpty(),
                "Wednesday 26 is empty in the calendar and must yield no busy interval");
        assertEquals(0L, fabricatedBusyMinutes(derived,
                        List.of(LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 26))),
                "fabricated busy minutes on genuinely free days must be zero");
        assertTrue(derived.freeDays().containsAll(
                        List.of(LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 26))),
                "both empty days are reported as positively free so the card can show them");
    }

    @Test
    void quarterHourEdgesSurvive_theDiagnosticFingerprint() {
        Derived derived = AvailabilityImageReading.derive(CALIBRATED, weekOneTruth());
        boolean anyNonHourBoundary = derived.busy().stream()
                .anyMatch(i -> i.start().getMinute() != 0 || i.end().getMinute() != 0);
        assertTrue(anyNonHourBoundary,
                "the real calendar has :45 and :30 edges; a reading with none is the "
                        + "fingerprint of a model emitting round guesses");
        assertFalse(AvailabilityImageReading.isAllRoundBoundaries(derived.busy()),
                "a faithful reading of this week is not all-round-boundaries");
    }

    @Test
    void adjacentBlocksMergeButGapsSurvive() {
        Derived derived = AvailabilityImageReading.derive(CALIBRATED, weekOneTruth());
        List<Interval> tuesday = busyOn(derived, LocalDate.of(2026, 8, 25));
        // 13:00–14:00 and 14:00–15:30 touch and merge; the 09:45–10:00 and
        // 10:45–13:00 gaps must NOT be swallowed the way "09:00–17:00" did.
        assertEquals(3, tuesday.size(),
                "two touching afternoon blocks merge, the morning gaps stay open");
        assertEquals(LocalDateTime.parse("2026-08-25T13:00"), tuesday.get(2).start());
        assertEquals(LocalDateTime.parse("2026-08-25T15:30"), tuesday.get(2).end());
    }

    // ---- The fabrication signature itself ---------------------------------

    @Test
    void fullDayClaimWithNothingTranscribed_isDiscardedNotTrusted() {
        // Exactly what v1 produced for the two empty days: "the whole day is
        // busy" with no measured block and no all-day band behind it.
        DayRead unsupported = new DayRead(LocalDate.of(2026, 8, 24), true, false, false,
                AvailabilitySchedulingPrompts.DAY_FULL, List.of());
        Derived derived = AvailabilityImageReading.derive(CALIBRATED, List.of(unsupported));

        assertTrue(derived.busy().isEmpty(),
                "a whole-day busy claim with nothing transcribed must not become evidence");
        assertTrue(derived.discardedDays().contains(LocalDate.of(2026, 8, 24)));
        assertFalse(derived.ambiguities().isEmpty(), "the discard is explained");
    }

    @Test
    void unreadableDayIsDiscarded_neverGuessed() {
        DayRead blurred = new DayRead(LocalDate.of(2026, 8, 25), false, false, false,
                AvailabilitySchedulingPrompts.DAY_PARTIAL,
                List.of(block("2026-08-25T09:00", "2026-08-25T17:00")));
        Derived derived = AvailabilityImageReading.derive(CALIBRATED, List.of(blurred));

        assertTrue(derived.busy().isEmpty(), "an unreadable column yields nothing");
        assertTrue(derived.discardedDays().contains(LocalDate.of(2026, 8, 25)));
    }

    @Test
    void withoutACalibratedAxis_timedBlocksAreDropped_allDayBandsSurvive() {
        Axis uncalibrated = new Axis(null, null, null);
        Derived derived = AvailabilityImageReading.derive(uncalibrated, List.of(
                partial(LocalDate.of(2026, 8, 25),
                        block("2026-08-25T10:00", "2026-08-25T10:45")),
                allDay(LocalDate.of(2026, 9, 3), false)));

        assertEquals(1, derived.busy().size(),
                "measured edges are meaningless without an axis; the all-day band is not");
        assertTrue(AvailabilityImageReading.isWholeDay(derived.busy().getFirst()));
        assertTrue(derived.discardedDays().contains(LocalDate.of(2026, 8, 25)));
    }

    @Test
    void freeVerdictContradictedByMeasuredBlocks_trustsTheBlocks() {
        DayRead contradictory = new DayRead(LocalDate.of(2026, 8, 25), true, false, false,
                AvailabilitySchedulingPrompts.DAY_FREE,
                List.of(block("2026-08-25T10:00", "2026-08-25T10:45")));
        Derived derived = AvailabilityImageReading.derive(CALIBRATED, List.of(contradictory));

        assertEquals(1, derived.busy().size(), "a measured block is evidence; the verdict is a summary");
        assertFalse(derived.freeDays().contains(LocalDate.of(2026, 8, 25)));
        assertFalse(derived.ambiguities().isEmpty());
    }

    @Test
    void blockDatedOutsideItsOwnColumn_isDropped() {
        DayRead misdated = new DayRead(LocalDate.of(2026, 8, 25), true, false, false,
                AvailabilitySchedulingPrompts.DAY_PARTIAL,
                List.of(block("2026-08-26T10:00", "2026-08-26T10:45")));
        Derived derived = AvailabilityImageReading.derive(CALIBRATED, List.of(misdated));
        assertTrue(derived.busy().isEmpty(), "a block on the wrong date is not evidence");
    }

    // ---- All-day bands: week of 31 Aug – 4 Sep ---------------------------

    @Test
    void allDayBandContinuingPastTheCrop_isFlaggedNotExtrapolated() {
        Derived derived = AvailabilityImageReading.derive(CALIBRATED, List.of(
                allDay(LocalDate.of(2026, 9, 3), true),
                allDay(LocalDate.of(2026, 9, 4), true)));

        assertEquals(2, derived.busy().size(), "both visible days of the band are busy");
        assertTrue(derived.busy().stream().allMatch(AvailabilityImageReading::isWholeDay));
        assertTrue(derived.ambiguities().stream().anyMatch(a -> a.contains("fortsætter")),
                "the continuation past the crop is surfaced, never extrapolated into "
                        + "days the image does not show");
        // The v1 failure was reporting Thursday only and losing Friday entirely.
        assertTrue(derived.busy().stream()
                        .anyMatch(i -> i.start().toLocalDate().equals(LocalDate.of(2026, 9, 4))),
                "the band's second visible day is not lost");
    }

    // ---- Trust heuristics -------------------------------------------------

    @Test
    void allRoundBoundaries_flagsTheProductionSignature() {
        // The v1 reading of week two: everything on the hour.
        List<Interval> rounded = List.of(
                interval("2026-08-31T09:00", "2026-08-31T10:00"),
                interval("2026-09-01T10:00", "2026-09-01T11:00"),
                interval("2026-09-02T09:00", "2026-09-02T10:00"));
        assertTrue(AvailabilityImageReading.isAllRoundBoundaries(rounded));

        Trust trust = AvailabilityImageReading.assess(CALIBRATED,
                new Derived(rounded, List.of(), List.of(), List.of()),
                LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 4));
        assertTrue(trust.suspicious());
        assertTrue(trust.reasons().contains("ALL_ROUND_BOUNDARIES"));
    }

    @Test
    void tooFewIntervalsToJudgeRoundness() {
        assertFalse(AvailabilityImageReading.isAllRoundBoundaries(List.of(
                        interval("2026-08-31T09:00", "2026-08-31T10:00"),
                        interval("2026-09-01T10:00", "2026-09-01T11:00"))),
                "two round intervals prove nothing — plenty of real meetings are hourly");
    }

    @Test
    void wholeDayIntervalsDoNotMaskTheRoundnessSignal() {
        // 00:00–23:59 is legitimately round; it must not be counted as evidence
        // either for or against the fingerprint.
        assertFalse(AvailabilityImageReading.isAllRoundBoundaries(List.of(
                        wholeDay("2026-08-26"), wholeDay("2026-08-27"), wholeDay("2026-08-28"))),
                "three whole-day intervals are not the round-boundary signature");
    }

    @Test
    void aWeekClaimedAlmostEntirelyBusy_isFlagged() {
        // The v1 week-one reading: effectively the entire working week.
        List<Interval> everything = List.of(
                wholeDay("2026-08-24"), wholeDay("2026-08-25"), wholeDay("2026-08-26"),
                wholeDay("2026-08-27"), wholeDay("2026-08-28"));
        assertTrue(AvailabilityImageReading.isTooDense(everything,
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 28)));
    }

    @Test
    void anOrdinaryBusyWeekIsNotFlaggedAsTooDense() {
        Derived derived = AvailabilityImageReading.derive(CALIBRATED, weekOneTruth());
        assertFalse(AvailabilityImageReading.isTooDense(derived.busy(),
                        LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 28)),
                "a real week with four booked mornings must not trip the density guard");
    }

    @Test
    void aGenuinelyFullWeekIsFlaggedButNeverBlocked() {
        // The honest false-positive case: someone really is booked solid. The
        // contract is that this is only ever a LOW-TRUST marker — the path stays
        // frictionless by owner decision, so nothing here may reject.
        Trust trust = AvailabilityImageReading.assess(CALIBRATED,
                new Derived(List.of(wholeDay("2026-08-24"), wholeDay("2026-08-25"),
                        wholeDay("2026-08-26"), wholeDay("2026-08-27"), wholeDay("2026-08-28")),
                        List.of(), List.of(), List.of()),
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 28));
        assertTrue(trust.suspicious(), "flagged");
        assertTrue(trust.reasons().contains("IMPLAUSIBLE_DENSITY"));
    }

    @Test
    void aFaithfulReadingIsNotSuspicious() {
        Derived derived = AvailabilityImageReading.derive(CALIBRATED, weekOneTruth());
        Trust trust = AvailabilityImageReading.assess(CALIBRATED, derived,
                LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 28));
        assertFalse(trust.suspicious(), "reasons: " + trust.reasons());
    }

    // ---- Reconciling re-reads --------------------------------------------

    @Test
    void twoAgreeingPassesKeepEveryDay() {
        Consensus consensus = AvailabilityImageReading.reconcile(
                List.of(weekOneTruth(), weekOneTruth()));
        assertEquals(5, consensus.days().size());
        assertTrue(consensus.unresolved().isEmpty());
        assertTrue(consensus.unanimous());
    }

    @Test
    void aDayTheTwoPassesReadDifferently_isDropped() {
        // Pass two puts Wednesday's meeting in the wrong row — the exact week-two
        // failure. Disagreement must lose the day, not average it.
        List<DayRead> passOne = List.of(partial(LocalDate.of(2026, 9, 2),
                block("2026-09-02T13:30", "2026-09-02T14:45")));
        List<DayRead> passTwo = List.of(partial(LocalDate.of(2026, 9, 2),
                block("2026-09-02T09:00", "2026-09-02T10:00")));

        Consensus consensus = AvailabilityImageReading.reconcile(List.of(passOne, passTwo));
        assertTrue(consensus.days().isEmpty(), "no majority, so no evidence");
        assertTrue(consensus.unresolved().contains(LocalDate.of(2026, 9, 2)));
        assertFalse(consensus.unanimous());
    }

    @Test
    void aThirdPassBreaksTheTie() {
        List<DayRead> right = List.of(partial(LocalDate.of(2026, 9, 2),
                block("2026-09-02T13:30", "2026-09-02T14:45")));
        List<DayRead> wrong = List.of(partial(LocalDate.of(2026, 9, 2),
                block("2026-09-02T09:00", "2026-09-02T10:00")));

        Consensus consensus =
                AvailabilityImageReading.reconcile(List.of(right, wrong, right));
        assertEquals(1, consensus.days().size());
        assertEquals(LocalDateTime.parse("2026-09-02T13:30"),
                consensus.days().getFirst().blocks().getFirst().start());
    }

    @Test
    void minorEdgeDifferencesStillAgree() {
        List<DayRead> a = List.of(partial(LocalDate.of(2026, 9, 2),
                block("2026-09-02T13:30", "2026-09-02T14:45")));
        List<DayRead> b = List.of(partial(LocalDate.of(2026, 9, 2),
                block("2026-09-02T13:35", "2026-09-02T14:45")));
        assertTrue(AvailabilityImageReading.dayAgrees(a.getFirst(), b.getFirst()),
                "a five-minute measurement difference is not a disagreement");
    }

    @Test
    void anExtraMeetingIsARealDisagreement() {
        DayRead one = partial(LocalDate.of(2026, 9, 4),
                block("2026-09-04T14:00", "2026-09-04T15:30"));
        DayRead two = partial(LocalDate.of(2026, 9, 4),
                block("2026-09-04T14:00", "2026-09-04T15:30"),
                block("2026-09-04T11:45", "2026-09-04T12:15"));
        assertFalse(AvailabilityImageReading.dayAgrees(one, two),
                "one pass seeing an extra meeting is a disagreement, not rounding");
    }

    @Test
    void aSinglePassPassesThroughButIsNotCorroborated() {
        Consensus consensus = AvailabilityImageReading.reconcile(List.of(weekOneTruth()));
        assertEquals(5, consensus.days().size(), "one reading is better than none");
        assertFalse(consensus.unanimous(), "but it was never corroborated");
    }

    @Test
    void noReadingsAtAllYieldsNothing() {
        Consensus consensus = AvailabilityImageReading.reconcile(List.of());
        assertTrue(consensus.days().isEmpty());
        assertFalse(consensus.unanimous());
    }

    // ---- helpers ----------------------------------------------------------

    private static long fabricatedBusyMinutes(Derived derived, List<LocalDate> trulyFreeDays) {
        long minutes = 0;
        for (Interval interval : derived.busy()) {
            if (trulyFreeDays.contains(interval.start().toLocalDate())) {
                minutes += java.time.Duration.between(interval.start(), interval.end()).toMinutes();
            }
        }
        return minutes;
    }

    private static List<Interval> busyOn(Derived derived, LocalDate day) {
        return derived.busy().stream()
                .filter(i -> i.start().toLocalDate().equals(day))
                .toList();
    }

    private static Block block(String start, String end) {
        return new Block(LocalDateTime.parse(start), LocalDateTime.parse(end),
                new BigDecimal("0.90"));
    }

    private static Interval interval(String start, String end) {
        return new Interval(LocalDateTime.parse(start), LocalDateTime.parse(end),
                new BigDecimal("0.90"));
    }

    private static Interval wholeDay(String date) {
        LocalDate day = LocalDate.parse(date);
        return new Interval(day.atStartOfDay(), day.atTime(23, 59), BigDecimal.ONE);
    }

    private static DayRead free(LocalDate date) {
        return new DayRead(date, true, false, false,
                AvailabilitySchedulingPrompts.DAY_FREE, List.of());
    }

    private static DayRead partial(LocalDate date, Block... blocks) {
        return new DayRead(date, true, false, false,
                AvailabilitySchedulingPrompts.DAY_PARTIAL, List.of(blocks));
    }

    private static DayRead allDay(LocalDate date, boolean continuesPastCrop) {
        return new DayRead(date, true, true, continuesPastCrop,
                AvailabilitySchedulingPrompts.DAY_FULL, List.of());
    }
}
