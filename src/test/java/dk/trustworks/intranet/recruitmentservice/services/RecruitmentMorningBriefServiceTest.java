package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P23 §DoD — the morning interviewer briefs against the local DB with a
 * mocked Slack transport: one DM per interviewer covering exactly today's
 * SCHEDULED interviews (Europe/Copenhagen day; cancelled and future rounds
 * excluded), event-derived idempotency per (interviewer, interview, date)
 * — a re-run briefs nobody twice and a Slack failure rolls its
 * bookkeeping back so the next run retries. Structural payloads only
 * (sentinel-checked); the scorecard button rides the P18 toggle on round
 * rows; the master gate is irrelevant (outbound feature).
 */
@QuarkusTest
class RecruitmentMorningBriefServiceTest {

    private static final String PIPELINE_FLAG = P8ProfileFixtures.PIPELINE_FLAG;
    private static final String BRIEF_FLAG = "recruitment.slack.morning-brief.enabled";
    private static final String SCORECARD_FLAG = "recruitment.slack.scorecard.enabled";
    private static final String MASTER_FLAG = "recruitment.slack.interactivity.enabled";

    @Inject
    RecruitmentMorningBriefService service;

    @Inject
    EntityManager em;

    @InjectMock
    SlackService slackService;

    private String interviewerOneUuid;
    private String interviewerTwoUuid;
    private String practiceUuid;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String morningRoundUuid;
    private String afternoonInformalUuid;
    private String tomorrowRoundUuid;
    private String cancelledTodayUuid;
    private String marker;

    private final Map<String, String> previousFlags = new HashMap<>();

