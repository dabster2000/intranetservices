package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
import dk.trustworks.intranet.recruitmentservice.model.enums.EvidenceSourceType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v2 day-by-day confirmation card. The card is the last line of defense
 * before a reading reaches the planner, and in v1 it could not express the one
 * thing that mattered: a day with no busy interval looked identical whether the
 * model had read it as free or never managed to read it at all. So a fabricated
 * busy day and an omitted busy day were equally invisible.
 */
class SlackAvailabilityDayCardTest {

    private static final LocalDate MON = LocalDate.of(2026, 8, 24);

    @Test
    void freeAndUnreadableDaysAreDistinguished() {
        RecruitmentAvailabilityEvidence evidence = image(MON, MON.plusDays(4));
        evidence.setUnreadableDays("2026-08-27");
        String text = SlackAvailabilityViews.summaryText(evidence,
                List.of(busy(MON.plusDays(1).atTime(9, 0), MON.plusDays(1).atTime(9, 45))), true);

        assertTrue(text.contains("mandag den 24. august: fri"), text);
        assertTrue(text.contains("tirsdag den 25. august: optaget kl. 09.00–09.45"), text);
        assertTrue(text.contains("onsdag den 26. august: fri"), text);
        assertTrue(text.contains("torsdag den 27. august: kunne ikke læses — ikke registreret"), text);
        assertTrue(text.contains("fredag den 28. august: fri"), text);
    }

    @Test
    void theProductionMisreadWouldNowBeObvious() {
        // What the interviewer would have seen if v1's reading were rendered by
        // the v2 card: two days it claimed busy, against three it did not.
        RecruitmentAvailabilityEvidence evidence = image(MON, MON.plusDays(4));
        String text = SlackAvailabilityViews.summaryText(evidence, List.of(
                busy(MON.atTime(9, 0), MON.atTime(17, 0)),
                busy(MON.plusDays(2).atStartOfDay(), MON.plusDays(2).atTime(23, 59))), true);

        assertTrue(text.contains("mandag den 24. august: optaget kl. 09.00–17.00"), text);
        assertTrue(text.contains("onsdag den 26. august: optaget hele dagen"), text);
        // The point: the days it said nothing about are now stated out loud.
        assertTrue(text.contains("tirsdag den 25. august: fri"), text);
    }

    @Test
    void multiDayIntervalRendersOnEveryDayItCovers() {
        RecruitmentAvailabilityEvidence evidence = image(MON.plusDays(7), MON.plusDays(11));
        String text = SlackAvailabilityViews.summaryText(evidence, List.of(
                busy(MON.plusDays(10).atStartOfDay(), MON.plusDays(11).atTime(23, 59))), true);

        // The UDLANDSTUR case: a band starting Thursday and running through Friday.
        assertTrue(text.contains("torsdag den 3. september: optaget hele dagen"), text);
        assertTrue(text.contains("fredag den 4. september: optaget hele dagen"), text);
        assertTrue(text.contains("mandag den 31. august: fri"), text);
    }

    @Test
    void aThreeWeekRangeStaysInsideSlacksTextLimit() {
        // MAX_DAY_LINES is 21; the worst realistic case is every one of those
        // days carrying four intervals. It must not be truncated, because the
        // clamp would silently cut the "Er det korrekt?" line off the bottom.
        LocalDate from = MON;
        LocalDate to = MON.plusDays(20);
        RecruitmentAvailabilityEvidence evidence = image(from, to);
        List<RecruitmentAvailabilityConstraint> constraints = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            constraints.add(busy(day.atTime(9, 0), day.atTime(9, 45)));
            constraints.add(busy(day.atTime(10, 15), day.atTime(11, 30)));
            constraints.add(busy(day.atTime(13, 0), day.atTime(14, 45)));
            constraints.add(busy(day.atTime(15, 30), day.atTime(16, 15)));
        }
        String text = SlackAvailabilityViews.summaryText(evidence, constraints, true);

