package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DM the unattended-review-queue nudge sends (2026-09-02).
 * <p>
 * The nudge exists because review-first failed silently once: four rejection
 * letters queued in August 2026, none approved, the templates eventually
 * switched off — while the acknowledgement letter kept promising an answer
 * within four working days. The queue had no clock on it.
 */
class EmailReviewQueueNudgeTest {

    @Test
    void namesNoCandidate() {
        // Load-bearing. Every HR/RECRUITMENT holder gets this DM, and the
        // oldest queued letter may belong to a candidate some of them cannot
        // read -- so the message is counts and nothing else.
        String text = RecruitmentSlaService.emailReviewNudgeText(4, 73);
        assertFalse(text.matches("(?s).*\\b[A-ZÆØÅ][a-zæøå]+ [A-ZÆØÅ][a-zæøå]+\\b.*"),
                "the nudge must not carry a person's name: " + text);
    }

    @Test
    void countsInDanish_singularAndPlural() {
        assertTrue(RecruitmentSlaService.emailReviewNudgeText(1, 50)
                .contains("1 kandidatmail venter"));
        assertTrue(RecruitmentSlaService.emailReviewNudgeText(7, 50)
                .contains("7 kandidatmails venter"));
    }

    @Test
    void agesInHoursUnderTwoDaysAndInDaysBeyond() {
        // "den ældste har ventet 60 timer" reads worse than "2 dage" once it
        // is genuinely days -- and the threshold that triggers this is 48h.
        assertTrue(RecruitmentSlaService.emailReviewNudgeText(2, 30).contains("30 timer"));
        assertTrue(RecruitmentSlaService.emailReviewNudgeText(2, 47).contains("47 timer"));
        assertTrue(RecruitmentSlaService.emailReviewNudgeText(2, 48).contains("2 dage"));
        assertTrue(RecruitmentSlaService.emailReviewNudgeText(2, 100).contains("4 dage"));
    }

    @Test
    void saysNothingHasBeenSentAndWhereToAct() {
        // The August failure was people assuming the queue had drained.
        String text = RecruitmentSlaService.emailReviewNudgeText(3, 60);
        assertTrue(text.contains("Ingen af dem er sendt endnu"));
        assertTrue(text.contains("/recruitment"));
    }

    @Test
    void defaultThresholdSitsInsideTheFourWorkingDayPromise() {
        // The acknowledgement letter promises an answer within four working
        // days. A nudge threshold at or beyond that would only fire once the
        // promise was already broken.
        assertEquals(48, RecruitmentSlaThresholds.DEFAULT_EMAIL_REVIEW_STALE_HOURS);
        assertTrue(RecruitmentSlaThresholds.DEFAULT_EMAIL_REVIEW_STALE_HOURS < 4 * 24);
    }
}
