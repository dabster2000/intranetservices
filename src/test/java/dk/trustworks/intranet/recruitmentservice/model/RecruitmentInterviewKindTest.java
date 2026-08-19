package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interview-kind vocabulary in isolation: which kinds are scored,
 * which carry a round, and which map onto the stage machine.
 * <p>
 * These are the two predicates every other surface keys off —
 * {@code scorecardRequired} on the wire, the debrief query, the SLA
 * scorecard sweeps, the Slack scorecard buttons and the "focus areas"
 * line all reduce to {@code takesScorecard()}; the blind rule's
 * "after decision" unlock reduces to {@link RecruitmentInterview#roundStage()}.
 * A new kind that quietly answers {@code true} to either would start
 * demanding scorecards nobody can submit.
 */
class RecruitmentInterviewKindTest {

    @Test
    void onlyTheRoundIsScoredAndNumbered() {
        assertTrue(RecruitmentInterviewKind.ROUND.takesScorecard());
        assertTrue(RecruitmentInterviewKind.ROUND.hasRound());

        assertFalse(RecruitmentInterviewKind.INFORMAL.takesScorecard());
        assertFalse(RecruitmentInterviewKind.INFORMAL.hasRound());

        assertFalse(RecruitmentInterviewKind.OFFER.takesScorecard(),
                "an offer meeting takes no scorecard (owner decision 2026-08-19)");
        assertFalse(RecruitmentInterviewKind.OFFER.hasRound(),
                "an offer meeting carries no round — the CHECK constraint pins round IS NULL");
    }

    @Test
    void everyKindIsAccountedFor() {
        // The guard on a fourth kind: whoever adds it must decide, here,
        // whether it is scored and numbered — not discover it later from
        // a "round null" in someone's calendar.
        assertEquals(List.of(RecruitmentInterviewKind.INFORMAL,
                        RecruitmentInterviewKind.ROUND,
                        RecruitmentInterviewKind.OFFER),
                List.of(RecruitmentInterviewKind.values()),
                "kinds are persisted as strings and CHECK-pinned — append only, never reorder");
    }

    @Test
    void roundStage_mapsRoundsOntoThePipelineAndNothingElse() {
        assertEquals(RecruitmentStage.INTERVIEW_1, stageOf(RecruitmentInterviewKind.ROUND, 1));
        assertEquals(RecruitmentStage.INTERVIEW_2, stageOf(RecruitmentInterviewKind.ROUND, 2));
        assertEquals(RecruitmentStage.INTERVIEW_3, stageOf(RecruitmentInterviewKind.ROUND, 3));

        assertNull(stageOf(RecruitmentInterviewKind.INFORMAL, null));
        assertNull(stageOf(RecruitmentInterviewKind.OFFER, null),
                "an offer meeting is scheduled AROUND the OFFER stage — it does not gate it, "
                        + "so it must never be swept up by the round-only SLA and decision paths");
    }

    @Test
    void roundStage_ignoresARoundNumberOnAKindThatHasNone() {
        // Defensive: a hand-patched row could carry both. The kind wins,
        // so a stray number can never turn an offer meeting into a round
        // whose scorecards the blind rule would then start unlocking.
        assertNull(stageOf(RecruitmentInterviewKind.OFFER, 2));
        assertNull(stageOf(RecruitmentInterviewKind.INFORMAL, 2));
    }

    private static RecruitmentStage stageOf(RecruitmentInterviewKind kind, Integer round) {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setKind(kind);
        interview.setRound(round);
        return interview.roundStage();
    }
}
