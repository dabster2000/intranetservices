package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
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

import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P22 §DoD: the living-card reactor against the real chassis
 * (raw-inserted committed events + deterministic {@code catchUp()}
 * sweeps) with a mocked {@link SlackService}. Covers the card lifecycle
 * (root card → chat.update + thread replies → terminal with reason code),
 * the mid-stream toggle semantics (card on next event only; off = flat
 * fallback via the P12 reactor, asserted in its own test class), circle
 * suppression with the private-channel/DM degradation, the
 * CANDIDATE_ANONYMIZED redaction hook (flag-independent), replay
 * idempotency (one root card ever; update-not-repost), the blind rule on
 * scorecard replies, and sentinel-PII containment.
 */
@QuarkusTest
class SlackCardReactorTest {

    private static final String PIPELINE_FLAG = P8ProfileFixtures.PIPELINE_FLAG;
    private static final String CARDS_FLAG = "recruitment.slack.cards.enabled";
    private static final String DEFAULT_KEY = RecruitmentSlackChannelRouter.DEFAULT_CHANNEL_KEY;
    private static final String ROOT_TS = "1700000000.000100";

    @Inject
    EntityManager em;

    @Inject
    SlackCardReactor reactor;

    @InjectMock
    SlackService slackService;

    private String practiceUuid;
    private String actorUser;
    private String circleMember;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;

    private String previousPipeline;
    private String previousCards;
    private String previousDefault;

