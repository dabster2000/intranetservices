package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlaService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P17 DoD — the SLA sweep against the real local DB with a mocked Slack
 * transport: table-driven trigger/threshold cases including the max-nudge
 * cap, "no nudge after submission", re-nudge spacing, the owner ladder,
 * DM-to-user routing (never a channel), flag gating and the no-Slack-link
 * / transport-failure skips.
 * <p>
 * The sweep scans whole tables, and the shared local DB may carry rows
 * from other suites — every assertion is therefore scoped to this class's
 * uuid-unique fixture names and candidate uuids, never to global call
 * counts.
 */
@QuarkusTest
class RecruitmentSlaServiceTest {

    private static final String INTERVIEWS_FLAG = "recruitment.interviews.enabled";
    private static final String SCORECARD_HOURS_KEY = "recruitment.sla.scorecard-overdue-hours";
    private static final String IDLE_DAYS_KEY = "recruitment.sla.candidate-idle-days";
    private static final String DEBRIEF_HOURS_KEY = "recruitment.sla.debrief-stalled-hours";

    @Inject
    EntityManager em;

    @Inject
    RecruitmentSlaService slaService;

    @InjectMock
    SlackService slackService;

    private String marker;
    private String practiceUuid;
    private String interviewerUser;
    private String ownerUser;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String interviewUuid;

    private String previousFlag;
    private String previousScorecardHours;
    private String previousIdleDays;
    private String previousDebriefHours;