    @BeforeEach
    void seed() {
        marker = UUID.randomUUID().toString().substring(0, 8);
        interviewerOneUuid = UUID.randomUUID().toString();
        interviewerTwoUuid = UUID.randomUUID().toString();
        practiceUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        morningRoundUuid = UUID.randomUUID().toString();
        afternoonInformalUuid = UUID.randomUUID().toString();
        tomorrowRoundUuid = UUID.randomUUID().toString();
        cancelledTodayUuid = UUID.randomUUID().toString();

        LocalDate today = LocalDate.now(RecruitmentMorningBriefService.COPENHAGEN);

        QuarkusTransaction.requiringNew().run(() -> {
            previousFlags.put(PIPELINE_FLAG, P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true"));
            previousFlags.put(BRIEF_FLAG, P8ProfileFixtures.setFlag(em, BRIEF_FLAG, "true"));
            previousFlags.put(SCORECARD_FLAG, P8ProfileFixtures.setFlag(em, SCORECARD_FLAG, "false"));
            // The brief is OUTBOUND — it must send with the master gate OFF.
            previousFlags.put(MASTER_FLAG, P8ProfileFixtures.setFlag(em, MASTER_FLAG, "false"));

            P8ProfileFixtures.insertUser(em, interviewerOneUuid, "Ivan", "One");
            P8ProfileFixtures.insertUser(em, interviewerTwoUuid, "Tina", "Two");
            linkSlack(interviewerOneUuid, "U-P23-ONE");
            linkSlack(interviewerTwoUuid, "U-P23-TWO");

            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Senior Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            // The candidate's name carries the PII sentinel: the DM MAY name
            // them (moderate rule), the event payload must not.
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    RecruitmentEventPiiAssertions.PII_SENTINEL + "-Cand" + marker, "Hansen",
                    "ACTIVE", null, null, "test");
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "INTERVIEW_1");

            // Today 09:00: round with both interviewers. Today 15:30:
            // informal with interviewer one. Tomorrow: round (no brief
            // today). Today but cancelled: never briefed.
            P8ProfileFixtures.insertInterviewAt(em, morningRoundUuid, applicationUuid,
                    "ROUND", 1, interviewers(interviewerOneUuid, interviewerTwoUuid),
                    "SCHEDULED", today.atTime(9, 0));
            P8ProfileFixtures.insertInterviewAt(em, afternoonInformalUuid, applicationUuid,
                    "INFORMAL", null, interviewers(interviewerOneUuid),
                    "SCHEDULED", today.atTime(15, 30));
            P8ProfileFixtures.insertInterviewAt(em, tomorrowRoundUuid, applicationUuid,
                    "ROUND", 2, interviewers(interviewerOneUuid),
                    "SCHEDULED", today.plusDays(1).atTime(10, 0));
            P8ProfileFixtures.insertInterviewAt(em, cancelledTodayUuid, applicationUuid,
                    "ROUND", 1, interviewers(interviewerTwoUuid),
                    "CANCELLED", today.atTime(11, 0));
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            previousFlags.forEach((flag, previous) ->
                    P8ProfileFixtures.restoreFlag(em, flag, previous));
            previousFlags.clear();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(interviewerOneUuid, interviewerTwoUuid), practiceUuid);
        });
    }

    // =========================================================================
    // The brief itself
    // =========================================================================

    @Test
    void run_oneDmPerInterviewer_coveringOnlyTodaysScheduledInterviews() throws Exception {
        RecruitmentMorningBriefService.BriefSummary summary = service.run();

        assertTrue(summary.enabled());
        assertEquals(2, summary.briefsSent(), "one DM per interviewer with interviews today");
        assertEquals(3, summary.interviewsCovered(),
                "interviewer one covers 2 (round + informal), interviewer two covers 1");
        assertEquals(0, summary.failures());

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<String> fallback = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(2)).sendMessage(user.capture(), fallback.capture(), anyList());

        String one = fallbackFor(user.getAllValues(), fallback.getAllValues(), interviewerOneUuid);
        assertTrue(one.contains("Your interviews today"), "the brief header");
        assertTrue(one.contains("09:00"), "the round's time");
        assertTrue(one.contains("15:30"), "the informal chat's time");
        assertTrue(one.contains("informal chat"), "informal is labeled, not numbered");
        assertTrue(one.contains("round 1"), "the round is numbered");
        assertFalse(one.contains("round 2"), "tomorrow's round is NOT in today's brief");
        assertTrue(one.contains(RecruitmentEventPiiAssertions.PII_SENTINEL + "-Cand" + marker),
                "the DM names the candidate (moderate rule — a DM to the assigned interviewer)");
        assertTrue(one.contains("Why consulting"), "focus areas from the position template");
        assertTrue(one.contains("/recruitment/interviews"), "the kit deep link");

        String two = fallbackFor(user.getAllValues(), fallback.getAllValues(), interviewerTwoUuid);
        assertTrue(two.contains("09:00"));
        assertFalse(two.contains("15:30"), "interviewer two is not on the informal chat");

        // Bookkeeping: one MORNING_BRIEF_SENT per (interviewer, interview),
        // structural payload only (sentinel-checked).
        List<RecruitmentEvent> events = briefEvents();
        assertEquals(3, events.size());
        for (RecruitmentEvent event : events) {
            RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
            assertEquals(RecruitmentEventVisibility.NORMAL, event.getVisibility());
        }
    }

    @Test
    void rerun_isIdempotent_perInterviewerInterviewAndDate() throws Exception {
        service.run();
        reset(slackService);

        RecruitmentMorningBriefService.BriefSummary second = service.run();

        assertEquals(0, second.briefsSent(), "every pair already briefed today");
        verify(slackService, never()).sendMessage(any(User.class), anyString(), anyList());
        assertEquals(3, briefEvents().size(), "no duplicate bookkeeping");
    }

    @Test
    void scorecardToggleOn_roundRowsCarryTheButton_informalNever() throws Exception {
        setFlag(SCORECARD_FLAG, "true");

        service.run();

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<com.slack.api.model.block.LayoutBlock>> blocks =
                ArgumentCaptor.forClass((Class) List.class);
        verify(slackService, times(2)).sendMessage(user.capture(), anyString(), blocks.capture());
        for (int i = 0; i < user.getAllValues().size(); i++) {
            String rendered = blocks.getAllValues().get(i).toString();
            assertTrue(rendered.contains("recruitment_scorecard_open"),
                    "both interviewers sit on today's round — the button rides the toggle");
            assertTrue(rendered.contains(morningRoundUuid), "the button's value is the round uuid");
            assertFalse(rendered.contains(afternoonInformalUuid),
                    "an informal chat never carries a scorecard button");
        }
    }

    // =========================================================================
    // Gates, skips and retries
    // =========================================================================

    @Test
    void flagOff_isANoOp() throws Exception {
        setFlag(BRIEF_FLAG, "false");

        RecruitmentMorningBriefService.BriefSummary summary = service.run();

        assertFalse(summary.enabled());
        verify(slackService, never()).sendMessage(any(User.class), anyString(), anyList());
        assertEquals(0, briefEvents().size());
    }

    @Test
    void pipelineOff_isANoOp() throws Exception {
        setFlag(PIPELINE_FLAG, "false");

        RecruitmentMorningBriefService.BriefSummary summary = service.run();

        assertFalse(summary.enabled());
        verify(slackService, never()).sendMessage(any(User.class), anyString(), anyList());
    }

    @Test
    void terminalApplication_noBrief() throws Exception {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_applications "
                                + "SET terminal = 'REJECTED' WHERE uuid = :a")
                        .setParameter("a", applicationUuid).executeUpdate());

        RecruitmentMorningBriefService.BriefSummary summary = service.run();

        assertEquals(0, summary.briefsSent(),
                "a decided application briefs nobody about a candidate who is out");
    }

    @Test
    void slackFailure_rollsBackBookkeeping_nextRunRetries() throws Exception {
        doThrow(new IOException("Slack down"))
                .when(slackService).sendMessage(any(User.class), anyString(), anyList());

        RecruitmentMorningBriefService.BriefSummary first = service.run();

        assertEquals(0, first.briefsSent());
        assertEquals(2, first.failures());
        assertEquals(0, briefEvents().size(), "the failed DMs left no bookkeeping");

        reset(slackService);
        RecruitmentMorningBriefService.BriefSummary second = service.run();

        assertEquals(2, second.briefsSent(), "the next run retries exactly the missed pairs");
        assertEquals(3, briefEvents().size());
    }

    @Test
    void unlinkedInterviewer_visibleSkipWithNoEvent_briefedAfterLinking() throws Exception {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE user SET slackusername = NULL WHERE uuid = :u")
                        .setParameter("u", interviewerTwoUuid).executeUpdate());

        RecruitmentMorningBriefService.BriefSummary first = service.run();
        assertEquals(1, first.briefsSent(), "only the linked interviewer briefs");
        assertEquals(2, briefEvents().size(), "no event for the unlinked interviewer's pair");

        QuarkusTransaction.requiringNew().run(() -> linkSlack(interviewerTwoUuid, "U-P23-TWO"));
        RecruitmentMorningBriefService.BriefSummary second = service.run();
        assertEquals(1, second.briefsSent(), "a later Slack link picks up naturally");
        assertEquals(3, briefEvents().size());
    }

    @Test
    void partnerPosition_briefEventsCarryCircleVisibility() throws Exception {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_positions "
                                + "SET hiring_track = 'PARTNER' WHERE uuid = :p")
                        .setParameter("p", positionUuid).executeUpdate());

        service.run();

        List<RecruitmentEvent> events = briefEvents();
        assertFalse(events.isEmpty());
        for (RecruitmentEvent event : events) {
            assertEquals(RecruitmentEventVisibility.CIRCLE, event.getVisibility(),
                    "partner-track brief bookkeeping is circle-scoped like every "
                            + "other partner event");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String interviewers(String... uuids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < uuids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(uuids[i]).append('"');
        }
        return sb.append(']').toString();
    }

    private void linkSlack(String userUuid, String slackId) {
        em.createNativeQuery("UPDATE user SET slackusername = :s WHERE uuid = :u")
                .setParameter("s", slackId).setParameter("u", userUuid).executeUpdate();
    }

    private void setFlag(String flag, String value) {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, flag, value));
    }

    private List<RecruitmentEvent> briefEvents() {
        return QuarkusTransaction.requiringNew().call(() ->
                RecruitmentEvent.list("applicationUuid = ?1 and eventType = ?2",
                        applicationUuid,
                        dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType.MORNING_BRIEF_SENT));
    }

    private static String fallbackFor(List<User> users, List<String> fallbacks, String userUuid) {
        for (int i = 0; i < users.size(); i++) {
            if (userUuid.equals(users.get(i).getUuid())) {
                return fallbacks.get(i);
            }
        }
        throw new AssertionError("no DM captured for user " + userUuid);
    }
}