    @BeforeEach
    void seed() throws Exception {
        practiceUuid = UUID.randomUUID().toString();
        actorUser = UUID.randomUUID().toString();
        circleMember = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, actorUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, circleMember, "Pia", "Partner");
            P12NotificationFixtures.setUserSlackLink(em, circleMember, "U-P22-CIRCLE");
            P12NotificationFixtures.setUserSlackLink(em, actorUser, "U-P22-ACTOR");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Senior Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "Anna", "Ager", "ACTIVE", null, null, actorUser);
            P12NotificationFixtures.setCandidateSource(em, candidateUuid, "WEBSITE");
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "SCREENING");
            previousPipeline = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "false");
            previousCards = P8ProfileFixtures.setFlag(em, CARDS_FLAG, "false");
            previousDefault = P8ProfileFixtures.setFlag(em, DEFAULT_KEY, "");
        });
        // Drain any backlog with the flags OFF so each test's sweep only
        // reflects its own trigger events.
        reactor.catchUp();
        when(slackService.sendMessageReturningTs(anyString(), anyString(), any()))
                .thenReturn(ROOT_TS);
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_slack_threads WHERE application_uuid = :a")
                    .setParameter("a", applicationUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_slack_channels WHERE position_uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(actorUser, circleMember), practiceUuid);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousPipeline);
            P8ProfileFixtures.restoreFlag(em, CARDS_FLAG, previousCards);
            P8ProfileFixtures.restoreFlag(em, DEFAULT_KEY, previousDefault);
        });
        // Advance past everything this test appended so the next test's
        // pre-sweep starts clean.
        reactor.catchUp();
    }

    // ---- helpers ---------------------------------------------------------------

    private void cardsOnWithChannel(String channel) {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
            P8ProfileFixtures.setFlag(em, CARDS_FLAG, "true");
            P8ProfileFixtures.setFlag(em, DEFAULT_KEY, channel);
        });
    }

    private long insertApplicationCreated(String visibility) {
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "APPLICATION_CREATED", candidateUuid,
                        applicationUuid, positionUuid, "USER", actorUser, visibility,
                        "{\"position_title\":\"Senior Consultant\",\"initial_stage\":\"SCREENING\","
                                + "\"origin\":\"manual\"}", null));
    }

    private long insertStageChanged(String from, String to, String direction) {
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "APPLICATION_STAGE_CHANGED", candidateUuid,
                        applicationUuid, positionUuid, "USER", actorUser, "NORMAL",
                        "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"direction\":\""
                                + direction + "\",\"skipped_stages\":false}", null));
    }

    private String threadRowTs() {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> rows = em.createNativeQuery(
                            "SELECT root_ts FROM recruitment_slack_threads WHERE application_uuid = :a")
                    .setParameter("a", applicationUuid).getResultList();
            return rows.isEmpty() ? null : rows.get(0).toString();
        });
    }

    private void insertThreadRow(String channel, String ts) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("INSERT INTO recruitment_slack_threads "
                                + "(application_uuid, channel_id, root_ts, updated_at) "
                                + "VALUES (:a, :c, :t, UTC_TIMESTAMP(3))")
                        .setParameter("a", applicationUuid)
                        .setParameter("c", channel)
                        .setParameter("t", ts)
                        .executeUpdate());
    }

    // ---- Flag gating + degradation ----------------------------------------------

    @Test
    void cardsFlagOff_noCardNoRow_offsetStillAdvances() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
            P8ProfileFixtures.setFlag(em, DEFAULT_KEY, "C-DEFAULT");
        });
        long seq = insertApplicationCreated("NORMAL");

        reactor.catchUp();

        verifyNoInteractions(slackService);
        assertNull(threadRowTs(), "no card row while the toggle is off");
        assertTrue(reactor.watermark() >= seq, "flag-off events must advance the offset (no backfill)");
    }

    @Test
    void pipelineOff_cardsOn_staysSilent() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, CARDS_FLAG, "true");
            P8ProfileFixtures.setFlag(em, DEFAULT_KEY, "C-DEFAULT");
        });
        insertApplicationCreated("NORMAL");

        reactor.catchUp();

        verifyNoInteractions(slackService);
    }

    // ---- Card lifecycle ----------------------------------------------------------

    @Test
    void applicationCreated_postsRootCard_recordsThread_noReply() throws Exception {
        cardsOnWithChannel("C-DEFAULT");
        insertApplicationCreated("NORMAL");

        reactor.catchUp();

        ArgumentCaptor<String> fallback = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(1))
                .sendMessageReturningTs(eq("C-DEFAULT"), fallback.capture(), any());
        assertTrue(fallback.getValue().contains("Anna Ager"), "card must name the candidate");
        assertEquals(ROOT_TS, threadRowTs(), "thread projection must record the root ts");
        verify(slackService, never()).sendThreadReply(anyString(), anyString(), anyString());
        verify(slackService, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void stageChange_updatesRootCard_andThreadsReply() throws Exception {
        cardsOnWithChannel("C-DEFAULT");
        insertThreadRow("C-DEFAULT", ROOT_TS);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_applications SET stage = 'INTERVIEW_1' "
                                + "WHERE uuid = :a")
                        .setParameter("a", applicationUuid).executeUpdate());
        insertStageChanged("SCREENING", "INTERVIEW_1", "FORWARD");

        reactor.catchUp();

        ArgumentCaptor<String> cardText = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(1)).updateMessageStrict(eq("C-DEFAULT"), eq(ROOT_TS),
                cardText.capture(), any());
        assertTrue(cardText.getValue().contains("Interview 1"),
                "chat.update must reflect the new stage");
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(1)).sendThreadReply(eq("C-DEFAULT"), eq(ROOT_TS), reply.capture());
        assertTrue(reply.getValue().contains("Screening → Interview 1"),
                "reply must carry the stage move");
        verify(slackService, never()).sendMessageReturningTs(anyString(), anyString(), any());
    }

    @Test
    void backMove_replySaysMovedBack() throws Exception {
        cardsOnWithChannel("C-DEFAULT");
        insertThreadRow("C-DEFAULT", ROOT_TS);
        insertStageChanged("INTERVIEW_2", "INTERVIEW_1", "BACK");

        reactor.catchUp();

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendThreadReply(eq("C-DEFAULT"), eq(ROOT_TS), reply.capture());
        assertTrue(reply.getValue().contains("Moved back"), "BACK moves must say so");
    }

    @Test
    void terminal_rejected_replyCarriesReasonCode_cardShowsOutcome() throws Exception {
        cardsOnWithChannel("C-DEFAULT");
        insertThreadRow("C-DEFAULT", ROOT_TS);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_applications SET terminal = 'REJECTED', "
                                + "rejection_reason_code = 'PROFILE_MISMATCH' WHERE uuid = :a")
                        .setParameter("a", applicationUuid).executeUpdate());
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, "APPLICATION_REJECTED", candidateUuid,
                        applicationUuid, positionUuid, "USER", actorUser, "NORMAL",
                        "{\"reason_code\":\"PROFILE_MISMATCH\",\"from_stage\":\"INTERVIEW_1\"}",
                        "{\"note\":\"" + PII_SENTINEL + " free text\"}"));

        reactor.catchUp();

        ArgumentCaptor<String> cardText = ArgumentCaptor.forClass(String.class);
        verify(slackService).updateMessageStrict(eq("C-DEFAULT"), eq(ROOT_TS),
                cardText.capture(), any());
        assertTrue(cardText.getValue().contains("Rejected"), "card must show the terminal outcome");
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendThreadReply(eq("C-DEFAULT"), eq(ROOT_TS), reply.capture());
        assertTrue(reply.getValue().contains("Profile mismatch"),
                "terminal reply must carry the humanized reason code");
        assertFalse(reply.getValue().contains(PII_SENTINEL), "the pii note never reaches Slack");
    }

    // ---- Mid-stream toggle -------------------------------------------------------

    @Test
    void midStreamEnable_existingApplicationGetsCardOnNextEventOnly() throws Exception {
        // Created while the toggle is off → nothing.
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
            P8ProfileFixtures.setFlag(em, DEFAULT_KEY, "C-DEFAULT");
        });
        insertApplicationCreated("NORMAL");
        reactor.catchUp();
        verifyNoInteractions(slackService);

        // Toggle on → the NEXT event births the card; no reply (the fresh
        // card already shows current state).
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, CARDS_FLAG, "true"));
        insertStageChanged("SCREENING", "INTERVIEW_1", "FORWARD");
        reactor.catchUp();

        verify(slackService, times(1)).sendMessageReturningTs(eq("C-DEFAULT"), anyString(), any());
        verify(slackService, never()).sendThreadReply(anyString(), anyString(), anyString());
        assertEquals(ROOT_TS, threadRowTs(), "card born mid-stream must be recorded");
    }

    // ---- Idempotency -------------------------------------------------------------

    @Test
    void redelivery_updatesExistingCard_neverReposts() throws Exception {
        cardsOnWithChannel("C-DEFAULT");
        // A thread row already exists (the card was posted by an earlier
        // delivery whose dedupe row has since been pruned) — the replayed
        // create must chat.update, not repost.
        insertThreadRow("C-DEFAULT", ROOT_TS);
        long seq = insertApplicationCreated("NORMAL");

        reactor.catchUp();
        reactor.deliverLive(seq); // replay through the second path too

        verify(slackService, never()).sendMessageReturningTs(anyString(), anyString(), any());
        verify(slackService, times(1)).updateMessageStrict(eq("C-DEFAULT"), eq(ROOT_TS),
                anyString(), any());
    }

    // ---- Circle (partner track) --------------------------------------------------

    @Test
    void circleEvent_withoutPrivateChannel_dmsCircleMembers_neverSharedChannel() throws Exception {
        cardsOnWithChannel("C-DEFAULT");
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE recruitment_positions SET hiring_track = 'PARTNER' "
                            + "WHERE uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
            P8ProfileFixtures.insertCircleMember(em, positionUuid, circleMember);
        });
        insertApplicationCreated("CIRCLE");

        reactor.catchUp();

        ArgumentCaptor<User> dmTarget = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<String> dm = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(1)).sendMessage(dmTarget.capture(), dm.capture());
        assertEquals(circleMember, dmTarget.getValue().getUuid());
        assertTrue(dm.getValue().contains("Confidential"), "the DM flags confidentiality");
        verify(slackService, never()).sendMessageReturningTs(anyString(), anyString(), any());
        assertNull(threadRowTs(), "no thread row without a private channel");
    }

    @Test
    void circleEvent_withPrivateChannel_postsCardThereOnly() throws Exception {
        cardsOnWithChannel("C-DEFAULT");
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("UPDATE recruitment_positions SET hiring_track = 'PARTNER' "
                            + "WHERE uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
            em.createNativeQuery("INSERT INTO recruitment_slack_channels "
                            + "(position_uuid, channel_id, archived_at) VALUES (:p, 'C-PRIVATE', NULL)")
                    .setParameter("p", positionUuid).executeUpdate();
        });
        insertApplicationCreated("CIRCLE");

        reactor.catchUp();

        verify(slackService, times(1)).sendMessageReturningTs(eq("C-PRIVATE"), anyString(), any());
        verify(slackService, never()).sendMessage(any(User.class), anyString());
        assertEquals(ROOT_TS, threadRowTs());
    }

    // ---- Redaction hook (P19 carry-over) ------------------------------------------

    @Test
    void anonymized_rewritesRootCard_evenWithFlagsOff() throws Exception {
        // Both flags OFF — erasure duties outlive convenience features.
        insertThreadRow("C-DEFAULT", ROOT_TS);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates SET status = 'ANONYMIZED', "
                                + "first_name = '', last_name = '' WHERE uuid = :c")
                        .setParameter("c", candidateUuid).executeUpdate());
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, "CANDIDATE_ANONYMIZED", candidateUuid,
                        null, null, "SYSTEM", null, "NORMAL",
                        "{\"mode\":\"RETENTION_DEADLINE\"}", null));

        reactor.catchUp();

        ArgumentCaptor<String> cardText = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(1)).updateMessageStrict(eq("C-DEFAULT"), eq(ROOT_TS),
                cardText.capture(), any());
        assertTrue(cardText.getValue().contains("Anonymized candidate"),
                "the root card must be redacted");
        assertFalse(cardText.getValue().contains("Anna"), "the name must be gone");
    }

    // ---- Blind rule + debrief ready -----------------------------------------------

    @Test
    void scorecardReply_submitterAndCountOnly_debriefReadyWhenAllIn() throws Exception {
        cardsOnWithChannel("C-DEFAULT");
        insertThreadRow("C-DEFAULT", ROOT_TS);
        String interviewUuid = UUID.randomUUID().toString();
        String scorecardUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertInterview(em, interviewUuid, applicationUuid,
                    "ROUND", 1, "[\"" + actorUser + "\"]", "SCHEDULED");
            P8ProfileFixtures.insertScorecard(em, scorecardUuid, interviewUuid,
                    actorUser, "STRONG_YES");
        });
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, "SCORECARD_SUBMITTED", candidateUuid,
                        applicationUuid, positionUuid, "USER", actorUser, "NORMAL",
                        "{\"interview_uuid\":\"" + interviewUuid + "\",\"recommendation_recorded\":true}",
                        "{\"notes\":\"" + PII_SENTINEL + " note\"}"));

        reactor.catchUp();

        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendThreadReply(eq("C-DEFAULT"), eq(ROOT_TS), reply.capture());
        assertTrue(reply.getValue().contains("Scorecard submitted by Rina Recruiter"),
                "reply names the submitter");
        assertTrue(reply.getValue().contains("(1 in)"), "reply counts the scorecards");
        assertTrue(reply.getValue().contains("Debrief ready"),
                "the completing scorecard flags debrief-ready");
        assertFalse(reply.getValue().contains("STRONG_YES"),
                "the blind rule: no recommendation before the decision");
        assertFalse(reply.getValue().contains(PII_SENTINEL), "scorecard notes never reach Slack");
    }

    // ---- Sentinel PII containment ---------------------------------------------------

    @Test
    void sentinelPii_neverAppearsInCardOrReplies() throws Exception {
        cardsOnWithChannel("C-DEFAULT");
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, "APPLICATION_CREATED", candidateUuid,
                        applicationUuid, positionUuid, "CANDIDATE", null, "NORMAL",
                        "{\"origin\":\"public_form\"}",
                        "{\"filename\":\"" + PII_SENTINEL + "-cv.pdf\",\"answers\":\""
                                + PII_SENTINEL + " essay\"}"));
        reactor.catchUp();
        insertStageChanged("SCREENING", "INTERVIEW_1", "FORWARD");
        reactor.catchUp();

        ArgumentCaptor<String> fallback = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendMessageReturningTs(anyString(), fallback.capture(), any());
        assertFalse(fallback.getValue().contains(PII_SENTINEL), "card fallback is structural only");
        ArgumentCaptor<String> updated = ArgumentCaptor.forClass(String.class);
        verify(slackService).updateMessageStrict(anyString(), anyString(), updated.capture(), any());
        assertFalse(updated.getValue().contains(PII_SENTINEL), "card update is structural only");
        ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendThreadReply(anyString(), anyString(), reply.capture());
        assertFalse(reply.getValue().contains(PII_SENTINEL), "replies are structural only");
    }
}
