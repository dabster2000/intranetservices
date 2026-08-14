package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.slack.SlackSchedulingViews;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingOrchestrator.RecheckFailureBranch;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentSchedulingOrchestrator.afterSelectedSlotInvalid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure Phase 11 rules (plan §11): the default candidate deadline
 * (3 business days, 16:00 — defaults §29.2), the Danish option list of
 * the invitation mail, the public token's shape gate, and the
 * finalization saga's recheck-failure branch (spec §16.3). All DB-free —
 * this tier gates deploys.
 */
class SchedulingCandidatePureTest {

    // ---- Default candidate deadline (defaults §29.2) -----------------------

    @Test
    void deadline_threeBusinessDays_at1600() {
        // Monday → Thursday.
        assertEquals(LocalDateTime.parse("2026-08-20T16:00:00"),
                RecruitmentSchedulingCandidateService.defaultCandidateDeadline(
                        LocalDateTime.parse("2026-08-17T09:30:00")));
    }

    @Test
    void deadline_skipsWeekends() {
        // Friday → Wednesday (Sat+Sun don't count).
        assertEquals(LocalDateTime.parse("2026-08-19T16:00:00"),
                RecruitmentSchedulingCandidateService.defaultCandidateDeadline(
                        LocalDateTime.parse("2026-08-14T11:00:00")));
        // Saturday start → the same Wednesday.
        assertEquals(LocalDateTime.parse("2026-08-19T16:00:00"),
                RecruitmentSchedulingCandidateService.defaultCandidateDeadline(
                        LocalDateTime.parse("2026-08-15T08:00:00")));
    }

    @Test
    void deadline_ignoresTheSendTimeOfDay() {
        // A 23:59 send counts from the same date as a 00:01 send.
        assertEquals(
                RecruitmentSchedulingCandidateService.defaultCandidateDeadline(
                        LocalDateTime.parse("2026-08-17T00:01:00")),
                RecruitmentSchedulingCandidateService.defaultCandidateDeadline(
                        LocalDateTime.parse("2026-08-17T23:59:00")));
    }

    // ---- The invitation mail's option list ---------------------------------

    @Test
    void optionsList_numberedDanishLines() {
        RecruitmentProposedSlot first = new RecruitmentProposedSlot();
        first.setSlotStart(LocalDateTime.parse("2026-08-18T10:00:00"));
        first.setSlotEnd(LocalDateTime.parse("2026-08-18T11:00:00"));
        RecruitmentProposedSlot second = new RecruitmentProposedSlot();
        second.setSlotStart(LocalDateTime.parse("2026-08-20T13:00:00"));
        second.setSlotEnd(LocalDateTime.parse("2026-08-20T14:00:00"));

        String list = RecruitmentSchedulingCandidateService.optionsList(
                List.of(first, second));

        assertEquals("""
                1. tirsdag den 18. august kl. 10.00–11.00
                2. torsdag den 20. august kl. 13.00–14.00""", list);
    }

    @Test
    void danishDayTime_matchesTheSpecFormat() {
        assertEquals("fredag den 21. august kl. 16.00",
                SlackSchedulingViews.danishDayTime(
                        LocalDateTime.parse("2026-08-21T16:00:00")));
    }

    // ---- Token shape gate --------------------------------------------------

    @Test
    void tokenShape_acceptsExactly43Base64urlChars() {
        String valid = "A".repeat(20) + "b-_" + "9".repeat(20); // 43 chars
        assertEquals(43, valid.length());
        assertTrue(RecruitmentSchedulingCandidateService.TOKEN_SHAPE
                .matcher(valid).matches());
        assertFalse(RecruitmentSchedulingCandidateService.TOKEN_SHAPE
                .matcher("A".repeat(42)).matches(), "too short");
        assertFalse(RecruitmentSchedulingCandidateService.TOKEN_SHAPE
                .matcher("A".repeat(44)).matches(), "too long");
        assertFalse(RecruitmentSchedulingCandidateService.TOKEN_SHAPE
                .matcher("A".repeat(42) + "+").matches(), "not base64url");
        assertFalse(RecruitmentSchedulingCandidateService.TOKEN_SHAPE
                .matcher("A".repeat(42) + "=").matches(), "padding never appears");
    }

    @Test
    void generatedTokens_passTheShapeGate() {
        for (int i = 0; i < 20; i++) {
            assertTrue(RecruitmentSchedulingCandidateService.TOKEN_SHAPE
                    .matcher(RecruitmentConsentService.generateToken()).matches());
        }
    }

    // ---- Finalization recheck-failure branch (spec §16.3) ------------------

    @Test
    void recheckFailure_remainingOptions_candidateChoosesAgain() {
        assertEquals(RecheckFailureBranch.CHOOSE_AGAIN,
                afterSelectedSlotInvalid(2, true));
        assertEquals(RecheckFailureBranch.CHOOSE_AGAIN,
                afterSelectedSlotInvalid(1, false),
                "remaining options beat the window state — the candidate chooses");
    }

    @Test
    void recheckFailure_nothingLeft_researchesWhileTheWindowAllows() {
        assertEquals(RecheckFailureBranch.RESEARCH,
                afterSelectedSlotInvalid(0, true));
    }

    @Test
    void recheckFailure_nothingLeftAndWindowSpent_handsBack() {
        assertEquals(RecheckFailureBranch.HAND_BACK,
                afterSelectedSlotInvalid(0, false));
    }
}