    @BeforeEach
    void seed() {
        marker = "SlaT" + UUID.randomUUID().toString().substring(0, 8);
        practiceUuid = UUID.randomUUID().toString();
        interviewerUser = UUID.randomUUID().toString();
        ownerUser = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        interviewUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, interviewerUser, marker, "Interviewer");
            P8ProfileFixtures.insertUser(em, ownerUser, marker, "Owner");
            P12NotificationFixtures.setUserSlackLink(em, interviewerUser, "U" + marker + "I");
            P12NotificationFixtures.setUserSlackLink(em, ownerUser, "U" + marker + "O");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            // Hiring owner set → the owner ladder's first rung.
            P8ProfileFixtures.insertPosition(em, positionUuid, marker + " Position",
                    "PRACTICE_TEAM", practiceUuid, null, ownerUser);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    marker, "Kandidat", "ACTIVE", null, null, ownerUser);
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "INTERVIEW_1");
            previousFlag = P8ProfileFixtures.setFlag(em, INTERVIEWS_FLAG, "true");
            previousScorecardHours = P8ProfileFixtures.setFlag(em, SCORECARD_HOURS_KEY, "24");
            previousIdleDays = P8ProfileFixtures.setFlag(em, IDLE_DAYS_KEY, "7");
            previousDebriefHours = P8ProfileFixtures.setFlag(em, DEBRIEF_HOURS_KEY, "48");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(interviewerUser, ownerUser), practiceUuid);
            P8ProfileFixtures.restoreFlag(em, INTERVIEWS_FLAG, previousFlag);
            P8ProfileFixtures.restoreFlag(em, SCORECARD_HOURS_KEY, previousScorecardHours);
            P8ProfileFixtures.restoreFlag(em, IDLE_DAYS_KEY, previousIdleDays);
            P8ProfileFixtures.restoreFlag(em, DEBRIEF_HOURS_KEY, previousDebriefHours);
        });
    }

    // ---- Scoped assertion helpers ------------------------------------------

    /** All DM texts sent to the given user (scoped — the sweep scans whole tables). */
    private List<String> dmTextsTo(String userUuid) {
        ArgumentCaptor<User> users = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        try {
            verify(slackService, atLeast(0)).sendMessage(users.capture(), texts.capture());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        List<User> allUsers = users.getAllValues();
        List<String> allTexts = texts.getAllValues();
        return java.util.stream.IntStream.range(0, allUsers.size())
                .filter(i -> userUuid.equals(allUsers.get(i).getUuid()))
                .mapToObj(allTexts::get)
                .toList();
    }

    private List<RecruitmentEvent> eventsOf(RecruitmentEventType type) {
        return QuarkusTransaction.requiringNew().call(() ->
                RecruitmentEvent.list("candidateUuid = ?1 and eventType = ?2 order by seq",
                        candidateUuid, type));
    }

    private void backdateInterviewHours(String uuid, int hoursAgo) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_interviews SET scheduled_at = "
                                + "DATE_SUB(UTC_TIMESTAMP(3), INTERVAL :h HOUR) WHERE uuid = :uuid")
                        .setParameter("h", hoursAgo)
                        .setParameter("uuid", uuid)
                        .executeUpdate());
    }

    private void insertOverdueInterview(int hoursAgo) {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertInterviewHoursAgo(em, interviewUuid, applicationUuid,
                        "ROUND", 1, "[\"" + interviewerUser + "\"]", "SCHEDULED", hoursAgo));
    }

    private long seedNudgeEvent(RecruitmentEventType type, String payloadJson, int hoursAgo) {
        return QuarkusTransaction.requiringNew().call(() -> {
            long seq = P8ProfileFixtures.insertEvent(em, type.name(), candidateUuid,
                    applicationUuid, positionUuid, "SCHEDULER", null, "NORMAL",
                    payloadJson, null);
            P8ProfileFixtures.backdateEvent(em, seq, hoursAgo);
            return seq;
        });
    }

    private String scorecardNudgePayload() {
        return "{\"interview_uuid\":\"" + interviewUuid + "\",\"nudged_user_uuid\":\""
                + interviewerUser + "\"}";
    }

    // ---- Flag gating ---------------------------------------------------------

    @Test
    void interviewsFlagOff_sweepIsNoOp() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, INTERVIEWS_FLAG, "false"));
        insertOverdueInterview(48);

        RecruitmentSlaService.SweepSummary summary = slaService.sweep();

        assertFalse(summary.enabled());
        assertTrue(dmTextsTo(interviewerUser).isEmpty());
        assertTrue(eventsOf(RecruitmentEventType.SCORECARD_NUDGED).isEmpty());
    }

    // ---- Trigger 1: scorecard overdue -----------------------------------------

    @Test
    void scorecardOverdue_dmsTheInterviewer_appendsStructuralEvent() {
        insertOverdueInterview(30); // threshold 24h

        slaService.sweep();

        List<String> dms = dmTextsTo(interviewerUser);
        assertEquals(1, dms.size(), "exactly one DM to the assigned interviewer");
        assertTrue(dms.get(0).contains(marker), "DM names the candidate");
        assertTrue(dms.get(0).contains("/recruitment/interviews"),
                "deep-link-only while the P18 scorecard toggle is off (degradation chain)");
        // Routed as a DM to the user — never to a channel (the String
        // overloads are the channel paths; the sweep must not touch them).
        verify(slackService, never()).sendMessage(anyString(), anyString());
        verify(slackService, never()).sendMessage(anyString(), anyString(), anyString());

        List<RecruitmentEvent> events = eventsOf(RecruitmentEventType.SCORECARD_NUDGED);
        assertEquals(1, events.size());
        RecruitmentEvent event = events.get(0);
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
        assertTrue(event.getPayload().contains("\"interview_uuid\":\"" + interviewUuid + "\""));
        assertTrue(event.getPayload().contains("\"nudged_user_uuid\":\"" + interviewerUser + "\""));
        assertTrue(event.getPayload().contains("\"nudge_number\":1"));
        assertEquals("SCHEDULER", event.getActorType().name());
    }

    @Test
    @SuppressWarnings("unchecked")
    void scorecardNudge_p18ToggleOn_dmCarriesTheScorecardButton() {
        String previousScorecard = QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.setFlag(em, "recruitment.slack.scorecard.enabled", "true"));
        try {
            insertOverdueInterview(30);

            slaService.sweep();

            // The Block Kit DM overload carries the button; the plain-text
            // overload stays untouched for this nudge.
            ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<List<com.slack.api.model.block.LayoutBlock>> blocks =
                    ArgumentCaptor.forClass((Class) List.class);
            try {
                verify(slackService).sendMessage(user.capture(), text.capture(), blocks.capture());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertEquals(interviewerUser, user.getValue().getUuid());
            assertTrue(text.getValue().contains("Scorecard overdue"),
                    "fallback text stays the full nudge message");
            String rendered = blocks.getValue().toString();
            assertTrue(rendered.contains("recruitment_scorecard_open"),
                    "the button's action id: " + rendered);
            assertTrue(rendered.contains(interviewUuid),
                    "the button carries the interview uuid");
            // Bookkeeping unchanged — same event, same cap accounting.
            assertEquals(1, eventsOf(RecruitmentEventType.SCORECARD_NUDGED).size());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> P8ProfileFixtures.restoreFlag(em,
                    "recruitment.slack.scorecard.enabled", previousScorecard));
        }
    }

    @Test
    void scorecardNudge_secondSweepWithinThreshold_noSecondDm() {
        insertOverdueInterview(30);

        slaService.sweep();
        slaService.sweep(); // same day — spacing suppresses

        assertEquals(1, dmTextsTo(interviewerUser).size());
        assertEquals(1, eventsOf(RecruitmentEventType.SCORECARD_NUDGED).size());
    }

    @Test
    void scorecardNudge_afterSpacing_secondAndFinalDm() {
        insertOverdueInterview(80);
        seedNudgeEvent(RecruitmentEventType.SCORECARD_NUDGED, scorecardNudgePayload(), 30);

        slaService.sweep();

        List<String> dms = dmTextsTo(interviewerUser);
        assertEquals(1, dms.size(), "one new DM (nudge 2)");
        assertTrue(dms.get(0).contains("final reminder"));
        List<RecruitmentEvent> events = eventsOf(RecruitmentEventType.SCORECARD_NUDGED);
        assertEquals(2, events.size());
        assertTrue(events.get(1).getPayload().contains("\"nudge_number\":2"));
    }

    @Test
    void scorecardNudge_capOfTwo_neverAThird() {
        insertOverdueInterview(200);
        seedNudgeEvent(RecruitmentEventType.SCORECARD_NUDGED, scorecardNudgePayload(), 120);
        seedNudgeEvent(RecruitmentEventType.SCORECARD_NUDGED, scorecardNudgePayload(), 60);

        slaService.sweep();

        assertTrue(dmTextsTo(interviewerUser).isEmpty(), "max 2 nudges — no third DM");
        assertEquals(2, eventsOf(RecruitmentEventType.SCORECARD_NUDGED).size());
    }

    @Test
    void submittedScorecard_neverNudged() {
        insertOverdueInterview(30);
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertScorecard(em, UUID.randomUUID().toString(),
                        interviewUuid, interviewerUser, "YES"));

        slaService.sweep();

        assertTrue(dmTextsTo(interviewerUser).isEmpty(), "no nudge after submission");
        assertTrue(eventsOf(RecruitmentEventType.SCORECARD_NUDGED).isEmpty());
    }

    @Test
    void notYetOverdue_noNudge() {
        insertOverdueInterview(2); // threshold 24h

        slaService.sweep();

        assertTrue(dmTextsTo(interviewerUser).isEmpty());
        assertTrue(eventsOf(RecruitmentEventType.SCORECARD_NUDGED).isEmpty());
    }

    @Test
    void cancelledInterview_orClosedApplication_noNudge() {
        insertOverdueInterview(30);
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE recruitment_interviews SET status = 'CANCELLED' "
                            + "WHERE uuid = :uuid")
                    .setParameter("uuid", interviewUuid).executeUpdate();
        });
        slaService.sweep();
        assertTrue(eventsOf(RecruitmentEventType.SCORECARD_NUDGED).isEmpty());

        // Re-activate the interview but close the application: still nothing.
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE recruitment_interviews SET status = 'SCHEDULED' "
                            + "WHERE uuid = :uuid")
                    .setParameter("uuid", interviewUuid).executeUpdate();
            P12NotificationFixtures.setApplicationTerminal(em, applicationUuid, "REJECTED");
        });
        slaService.sweep();
        assertTrue(eventsOf(RecruitmentEventType.SCORECARD_NUDGED).isEmpty());
        assertTrue(dmTextsTo(interviewerUser).isEmpty());
    }

    @Test
    void thresholdFromSettings_drivesTheTrigger() {
        insertOverdueInterview(30);
        // Raise the threshold above the interview's age: no nudge.
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, SCORECARD_HOURS_KEY, "100"));
        slaService.sweep();
        assertTrue(eventsOf(RecruitmentEventType.SCORECARD_NUDGED).isEmpty());

        // Lower it below the age: nudge fires on the next sweep.
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, SCORECARD_HOURS_KEY, "1"));
        slaService.sweep();
        assertEquals(1, eventsOf(RecruitmentEventType.SCORECARD_NUDGED).size());
    }

    // ---- Trigger 2: debrief stalled --------------------------------------------

    private void seedStalledDebrief(int scorecardHoursAgo) {
        String scorecardUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertInterviewHoursAgo(em, interviewUuid, applicationUuid,
                    "ROUND", 1, "[\"" + interviewerUser + "\"]", "HELD", scorecardHoursAgo + 2);
            P8ProfileFixtures.insertScorecard(em, scorecardUuid, interviewUuid,
                    interviewerUser, "YES");
            P8ProfileFixtures.backdateScorecard(em, scorecardUuid, scorecardHoursAgo);
        });
    }

    @Test
    void debriefStalled_dmsTheHiringOwner_appendsEvent() {
        seedStalledDebrief(60); // threshold 48h

        slaService.sweep();

        List<String> dms = dmTextsTo(ownerUser);
        assertEquals(1, dms.size(), "the hiring owner gets the debrief ping");
        assertTrue(dms.get(0).contains(marker));
        List<RecruitmentEvent> events = eventsOf(RecruitmentEventType.DEBRIEF_STALLED_NUDGED);
        assertEquals(1, events.size());
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(events.get(0));
        assertTrue(events.get(0).getPayload()
                .contains("\"nudged_user_uuids\":[\"" + ownerUser + "\"]"));

        // Second sweep inside the threshold window: no re-ping.
        slaService.sweep();
        assertEquals(1, dmTextsTo(ownerUser).size());
        assertEquals(1, eventsOf(RecruitmentEventType.DEBRIEF_STALLED_NUDGED).size());
    }

    @Test
    void debriefFresh_orDecisionMade_noPing() {
        seedStalledDebrief(10); // under the 48h threshold
        slaService.sweep();
        assertTrue(eventsOf(RecruitmentEventType.DEBRIEF_STALLED_NUDGED).isEmpty());

        // Backdate past the threshold but move the application past the
        // round — the decision was made, nothing to chase.
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE recruitment_scorecards SET submitted_at = "
                            + "DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 60 HOUR) "
                            + "WHERE interview_uuid = :uuid")
                    .setParameter("uuid", interviewUuid).executeUpdate();
            P12NotificationFixtures.setApplicationStage(em, applicationUuid, "OFFER");
        });
        slaService.sweep();
        assertTrue(eventsOf(RecruitmentEventType.DEBRIEF_STALLED_NUDGED).isEmpty());
        assertTrue(dmTextsTo(ownerUser).isEmpty());
    }

    // ---- Trigger 3: candidate idle ----------------------------------------------

    @Test
    void idleApplication_dmsTheOwner_appendsEvent_reNudgeSuppressed() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.backdateApplicationStageEntry(em, applicationUuid, 10));

        slaService.sweep();

        List<String> dms = dmTextsTo(ownerUser);
        assertEquals(1, dms.size());
        assertTrue(dms.get(0).contains(marker));
        assertTrue(dms.get(0).contains("/recruitment/pipeline?position=" + positionUuid));
        List<RecruitmentEvent> events = eventsOf(RecruitmentEventType.CANDIDATE_IDLE_NUDGED);
        assertEquals(1, events.size());
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(events.get(0));
        assertTrue(events.get(0).getPayload().contains("\"days_idle\":10"));

        // Same idle episode, second sweep: suppressed.
        slaService.sweep();
        assertEquals(1, dmTextsTo(ownerUser).size());
        assertEquals(1, eventsOf(RecruitmentEventType.CANDIDATE_IDLE_NUDGED).size());
    }

    @Test
    void freshApplication_noIdlePing() {
        slaService.sweep(); // stage_entered_at = now

        assertTrue(eventsOf(RecruitmentEventType.CANDIDATE_IDLE_NUDGED).isEmpty());
        assertTrue(dmTextsTo(ownerUser).isEmpty());
    }

    // ---- Owner ladder --------------------------------------------------------------

    @Test
    void ownerLadder_fallsBackToCurrentTeamLead() {
        String teamUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE recruitment_positions SET hiring_owner_uuid = NULL, "
                            + "team_uuid = :team WHERE uuid = :uuid")
                    .setParameter("team", teamUuid)
                    .setParameter("uuid", positionUuid).executeUpdate();
            P8ProfileFixtures.insertTeamLeader(em, ownerUser, teamUuid);
            P8ProfileFixtures.backdateApplicationStageEntry(em, applicationUuid, 10);
        });

        slaService.sweep();

        assertEquals(1, dmTextsTo(ownerUser).size(), "the current team lead gets the ping");
        assertEquals(1, eventsOf(RecruitmentEventType.CANDIDATE_IDLE_NUDGED).size());
    }

    @Test
    void ownerLadder_partnerTrack_pingsCircleOwners_withCircleVisibility() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE recruitment_positions SET hiring_owner_uuid = NULL, "
                            + "hiring_track = 'PARTNER' WHERE uuid = :uuid")
                    .setParameter("uuid", positionUuid).executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO recruitment_circle_members
                                (position_uuid, user_uuid, role_in_circle, added_at, added_by_uuid)
                            VALUES (:p, :u, 'OWNER', NOW(3), :u)
                            """)
                    .setParameter("p", positionUuid)
                    .setParameter("u", ownerUser).executeUpdate();
            P8ProfileFixtures.backdateApplicationStageEntry(em, applicationUuid, 10);
        });

        slaService.sweep();

        assertEquals(1, dmTextsTo(ownerUser).size(), "the circle OWNER gets the ping");
        List<RecruitmentEvent> events = eventsOf(RecruitmentEventType.CANDIDATE_IDLE_NUDGED);
        assertEquals(1, events.size());
        assertEquals("CIRCLE", events.get(0).getVisibility().name());
    }

    @Test
    void ownerLadder_nobodyResolvable_visibleSkip_noEvent() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE recruitment_positions SET hiring_owner_uuid = NULL "
                            + "WHERE uuid = :uuid")
                    .setParameter("uuid", positionUuid).executeUpdate();
            P8ProfileFixtures.backdateApplicationStageEntry(em, applicationUuid, 10);
        });

        slaService.sweep();

        assertTrue(dmTextsTo(ownerUser).isEmpty());
        assertTrue(eventsOf(RecruitmentEventType.CANDIDATE_IDLE_NUDGED).isEmpty());
    }

    // ---- Degradation ----------------------------------------------------------------

    @Test
    void missingSlackLink_visibleSkip_noEvent() {
        QuarkusTransaction.requiringNew().run(() ->
                P12NotificationFixtures.setUserSlackLink(em, interviewerUser, ""));
        insertOverdueInterview(30);

        slaService.sweep();

        assertTrue(dmTextsTo(interviewerUser).isEmpty());
        assertTrue(eventsOf(RecruitmentEventType.SCORECARD_NUDGED).isEmpty(),
                "no bookkeeping without a DM — a later Slack link picks up naturally");
    }

    @Test
    void slackTransportFailure_noEvent_nextSweepRetries() throws Exception {
        insertOverdueInterview(30);
        doThrow(new java.io.IOException("slack down"))
                .when(slackService).sendMessage(any(User.class), anyString());

        RecruitmentSlaService.SweepSummary summary = slaService.sweep();

        assertTrue(summary.failures() >= 1);
        assertTrue(eventsOf(RecruitmentEventType.SCORECARD_NUDGED).isEmpty(),
                "the event rolls back with the failed DM — the next sweep retries");
    }
}
