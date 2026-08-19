package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.BoardCardSubStatus;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewDecision;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentBoardService.SubStatusInputs;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins every rung of the board sub-status ladder (pipeline sub-status
 * feature) — DB-free, because the derivation is static and pure by design:
 * these transitions ARE the feature's promise that nobody ever switches a
 * sub-status by hand.
 */
class RecruitmentBoardSubStatusTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 12, 0);
    private static final String APP = "app-1";
    private static final String CAND = "cand-1";

    // ---- Interview-stage ladder -------------------------------------------

    @Test
    void interviewStage_noInterview_isBook() {
        BoardCardSubStatus sub = derive(application(RecruitmentStage.INTERVIEW_1),
                practicePosition(), SubStatusInputs.EMPTY, true);
        assertEquals(BoardCardSubStatus.Code.BOOK, sub.code());
        assertNull(sub.interviewUuid());
    }

    @Test
    void interviewStage_liveMethodBRequest_isBooking() {
        SubStatusInputs inputs = new SubStatusInputs(Map.of(), Map.of(),
                Set.of(APP), Set.of(), Set.of());
        BoardCardSubStatus sub = derive(application(RecruitmentStage.INTERVIEW_1),
                practicePosition(), inputs, true);
        assertEquals(BoardCardSubStatus.Code.BOOKING, sub.code());
    }

    @Test
    void interviewStage_futureInterview_isAwaiting_withDateAndLocation() {
        RecruitmentInterview interview = interview(1, NOW.plusDays(2), "HP2");
        BoardCardSubStatus sub = derive(application(RecruitmentStage.INTERVIEW_1),
                practicePosition(), inputsWith(interview), true);
        assertEquals(BoardCardSubStatus.Code.AWAITING, sub.code());
        assertEquals(interview.getUuid(), sub.interviewUuid());
        assertEquals(NOW.plusDays(2), sub.interviewScheduledAt());
        assertEquals("HP2", sub.interviewLocation());
        assertNull(sub.scorecardsSubmitted());
    }

    @Test
    void interviewStage_datePassed_scorecardsOutstanding_isVotering() {
        RecruitmentInterview interview = interview(1, NOW.minusHours(3), "HP2");
        SubStatusInputs inputs = inputsWith(interview,
                scorecard(interview, "interviewer-a"));
        BoardCardSubStatus sub = derive(application(RecruitmentStage.INTERVIEW_1),
                practicePosition(), inputs, true);
        assertEquals(BoardCardSubStatus.Code.VOTERING, sub.code());
        assertEquals(1, sub.scorecardsSubmitted());
        assertEquals(2, sub.scorecardsExpected());
    }

    @Test
    void interviewStage_allScorecardsIn_isDecide() {
        RecruitmentInterview interview = interview(1, NOW.minusDays(1), null);
        SubStatusInputs inputs = inputsWith(interview,
                scorecard(interview, "interviewer-a"), scorecard(interview, "interviewer-b"));
        BoardCardSubStatus sub = derive(application(RecruitmentStage.INTERVIEW_1),
                practicePosition(), inputs, true);
        assertEquals(BoardCardSubStatus.Code.DECIDE, sub.code());
    }

    /**
     * A kept scorecard from a REMOVED interviewer counts in the progress
     * counter but never flips votering to decide — the flip needs every
     * currently assigned interviewer in (the debrief's exact rule).
     */
    @Test
    void interviewStage_strangerScorecard_countsButDoesNotFlip() {
        RecruitmentInterview interview = interview(1, NOW.minusDays(1), null);
        SubStatusInputs inputs = inputsWith(interview,
                scorecard(interview, "interviewer-a"), scorecard(interview, "removed-colleague"));
        BoardCardSubStatus sub = derive(application(RecruitmentStage.INTERVIEW_1),
                practicePosition(), inputs, true);
        assertEquals(BoardCardSubStatus.Code.VOTERING, sub.code());
        assertEquals(2, sub.scorecardsSubmitted());
        assertEquals(2, sub.scorecardsExpected());
    }

    /** An early scorecard marks the interview HELD — votering starts then,
     * even with the calendar date still ahead. */
    @Test
    void interviewStage_heldBeforeDate_isVotering() {
        RecruitmentInterview interview = interview(1, NOW.plusDays(1), null);
        interview.setStatus(RecruitmentInterviewStatus.HELD);
        SubStatusInputs inputs = inputsWith(interview, scorecard(interview, "interviewer-a"));
        BoardCardSubStatus sub = derive(application(RecruitmentStage.INTERVIEW_1),
                practicePosition(), inputs, true);
        assertEquals(BoardCardSubStatus.Code.VOTERING, sub.code());
    }

    /** Defensive: a row with no interviewers skips votering entirely. */
    @Test
    void interviewStage_zeroInterviewers_goesStraightToDecide() {
        RecruitmentInterview interview = interview(1, NOW.minusDays(1), null);
        interview.setInterviewerUuids(List.of());
        BoardCardSubStatus sub = derive(application(RecruitmentStage.INTERVIEW_1),
                practicePosition(), inputsWith(interview), true);
        assertEquals(BoardCardSubStatus.Code.DECIDE, sub.code());
    }

    @Test
    void interviewStage_pendingDecision_isInform_outcomeForDecidersOnly() {
        RecruitmentInterview interview = interview(2, NOW.minusDays(2), null);
        interview.setDecision(RecruitmentInterviewDecision.ADVANCE);
        SubStatusInputs inputs = inputsWith(interview);

        BoardCardSubStatus decider = derive(application(RecruitmentStage.INTERVIEW_2),
                practicePosition(), inputs, true);
        assertEquals(BoardCardSubStatus.Code.INFORM, decider.code());
        assertEquals(RecruitmentInterviewDecision.ADVANCE, decider.decidedOutcome());

        BoardCardSubStatus reader = derive(application(RecruitmentStage.INTERVIEW_2),
                practicePosition(), inputs, false);
        assertEquals(BoardCardSubStatus.Code.INFORM, reader.code());
        assertNull(reader.decidedOutcome(), "read-only viewers get an outcome-neutral chip");
    }

    /**
     * The held-pivot compares Copenhagen wall-clock against the stored
     * wall-clock — a slot starting exactly now counts as held (the
     * interview is in the room, votering may begin).
     */
    @Test
    void interviewStage_slotStartingExactlyNow_countsAsHeld() {
        RecruitmentInterview interview = interview(1, NOW, null);
        BoardCardSubStatus sub = derive(application(RecruitmentStage.INTERVIEW_1),
                practicePosition(), inputsWith(interview), true);
        assertEquals(BoardCardSubStatus.Code.VOTERING, sub.code());
    }

    // ---- OFFER ladder ------------------------------------------------------

    @Test
    void offer_signed_outranksEverything() {
        SubStatusInputs inputs = new SubStatusInputs(Map.of(), Map.of(), Set.of(),
                Set.of(CAND), Set.of(CAND));
        BoardCardSubStatus sub = derive(application(RecruitmentStage.OFFER),
                practicePosition(), inputs, true);
        assertEquals(BoardCardSubStatus.Code.SIGNED, sub.code());
    }

    @Test
    void offer_contractSent_isAwaitingSignature() {
        SubStatusInputs inputs = new SubStatusInputs(Map.of(), Map.of(), Set.of(),
                Set.of(CAND), Set.of());
        BoardCardSubStatus sub = derive(application(RecruitmentStage.OFFER),
                practicePosition(), inputs, true);
        assertEquals(BoardCardSubStatus.Code.AWAITING_SIGNATURE, sub.code());
    }

    @Test
    void offer_practiceTrackWithoutTeam_isTeamMissing() {
        BoardCardSubStatus sub = derive(application(RecruitmentStage.OFFER),
                practicePosition(), SubStatusInputs.EMPTY, true);
        assertEquals(BoardCardSubStatus.Code.TEAM_MISSING, sub.code());
    }

    @Test
    void offer_teamAssigned_isContractNotSent() {
        RecruitmentApplication application = application(RecruitmentStage.OFFER);
        application.setAssignedTeamUuid("team-1");
        BoardCardSubStatus sub = derive(application, practicePosition(),
                SubStatusInputs.EMPTY, true);
        assertEquals(BoardCardSubStatus.Code.CONTRACT_NOT_SENT, sub.code());
    }

    /** The team gate is a practice-track rule only — other tracks go
     * straight to the contract question. */
    @Test
    void offer_staffTrackWithoutTeam_isContractNotSent() {
        RecruitmentPosition position = new RecruitmentPosition();
        position.setHiringTrack(RecruitmentHiringTrack.STAFF_ROLE);
        BoardCardSubStatus sub = derive(application(RecruitmentStage.OFFER),
                position, SubStatusInputs.EMPTY, true);
        assertEquals(BoardCardSubStatus.Code.CONTRACT_NOT_SENT, sub.code());
    }

    // ---- No ladder ---------------------------------------------------------

    @Test
    void screeningAndHired_haveNoSubStatus() {
        assertNull(derive(application(RecruitmentStage.SCREENING), practicePosition(),
                SubStatusInputs.EMPTY, true));
        assertNull(derive(application(RecruitmentStage.HIRED), practicePosition(),
                SubStatusInputs.EMPTY, true));
    }

    @Test
    void roundOf_mapsInterviewStagesOnly() {
        assertEquals(1, RecruitmentBoardService.roundOf(RecruitmentStage.INTERVIEW_1));
        assertEquals(2, RecruitmentBoardService.roundOf(RecruitmentStage.INTERVIEW_2));
        assertEquals(3, RecruitmentBoardService.roundOf(RecruitmentStage.INTERVIEW_3));
        assertNull(RecruitmentBoardService.roundOf(RecruitmentStage.SCREENING));
        assertNull(RecruitmentBoardService.roundOf(RecruitmentStage.OFFER));
        assertNull(RecruitmentBoardService.roundOf(RecruitmentStage.HIRED));
    }

    // ---- Fixtures ----------------------------------------------------------

    private static BoardCardSubStatus derive(RecruitmentApplication application,
                                             RecruitmentPosition position,
                                             SubStatusInputs inputs,
                                             boolean viewerCanDecide) {
        return RecruitmentBoardService.deriveSubStatus(application, position, inputs,
                NOW, viewerCanDecide);
    }

    private static RecruitmentApplication application(RecruitmentStage stage) {
        RecruitmentApplication application = new RecruitmentApplication();
        application.setUuid(APP);
        application.setCandidateUuid(CAND);
        application.setStage(stage);
        return application;
    }

    private static RecruitmentPosition practicePosition() {
        RecruitmentPosition position = new RecruitmentPosition();
        position.setHiringTrack(RecruitmentHiringTrack.PRACTICE_TEAM);
        return position;
    }

    private static RecruitmentInterview interview(int round, LocalDateTime scheduledAt,
                                                  String location) {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid("interview-" + round);
        interview.setApplicationUuid(APP);
        interview.setKind(RecruitmentInterviewKind.ROUND);
        interview.setRound(round);
        interview.setScheduledAt(scheduledAt);
        interview.setLocation(location);
        interview.setStatus(RecruitmentInterviewStatus.SCHEDULED);
        interview.setInterviewerUuids(List.of("interviewer-a", "interviewer-b"));
        return interview;
    }

    private static RecruitmentScorecard scorecard(RecruitmentInterview interview,
                                                  String interviewerUuid) {
        RecruitmentScorecard scorecard = new RecruitmentScorecard();
        scorecard.setUuid("card-" + interviewerUuid);
        scorecard.setInterviewUuid(interview.getUuid());
        scorecard.setInterviewerUuid(interviewerUuid);
        return scorecard;
    }

    private static SubStatusInputs inputsWith(RecruitmentInterview interview,
                                              RecruitmentScorecard... scorecards) {
        return new SubStatusInputs(
                Map.of(APP, interview),
                scorecards.length == 0 ? Map.of()
                        : Map.of(interview.getUuid(), List.of(scorecards)),
                Set.of(), Set.of(), Set.of());
    }
}