        assertTrue(text.length() < 3000,
                "a full three-week card must fit Slack's 3000-char section limit, was "
                        + text.length());
        assertTrue(text.contains("Er det korrekt?"),
                "the confirmation question survives the worst case");
    }

    @Test
    void beyondTheDayLineLimitItFallsBackToAFlatList() {
        RecruitmentAvailabilityEvidence evidence = image(MON, MON.plusMonths(3));
        String text = SlackAvailabilityViews.summaryText(evidence,
                List.of(busy(MON.atTime(9, 0), MON.atTime(10, 0))), true);

        // No day lines for a 90-day range — it would be unreadable and would
        // blow the clamp. The flat interval form returns.
        assertFalse(text.contains(": fri"), text);
        assertTrue(text.contains("mandag den 24. august kl. 09.00–10.00: optaget"), text);
    }

    @Test
    void noCoveredRangeFallsBackSafely() {
        RecruitmentAvailabilityEvidence evidence = image(null, null);
        String text = SlackAvailabilityViews.summaryText(evidence,
                List.of(busy(MON.atTime(9, 0), MON.atTime(10, 0))), true);
        assertTrue(text.contains("mandag den 24. august kl. 09.00–10.00: optaget"), text);
        assertTrue(text.contains("Er det korrekt?"), text);
    }

    @Test
    void nonBusyConstraintsKeepTheirOwnLine() {
        RecruitmentAvailabilityEvidence evidence = image(MON, MON.plusDays(1));
        RecruitmentAvailabilityConstraint preferred = new RecruitmentAvailabilityConstraint();
        preferred.setType(AvailabilityConstraintType.PREFERRED);
        preferred.setStartAt(MON.atTime(13, 0));
        preferred.setEndAt(MON.atTime(16, 0));

        String text = SlackAvailabilityViews.summaryText(evidence, List.of(preferred), true);
        assertTrue(text.contains("mandag den 24. august: fri"), text);
        assertTrue(text.contains("helst"), "a soft preference is not a busy day: " + text);
    }

    // ---- Source disclosure ------------------------------------------------

    @Test
    void imageCardDisclosesItsSource_andWhetherItWasCorroborated() {
        RecruitmentAvailabilityEvidence corroborated = image(MON, MON);
        assertTrue(SlackAvailabilityViews.summaryText(corroborated, List.of(), true)
                .contains("dobbeltchecket"));

        RecruitmentAvailabilityEvidence single = image(MON, MON);
        single.setReadTrust("NOT_CORROBORATED");
        String text = SlackAvailabilityViews.summaryText(single, List.of(), true);
        assertTrue(text.contains("kunne ikke dobbeltcheckes"), text);
    }

    @Test
    void textEvidenceMakesNoImageClaim() {
        RecruitmentAvailabilityEvidence evidence = image(MON, MON);
        evidence.setSourceType(EvidenceSourceType.TEXT);
        String text = SlackAvailabilityViews.summaryText(evidence, List.of(), true);
        assertFalse(text.contains("kalenderbillede"), text);
    }

    // ---- The prefill the Ret modal opens with -----------------------------

    @Test
    void correctionPrefillIsEditableDayLines_withBlanksForUnreadableDays() {
        RecruitmentAvailabilityEvidence evidence = image(MON, MON.plusDays(2));
        evidence.setUnreadableDays("2026-08-26");
        String prefill = SlackAvailabilityViews.correctionPrefill(evidence,
                List.of(busy(MON.atTime(9, 0), MON.atTime(9, 45))));

        String[] lines = prefill.split("\n");
        assertEquals(3, lines.length, prefill);
        assertEquals("mandag den 24. august: optaget kl. 09.00–09.45", lines[0]);
        assertEquals("tirsdag den 25. august: fri", lines[1]);
        // An unreadable day becomes an empty line to fill in, not a status
        // message the interviewer has to delete first.
        assertEquals("onsdag den 26. august: ", lines[2]);
    }

    @Test
    void prefillIsClampedForTheModalInput() {
        assertEquals("", SlackAvailabilityViews.clampPrefill(null));
        String huge = "x".repeat(SlackAvailabilityViews.PREFILL_MAX + 500);
        assertEquals(SlackAvailabilityViews.PREFILL_MAX,
                SlackAvailabilityViews.clampPrefill(huge).length());
    }

    // ---- helpers ----------------------------------------------------------

    private static RecruitmentAvailabilityEvidence image(LocalDate from, LocalDate to) {
        RecruitmentAvailabilityEvidence evidence = new RecruitmentAvailabilityEvidence();
        evidence.setUuid("evidence-1");
        evidence.setRequestUuid("request-1");
        evidence.setUserUuid("user-1");
        evidence.setSourceType(EvidenceSourceType.IMAGE);
        evidence.setLanguage("da");
        evidence.setTimezone("Europe/Copenhagen");
        evidence.setCoveredFrom(from);
        evidence.setCoveredTo(to);
        return evidence;
    }

    private static RecruitmentAvailabilityConstraint busy(LocalDateTime start, LocalDateTime end) {
        RecruitmentAvailabilityConstraint constraint = new RecruitmentAvailabilityConstraint();
        constraint.setType(AvailabilityConstraintType.BUSY);
        constraint.setStartAt(start);
        constraint.setEndAt(end);
        return constraint;
    }
}
