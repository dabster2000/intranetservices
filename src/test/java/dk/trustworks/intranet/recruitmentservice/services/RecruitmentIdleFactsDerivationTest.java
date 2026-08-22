package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interview-derived halves of {@link RecruitmentIdleFacts}: "is the next
 * step already booked?" and "is this round waiting on somebody, or ready to
 * decide?". Pure — the two helpers take collections, so no Quarkus and no DB.
 *
 * <p>The cases are the shapes that actually exist in the production stream:
 * a meeting still running, a cancelled round, an Airtable round imported with
 * no interviewers, and a round the decision already passed by.
 */
class RecruitmentIdleFactsDerivationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 22, 12, 0);
    private static final String APP = "app-1";
    private static final String ANNA = "interviewer-anna";
    private static final String BO = "interviewer-bo";

    // ---- booked -----------------------------------------------------------

    @Test
    void aFutureInterviewOfAnyKindCountsAsBooked() {
        for (RecruitmentInterviewKind kind : RecruitmentInterviewKind.values()) {
            RecruitmentInterview future = interview(kind, kind.hasRound() ? 2 : null,
                    NOW.plusDays(4), RecruitmentInterviewStatus.SCHEDULED, List.of(ANNA));

            assertEquals(Set.of(APP), RecruitmentIdleFacts.booked(List.of(future), NOW),
                    kind + " in the calendar is a next step, whether it is a round, "
                            + "an informal chat or the offer meeting");
        }
    }

    @Test
    void aCancelledFutureInterviewIsNotBooked() {
        RecruitmentInterview cancelled = interview(RecruitmentInterviewKind.ROUND, 2,
                NOW.plusDays(4), RecruitmentInterviewStatus.CANCELLED, List.of(ANNA));

        assertTrue(RecruitmentIdleFacts.booked(List.of(cancelled), NOW).isEmpty(),
                "Martin Nöhrlind Lehmann's round was cancelled — that is precisely "
                        + "when somebody has to act");
    }

    @Test
    void aPastInterviewIsNotBooked() {
        RecruitmentInterview held = interview(RecruitmentInterviewKind.ROUND, 1,
                NOW.minusDays(2), RecruitmentInterviewStatus.HELD, List.of(ANNA));

        assertTrue(RecruitmentIdleFacts.booked(List.of(held), NOW).isEmpty());
    }

    // ---- rounds: waiting vs ready ------------------------------------------

    @Test
    void aRoundMissingOneCardIsWaitingNotReady() {
        RecruitmentInterview round = interview(RecruitmentInterviewKind.ROUND, 1,
                NOW.minusDays(2), RecruitmentInterviewStatus.HELD, List.of(ANNA, BO));
        Map<String, List<RecruitmentScorecard>> cards = cards(round, ANNA);

        assertEquals(Set.of(APP), roundsWhere(round, cards, RecruitmentStage.INTERVIEW_1, false),
                "Rasmus Helmer-Villadsen: 1 of 2 cards in");
        assertTrue(roundsWhere(round, cards, RecruitmentStage.INTERVIEW_1, true).isEmpty());
    }

    @Test
    void aRoundWithEveryCardInIsReadyNotWaiting() {
        RecruitmentInterview round = interview(RecruitmentInterviewKind.ROUND, 2,
                NOW.minusDays(3), RecruitmentInterviewStatus.HELD, List.of(ANNA, BO));
        Map<String, List<RecruitmentScorecard>> cards = cards(round, ANNA, BO);

        assertEquals(Set.of(APP), roundsWhere(round, cards, RecruitmentStage.INTERVIEW_2, true),
                "Kim Petersen: both cards in, the process waits for one decision");
        assertTrue(roundsWhere(round, cards, RecruitmentStage.INTERVIEW_2, false).isEmpty());
    }

    @Test
    void aRoundWithNoAssignedInterviewersIsNeitherWaitingNorReady() {
        RecruitmentInterview imported = interview(RecruitmentInterviewKind.ROUND, 1,
                NOW.minusDays(30), RecruitmentInterviewStatus.HELD, List.of());

        assertTrue(roundsWhere(imported, Map.of(), RecruitmentStage.INTERVIEW_1, false).isEmpty(),
                "Airtable imports carry interviewer_uuids = [] and allAssignedSubmitted "
                        + "answers false for them; without the guard such a round would "
                        + "mute its candidate forever");
        assertTrue(roundsWhere(imported, Map.of(), RecruitmentStage.INTERVIEW_1, true).isEmpty());
    }

    @Test
    void aRoundTheApplicationHasMovedPastIsOver() {
        RecruitmentInterview roundOne = interview(RecruitmentInterviewKind.ROUND, 1,
                NOW.minusDays(20), RecruitmentInterviewStatus.HELD, List.of(ANNA, BO));

        assertTrue(roundsWhere(roundOne, cards(roundOne, ANNA), RecruitmentStage.INTERVIEW_2, false)
                        .isEmpty(),
                "the application sits in INTERVIEW_2: round one's missing card no longer "
                        + "blocks anyone (RecruitmentInterviewService.decisionMade)");
    }

    @Test
    void aRoundWithARecordedDecisionIsOver() {
        RecruitmentInterview round = interview(RecruitmentInterviewKind.ROUND, 2,
                NOW.minusDays(3), RecruitmentInterviewStatus.HELD, List.of(ANNA, BO));
        round.setDecidedAt(NOW.minusDays(1));

        assertTrue(roundsWhere(round, cards(round, ANNA, BO), RecruitmentStage.INTERVIEW_2, true)
                        .isEmpty(),
                "V519: recording the decision IS the decision — the stage move only "
                        + "completes it, so the row must not survive the recording");
    }

    @Test
    void aMeetingStillRunningIsNotYetHeld() {
        RecruitmentInterview inProgress = interview(RecruitmentInterviewKind.ROUND, 1,
                NOW.minusMinutes(30), RecruitmentInterviewStatus.SCHEDULED, List.of(ANNA, BO));
        inProgress.setDurationMinutes(60);

        assertTrue(roundsWhere(inProgress, Map.of(), RecruitmentStage.INTERVIEW_1, false).isEmpty(),
                "scheduledAt + duration, not scheduledAt: nobody owes a scorecard while "
                        + "still in the room with the candidate");

        inProgress.setDurationMinutes(15);
        assertFalse(roundsWhere(inProgress, Map.of(), RecruitmentStage.INTERVIEW_1, false).isEmpty(),
                "once the booked time is over, the card is genuinely outstanding");
    }

    @Test
    void anInformalChatNeverCountsAsARound() {
        RecruitmentInterview chat = interview(RecruitmentInterviewKind.INFORMAL, null,
                NOW.minusDays(5), RecruitmentInterviewStatus.HELD, List.of(ANNA));

        assertTrue(roundsWhere(chat, Map.of(), RecruitmentStage.SCREENING, false).isEmpty(),
                "an informal chat takes no scorecard (spec §5.3), so it can never be "
                        + "'waiting on a scorecard'");
    }

    // ---- fixtures ---------------------------------------------------------

    private static Set<String> roundsWhere(RecruitmentInterview interview,
                                           Map<String, List<RecruitmentScorecard>> cards,
                                           RecruitmentStage applicationStage,
                                           boolean allSubmitted) {
        return RecruitmentIdleFacts.roundsWhere(List.of(interview), cards,
                Map.of(APP, application(applicationStage)), NOW, allSubmitted);
    }

    private static RecruitmentApplication application(RecruitmentStage stage) {
        RecruitmentApplication application = new RecruitmentApplication();
        application.setUuid(APP);
        application.setCandidateUuid("candidate-1");
        application.setPositionUuid("position-1");
        application.setStage(stage);
        application.setStageEnteredAt(NOW.minusDays(11));
        return application;
    }

    private static RecruitmentInterview interview(RecruitmentInterviewKind kind, Integer round,
                                                  LocalDateTime scheduledAt,
                                                  RecruitmentInterviewStatus status,
                                                  List<String> interviewers) {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid("interview-" + kind + "-" + round + "-" + scheduledAt);
        interview.setApplicationUuid(APP);
        interview.setKind(kind);
        interview.setRound(round);
        interview.setScheduledAt(scheduledAt);
        interview.setDurationMinutes(60);
        interview.setStatus(status);
        interview.setInterviewerUuids(interviewers);
        return interview;
    }

    private static Map<String, List<RecruitmentScorecard>> cards(RecruitmentInterview interview,
                                                                 String... interviewerUuids) {
        return java.util.Arrays.stream(interviewerUuids)
                .map(uuid -> {
                    RecruitmentScorecard card = new RecruitmentScorecard();
                    card.setUuid("card-" + uuid);
                    card.setInterviewUuid(interview.getUuid());
                    card.setInterviewerUuid(uuid);
                    card.setSubmittedAt(NOW.minusDays(1));
                    return card;
                })
                .collect(Collectors.groupingBy(RecruitmentScorecard::getInterviewUuid));
    }
}
