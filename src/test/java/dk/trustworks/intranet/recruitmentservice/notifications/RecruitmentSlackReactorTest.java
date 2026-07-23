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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * P12 §DoD: the outbound Slack reactor against the real chassis
 * (raw-inserted committed events + deterministic {@code catchUp()} sweeps
 * — the exact crash-recovery/kill-between-commit-and-dispatch shape) with
 * a mocked {@link SlackService}. Covers flag gating, replay idempotency,
 * offset seeding (no historical replay), settings-driven channel routing
 * without redeploy, default-channel fallback, circle suppression with
 * DM degradation, the AI-brief enrichment + 3 000-char clamp, the
 * debrief-ready all-scorecards-in rule, and sentinel-PII containment for
 * every message builder.
 */
@QuarkusTest
class RecruitmentSlackReactorTest {

    private static final String PIPELINE_FLAG = P8ProfileFixtures.PIPELINE_FLAG;
    private static final String BRIEF_FLAG = "recruitment.ai.brief.enabled";
    private static final String TRIAGE_FLAG = "recruitment.slack.triage-actions.enabled";
    private static final String SCORECARD_TOGGLE = "recruitment.slack.scorecard.enabled";
    private static final String DEFAULT_KEY = RecruitmentSlackChannelRouter.DEFAULT_CHANNEL_KEY;

    @Inject
    EntityManager em;

    @Inject
    RecruitmentSlackReactor reactor;

    @InjectMock
    SlackService slackService;

    private String practiceUuid;
    private String actorUser;
    private String interviewer1;
    private String interviewer2;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;

    private String previousPipeline;
    private String previousBrief;
    private String previousTriage;
    private String previousScorecardToggle;
    private String previousDefault;
    private String previousOverride;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        actorUser = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();

        interviewer1 = UUID.randomUUID().toString();
        interviewer2 = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, actorUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, interviewer1, "Ivan", "Interviewer");
            P8ProfileFixtures.insertUser(em, interviewer2, "Carla", "CoInterviewer");
            P12NotificationFixtures.setUserSlackLink(em, interviewer1, "U-P18-I1");
            P12NotificationFixtures.setUserSlackLink(em, interviewer2, "U-P18-I2");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Senior Consultant",
                    "PRACTICE_TEAM", practiceUuid, null, null);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "Anna", "Ager", "ACTIVE", null, null, actorUser);
            P12NotificationFixtures.setCandidateSource(em, candidateUuid, "WEBSITE");
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "SCREENING");
            previousPipeline = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "false");
            previousBrief = P8ProfileFixtures.setFlag(em, BRIEF_FLAG, "false");
            previousTriage = P8ProfileFixtures.setFlag(em, TRIAGE_FLAG, "false");
            previousScorecardToggle = P8ProfileFixtures.setFlag(em, SCORECARD_TOGGLE, "false");
            previousDefault = P8ProfileFixtures.setFlag(em, DEFAULT_KEY, "");
            previousOverride = null;
        });
        // Drain any backlog with the flag OFF so each test's sweep only
        // reflects its own trigger events.
        reactor.catchUp();
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_events WHERE position_uuid = :p "
                            + "AND candidate_uuid IS NULL")
                    .setParameter("p", positionUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_events WHERE event_type = 'REFERRAL_SUBMITTED' "
                            + "AND actor_uuid = :a")
                    .setParameter("a", actorUser).executeUpdate();
            P12NotificationFixtures.deleteReferralsBy(em, actorUser);
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(actorUser, interviewer1, interviewer2), practiceUuid);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousPipeline);
            P8ProfileFixtures.restoreFlag(em, BRIEF_FLAG, previousBrief);
            P8ProfileFixtures.restoreFlag(em, TRIAGE_FLAG, previousTriage);
            P8ProfileFixtures.restoreFlag(em, SCORECARD_TOGGLE, previousScorecardToggle);
            P8ProfileFixtures.restoreFlag(em, DEFAULT_KEY, previousDefault);
            P8ProfileFixtures.restoreFlag(em,
                    RecruitmentSlackChannelRouter.PRACTICE_CHANNEL_KEY_PREFIX + practiceUuid,
                    previousOverride);
        });
        // Advance past everything this test appended so the next test's
        // pre-sweep starts clean.
        reactor.catchUp();
    }

    // ---- helpers ---------------------------------------------------------------

    private void pipelineOnWithChannel(String channel) {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
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

    private String channelMessageAfterCatchUp(int expectedSends) {
        reactor.catchUp();
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(expectedSends)).sendMessage(anyString(), message.capture());
        return message.getValue();
    }

    // ---- Flag gating -----------------------------------------------------------

    @Test
    void pipelineFlagOff_noSlackCall_offsetStillAdvances() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, DEFAULT_KEY, "C-DEFAULT"));
        long seq = insertApplicationCreated("NORMAL");

        reactor.catchUp();

        verifyNoInteractions(slackService);
        assertTrue(reactor.watermark() >= seq, "flag-off events must advance the offset (no backfill)");
    }

    // ---- New application + idempotency + crash recovery --------------------------

    @Test
    void committedEventWithoutLiveDispatch_isDeliveredByCatchUp_exactlyOnceUnderReplay() {
        pipelineOnWithChannel("C-DEFAULT");
        // Raw insert = committed event whose live dispatch never happened —
        // the kill-between-commit-and-dispatch scenario.
        long seq = insertApplicationCreated("NORMAL");

        String message = channelMessageAfterCatchUp(1);
        assertTrue(message.contains("Anna Ager"), "ping must name the candidate");
        assertTrue(message.contains("Senior Consultant"), "ping must carry the position title");
        assertTrue(message.contains("Website"), "ping must carry the humanized source");
        assertTrue(message.contains("/recruitment/candidates/" + candidateUuid),
                "ping must deep-link the profile");

        // Replay through both paths — the durable dedupe keeps it at one.
        reactor.catchUp();
        reactor.deliverLive(seq);
        verify(slackService, times(1)).sendMessage(anyString(), anyString());
    }

    // ---- Routing ----------------------------------------------------------------

    @Test
    void practiceOverrideRoutes_thenSettingsChangeApplies_withoutRedeploy() {
        pipelineOnWithChannel("C-DEFAULT");
        String overrideKey = RecruitmentSlackChannelRouter.PRACTICE_CHANNEL_KEY_PREFIX + practiceUuid;
        QuarkusTransaction.requiringNew().run(() ->
                previousOverride = P8ProfileFixtures.setFlag(em, overrideKey, "C-PRACTICE"));

        insertApplicationCreated("NORMAL");
        reactor.catchUp();

        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, overrideKey, "C-PRACTICE-2"));
        insertApplicationCreated("NORMAL");
        reactor.catchUp();

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(2)).sendMessage(channel.capture(), anyString());
        assertEquals(List.of("C-PRACTICE", "C-PRACTICE-2"), channel.getAllValues(),
                "routing must follow the settings row per event — no cache, no redeploy");
    }

    @Test
    void unconfiguredPractice_fallsBackToDefaultChannel() {
        // No override row for the practice (the freshly-created-practice case).
        pipelineOnWithChannel("C-DEFAULT");
        insertApplicationCreated("NORMAL");
        reactor.catchUp();

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendMessage(channel.capture(), anyString());
        assertEquals("C-DEFAULT", channel.getValue());
    }

    @Test
    void noChannelConfigured_skipsSilently_offsetAdvances() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true")); // default stays blank
        long seq = insertApplicationCreated("NORMAL");

        reactor.catchUp();

        verifyNoInteractions(slackService);
        assertTrue(reactor.watermark() >= seq);
    }

    // ---- Offset seeding (no historical replay) -----------------------------------

    @Test
    void freshlyDeployedReactor_seedsToHead_zeroMessagesForHistory() {
        pipelineOnWithChannel("C-DEFAULT");
        insertApplicationCreated("NORMAL");
        insertApplicationCreated("NORMAL");
        // Forget the reactor entirely — the "deploy against a store with
        // historical events" precondition.
        QuarkusTransaction.requiringNew().run(() ->
                P12NotificationFixtures.resetReactorState(em, RecruitmentSlackReactor.NAME));

        reactor.catchUp();

        verifyNoInteractions(slackService);
    }

    // ---- Circle suppression -------------------------------------------------------

    @Test
    void circleEvent_neverPostsToSharedChannel_dmsLinkedCircleMembers() throws Exception {
        pipelineOnWithChannel("C-DEFAULT");
        String memberLinked = UUID.randomUUID().toString();
        String memberUnlinked = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, memberLinked, "Pia", "Partner");
            P12NotificationFixtures.setUserSlackLink(em, memberLinked, "U-PIA");
            P8ProfileFixtures.insertUser(em, memberUnlinked, "Ulf", "Unlinked");
            P8ProfileFixtures.insertCircleMember(em, positionUuid, memberLinked);
            P8ProfileFixtures.insertCircleMember(em, positionUuid, memberUnlinked);
        });
        try {
            insertApplicationCreated("CIRCLE");
            reactor.catchUp();

            verify(slackService, never()).sendMessage(anyString(), anyString());
            ArgumentCaptor<User> recipient = ArgumentCaptor.forClass(User.class);
            verify(slackService, times(1)).sendMessage(recipient.capture(), anyString());
            assertEquals(memberLinked, recipient.getValue().getUuid(),
                    "only the Slack-linked circle member gets the DM");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(), List.of(), List.of(memberLinked, memberUnlinked), null));
        }
    }

    @Test
    void positionlessCircleEvent_resolvesCircleViaPartnerApplications() throws Exception {
        pipelineOnWithChannel("C-DEFAULT");
        String partnerPosition = UUID.randomUUID().toString();
        String partnerApplication = UUID.randomUUID().toString();
        String member = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertPosition(em, partnerPosition, "Partner hire",
                    "PARTNER", null, null, null);
            P8ProfileFixtures.insertOpenApplication(em, partnerApplication,
                    candidateUuid, partnerPosition, "OFFER");
            P8ProfileFixtures.insertUser(em, member, "Pia", "Partner");
            P12NotificationFixtures.setUserSlackLink(em, member, "U-PIA");
            P8ProfileFixtures.insertCircleMember(em, partnerPosition, member);
        });
        try {
            // The fail-closed SIGNING_COMPLETED shape: candidate subject only.
            QuarkusTransaction.requiringNew().run(() ->
                    P8ProfileFixtures.insertEvent(em, "SIGNING_COMPLETED", candidateUuid,
                            null, null, "SYSTEM", null, "CIRCLE",
                            "{\"case_key\":\"case-1\"}", null));
            reactor.catchUp();

            verify(slackService, never()).sendMessage(anyString(), anyString());
            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(slackService, times(1)).sendMessage(any(User.class), message.capture());
            assertTrue(message.getValue().contains("Contract signed"));
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                // The partner application references the partner position
                // (FK RESTRICT) — drop it before the position cleanup.
                em.createNativeQuery("DELETE FROM recruitment_applications WHERE uuid = :a")
                        .setParameter("a", partnerApplication).executeUpdate();
                P8ProfileFixtures.cleanupRecruitmentRows(em,
                        List.of(), List.of(partnerPosition), List.of(member), null);
            });
        }
    }

    // ---- AI brief enrichment -------------------------------------------------------

    @Test
    void briefFlagOn_appendsBullets_truncatedToClamp_structuralFieldsIntact() {
        pipelineOnWithChannel("C-DEFAULT");
        StringBuilder bullets = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            bullets.append(i == 0 ? "" : ",").append("\"")
                    .append("Punkt ").append(i).append(" ").append("x".repeat(120)).append("\"");
        }
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, BRIEF_FLAG, "true");
            P8ProfileFixtures.insertEvent(em, "AI_BRIEF_GENERATED", candidateUuid,
                    applicationUuid, positionUuid, "SYSTEM", null, "NORMAL",
                    "{\"generation_id\":\"g1\",\"model\":\"test\"}",
                    "{\"bullets\":[" + bullets + "]}");
        });
        insertApplicationCreated("NORMAL");

        String message = channelMessageAfterCatchUp(1);
        assertTrue(message.startsWith(":inbox_tray: *New application*"),
                "structural header must never be truncated");
        assertTrue(message.contains("/recruitment/candidates/" + candidateUuid),
                "the deep link is structural — never truncated");
        assertTrue(message.contains("AI-genereret resumé"), "brief section present");
        assertTrue(message.contains("Punkt 0"), "leading bullets fit");
        assertTrue(message.length() <= RecruitmentSlackReactor.MESSAGE_CLAMP,
                "message must respect the 3000-char Slack clamp, was " + message.length());
        assertTrue(message.endsWith("…"), "truncation is marked");
    }

    @Test
    void briefFlagOff_structuralPingOnly() {
        pipelineOnWithChannel("C-DEFAULT");
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertEvent(em, "AI_BRIEF_GENERATED", candidateUuid,
                        applicationUuid, positionUuid, "SYSTEM", null, "NORMAL",
                        "{\"generation_id\":\"g1\",\"model\":\"test\"}",
                        "{\"bullets\":[\"" + PII_SENTINEL + " punkt\"]}"));
        insertApplicationCreated("NORMAL");

        String message = channelMessageAfterCatchUp(1);
        assertFalse(message.contains("AI-genereret"), "no brief section when the toggle is off");
        assertFalse(message.contains(PII_SENTINEL), "brief text must not leak when the toggle is off");
    }

    // ---- Debrief ready ---------------------------------------------------------------

    @Test
    void debriefReady_firesOnlyOnTheCompletingScorecard_andCarriesNoScores() {
        pipelineOnWithChannel("C-DEFAULT");
        String interviewUuid = UUID.randomUUID().toString();
        String interviewer1 = UUID.randomUUID().toString();
        String interviewer2 = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertInterview(em, interviewUuid, applicationUuid, "ROUND", 1,
                    "[\"" + interviewer1 + "\",\"" + interviewer2 + "\"]", "HELD");
            P8ProfileFixtures.insertScorecard(em, UUID.randomUUID().toString(),
                    interviewUuid, interviewer1, "YES");
            P8ProfileFixtures.insertEvent(em, "SCORECARD_SUBMITTED", candidateUuid,
                    applicationUuid, positionUuid, "USER", interviewer1, "NORMAL",
                    "{\"interview_uuid\":\"" + interviewUuid + "\",\"kind\":\"ROUND\",\"round\":1}",
                    "{\"notes\":\"" + PII_SENTINEL + " note\"}");
        });
        reactor.catchUp();
        verifyNoInteractions(slackService); // first of two scorecards — not ready

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertScorecard(em, UUID.randomUUID().toString(),
                    interviewUuid, interviewer2, "STRONG_YES");
            P8ProfileFixtures.insertEvent(em, "SCORECARD_SUBMITTED", candidateUuid,
                    applicationUuid, positionUuid, "USER", interviewer2, "NORMAL",
                    "{\"interview_uuid\":\"" + interviewUuid + "\",\"kind\":\"ROUND\",\"round\":1}",
                    null);
        });
        String message = channelMessageAfterCatchUp(1);
        assertTrue(message.contains("Debrief ready"));
        assertTrue(message.contains("all 2 scorecards"));
        assertTrue(message.contains("Anna Ager"));
        assertTrue(message.contains("round 1"));
        assertFalse(message.contains("STRONG_YES"), "recommendations never reach Slack (P11 rule)");
        assertFalse(message.contains(PII_SENTINEL), "scorecard notes never reach Slack");
    }

    // ---- P18: interview-kit DMs + the debrief-ready owner DM -------------------------

    private long insertInterviewLifecycleEvent(String type, String interviewUuid, Integer round) {
        String kind = round == null ? "INFORMAL" : "ROUND";
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, type, candidateUuid, applicationUuid,
                        positionUuid, "USER", actorUser, "NORMAL",
                        "{\"interview_uuid\":\"" + interviewUuid + "\",\"kind\":\"" + kind
                                + "\"" + (round == null ? "" : ",\"round\":" + round) + "}",
                        null));
    }

    /** All DM texts sent to the given user via the plain-text DM overload. */
    private List<String> dmTextsTo(String userUuid) {
        ArgumentCaptor<dk.trustworks.intranet.domain.user.entity.User> users =
                ArgumentCaptor.forClass(dk.trustworks.intranet.domain.user.entity.User.class);
        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        try {
            verify(slackService, org.mockito.Mockito.atLeast(0))
                    .sendMessage(users.capture(), texts.capture());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return java.util.stream.IntStream.range(0, users.getAllValues().size())
                .filter(i -> userUuid.equals(users.getAllValues().get(i).getUuid()))
                .mapToObj(i -> texts.getAllValues().get(i))
                .toList();
    }

    @Test
    void interviewScheduled_kitDmToEachInterviewer_deepLinkOnlyWhileToggleOff() throws Exception {
        pipelineOnWithChannel("C-DEFAULT");
        String interviewUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertInterview(em, interviewUuid, applicationUuid, "ROUND", 1,
                        "[\"" + interviewer1 + "\",\"" + interviewer2 + "\"]", "SCHEDULED"));
        insertInterviewLifecycleEvent("INTERVIEW_SCHEDULED", interviewUuid, 1);

        reactor.catchUp();

        for (String interviewer : List.of(interviewer1, interviewer2)) {
            List<String> dms = dmTextsTo(interviewer);
            assertEquals(1, dms.size(), "one kit DM per assigned interviewer");
            String dm = dms.getFirst();
            assertTrue(dm.contains("Interview scheduled"), dm);
            assertTrue(dm.contains("Anna Ager"), "kit names the candidate");
            assertTrue(dm.contains("Senior Consultant"), "kit carries the position");
            assertTrue(dm.contains("round 1"), "kit states the round");
            assertTrue(dm.contains("Why consulting"), "kit lists the focus areas");
            assertTrue(dm.contains("/recruitment/interviews"), "kit deep link");
        }
        // Toggle off ⇒ never the Block Kit DM overload (deep-link-only —
        // the explicit degradation chain).
        verify(slackService, never()).sendMessage(
                any(dk.trustworks.intranet.domain.user.entity.User.class), anyString(), any());
        // No channel post for kit DMs — interviewer-directed by construction.
        verify(slackService, never()).sendMessage(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void interviewScheduled_toggleOn_kitDmCarriesScorecardButton() {
        pipelineOnWithChannel("C-DEFAULT");
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, SCORECARD_TOGGLE, "true"));
        String interviewUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertInterview(em, interviewUuid, applicationUuid, "ROUND", 1,
                        "[\"" + interviewer1 + "\"]", "SCHEDULED"));
        insertInterviewLifecycleEvent("INTERVIEW_SCHEDULED", interviewUuid, 1);

        reactor.catchUp();

        ArgumentCaptor<dk.trustworks.intranet.domain.user.entity.User> user =
                ArgumentCaptor.forClass(dk.trustworks.intranet.domain.user.entity.User.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<com.slack.api.model.block.LayoutBlock>> blocks =
                ArgumentCaptor.forClass((Class) List.class);
        try {
            verify(slackService).sendMessage(user.capture(), text.capture(), blocks.capture());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals(interviewer1, user.getValue().getUuid());
        assertTrue(text.getValue().contains("Interview scheduled"),
                "fallback text stays the full kit message");
        String rendered = blocks.getValue().toString();
        assertTrue(rendered.contains("recruitment_scorecard_open"),
                "the scorecard button hangs on the kit DM: " + rendered);
        assertTrue(rendered.contains(interviewUuid), "button value = interview uuid");
    }

    @Test
    void interviewCancelled_dmWithoutButton_evenWithToggleOn() throws Exception {
        pipelineOnWithChannel("C-DEFAULT");
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, SCORECARD_TOGGLE, "true"));
        String interviewUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.insertInterview(em, interviewUuid, applicationUuid, "ROUND", 2,
                        "[\"" + interviewer1 + "\"]", "CANCELLED"));
        insertInterviewLifecycleEvent("INTERVIEW_CANCELLED", interviewUuid, 2);

        reactor.catchUp();

        List<String> dms = dmTextsTo(interviewer1);
        assertEquals(1, dms.size());
        assertTrue(dms.getFirst().contains("Interview cancelled"), dms.getFirst());
        assertTrue(dms.getFirst().contains("nothing more to do"), dms.getFirst());
        verify(slackService, never()).sendMessage(
                any(dk.trustworks.intranet.domain.user.entity.User.class), anyString(), any());
    }

    @Test
    void interviewScheduled_unlinkedInterviewer_visibleSkip_othersStillDmed() {
        pipelineOnWithChannel("C-DEFAULT");
        String unlinked = UUID.randomUUID().toString();
        String interviewUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, unlinked, "Ulla", "Unlinked");
            P8ProfileFixtures.insertInterview(em, interviewUuid, applicationUuid, "ROUND", 1,
                    "[\"" + interviewer1 + "\",\"" + unlinked + "\"]", "SCHEDULED");
        });
        insertInterviewLifecycleEvent("INTERVIEW_SCHEDULED", interviewUuid, 1);
        try {
            reactor.catchUp();
            assertEquals(1, dmTextsTo(interviewer1).size(), "linked interviewer gets the kit");
            assertEquals(0, dmTextsTo(unlinked).size(), "unlinked interviewer skipped, not fatal");
        } finally {
            QuarkusTransaction.requiringNew().run(() ->
                    em.createNativeQuery("DELETE FROM user WHERE uuid = :u")
                            .setParameter("u", unlinked).executeUpdate());
        }
    }

    @Test
    void debriefReady_alsoDmsTheDecisionOwner_deepLinkNoButtons() throws Exception {
        pipelineOnWithChannel("C-DEFAULT");
        // interviewer1 doubles as the position's hiring owner — the P17
        // owner ladder's first rung (shared code, RecruitmentSlaService).
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_positions SET hiring_owner_uuid = :o "
                                + "WHERE uuid = :p")
                        .setParameter("o", interviewer1)
                        .setParameter("p", positionUuid).executeUpdate());
        String interviewUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertInterview(em, interviewUuid, applicationUuid, "ROUND", 1,
                    "[\"" + interviewer2 + "\"]", "HELD");
            P8ProfileFixtures.insertScorecard(em, UUID.randomUUID().toString(),
                    interviewUuid, interviewer2, "STRONG_YES");
            P8ProfileFixtures.insertEvent(em, "SCORECARD_SUBMITTED", candidateUuid,
                    applicationUuid, positionUuid, "USER", interviewer2, "NORMAL",
                    "{\"interview_uuid\":\"" + interviewUuid
                            + "\",\"kind\":\"ROUND\",\"round\":1,\"origin\":\"slack\"}",
                    "{\"notes\":\"" + PII_SENTINEL + " note\"}");
        });

        String channelMessage = channelMessageAfterCatchUp(1);
        assertTrue(channelMessage.contains("Debrief ready"), "channel ping unchanged");

        List<String> ownerDms = dmTextsTo(interviewer1);
        assertEquals(1, ownerDms.size(), "the decision owner is DM'ed personally");
        String dm = ownerDms.getFirst();
        assertTrue(dm.contains("Debrief ready"), dm);
        assertTrue(dm.contains("/recruitment/candidates/" + candidateUuid),
                "deep link — the decision happens in the intranet");
        assertFalse(dm.contains("STRONG_YES"), "no scores/recommendations (blind rule)");
        assertFalse(dm.contains(PII_SENTINEL), "no scorecard notes");
        // No decision buttons anywhere in Slack (the locked boundary):
        // the owner DM uses the plain-text overload, never blocks.
        verify(slackService, never()).sendMessage(
                any(dk.trustworks.intranet.domain.user.entity.User.class), anyString(), any());
    }

    // ---- Slack mrkdwn injection ------------------------------------------------------

    @Test
    void mrkdwnControlCharsInApplicantName_areEscaped_neverRenderAsLinks() {
        pipelineOnWithChannel("C-DEFAULT");
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates "
                                + "SET first_name = :f, last_name = :l WHERE uuid = :uuid")
                        .setParameter("f", "<https://evil.example|Click here>")
                        .setParameter("l", "& Co")
                        .setParameter("uuid", candidateUuid).executeUpdate());
        insertApplicationCreated("NORMAL");

        String message = channelMessageAfterCatchUp(1);
        assertFalse(message.contains("<https://evil.example"),
                "applicant-controlled text must never render as a Slack link");
        assertTrue(message.contains("&lt;https://evil.example|Click here&gt; &amp; Co"),
                "control characters are escaped, not stripped: " + message);
    }

    // ---- Sentinel PII across every builder ----------------------------------------------

    @Test
    void sentinelPii_neverAppearsInAnyChannelMessage() {
        pipelineOnWithChannel("C-DEFAULT");
        QuarkusTransaction.requiringNew().run(() -> {
            // Free text everywhere free text can live around the five pings.
            String referralUuid = P12NotificationFixtures.insertReferral(em, actorUser,
                    "Bo Berg", PII_SENTINEL + " why-text", "SUBMITTED", null);
            P8ProfileFixtures.insertEvent(em, "REFERRAL_SUBMITTED", null, null, null,
                    "USER", actorUser, "NORMAL",
                    "{\"referral_uuid\":\"" + referralUuid + "\",\"relation\":\"FORMER_COLLEAGUE\"}",
                    "{\"candidate_name\":\"Bo Berg\",\"why_text\":\"" + PII_SENTINEL + " why\"}");
            P8ProfileFixtures.insertEvent(em, "APPLICATION_CREATED", candidateUuid,
                    applicationUuid, positionUuid, "CANDIDATE", null, "NORMAL",
                    "{\"origin\":\"public_form\"}",
                    "{\"filename\":\"" + PII_SENTINEL + "-cv.pdf\"}");
            P8ProfileFixtures.insertEvent(em, "SIGNING_COMPLETED", candidateUuid,
                    null, null, "SYSTEM", null, "NORMAL", "{\"case_key\":\"case-2\"}", null);
            P8ProfileFixtures.insertEvent(em, "POSITION_OPENED", null, null, positionUuid,
                    "USER", actorUser, "NORMAL",
                    "{\"title\":\"Senior Consultant\",\"hiring_track\":\"PRACTICE_TEAM\"}", null);
        });

        reactor.catchUp();

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(4)).sendMessage(anyString(), messages.capture());
        for (String message : messages.getAllValues()) {
            assertFalse(message.contains(PII_SENTINEL),
                    "free text leaked into a Slack message: " + message);
        }
        // The referral ping renders the name COLUMN + referrer, never why-text.
        assertTrue(messages.getAllValues().stream()
                        .anyMatch(m -> m.contains("New referral") && m.contains("Bo Berg")
                                && m.contains("Rina Recruiter")),
                "referral ping must name candidate and referrer");
        assertTrue(messages.getAllValues().stream()
                        .anyMatch(m -> m.contains("Position opened") && m.contains("Senior Consultant")),
                "position ping present");
    }

    // ---- P14: triage buttons on the new-referral ping -----------------------------

    @Test
    @SuppressWarnings("unchecked")
    void referralPing_carriesTriageButtons_whenTriageActionsOn() {
        pipelineOnWithChannel("C-DEFAULT");
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, TRIAGE_FLAG, "true"));
        String referralUuid = QuarkusTransaction.requiringNew().call(() -> {
            String uuid = P12NotificationFixtures.insertReferral(em, actorUser,
                    "Betty Buttons", "why", "SUBMITTED", null);
            P8ProfileFixtures.insertEvent(em, "REFERRAL_SUBMITTED", null, null, null,
                    "USER", actorUser, "NORMAL",
                    "{\"referral_uuid\":\"" + uuid + "\",\"relation\":\"COLLEAGUE\"}",
                    "{\"candidate_name\":\"Betty Buttons\"}");
            return uuid;
        });

        reactor.catchUp();

        ArgumentCaptor<List<com.slack.api.model.block.LayoutBlock>> blocks =
                ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<String> fallback = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendMessage(anyString(), fallback.capture(), blocks.capture());
        assertTrue(fallback.getValue().contains("New referral"),
                "the flat text stays as notification fallback");

        var actions = blocks.getValue().stream()
                .filter(com.slack.api.model.block.ActionsBlock.class::isInstance)
                .map(com.slack.api.model.block.ActionsBlock.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("actions block missing"));
        var buttons = actions.getElements().stream()
                .map(com.slack.api.model.block.element.ButtonElement.class::cast)
                .toList();
        assertEquals(List.of("recruitment_triage_create", "recruitment_triage_view",
                        "recruitment_triage_dismiss"),
                buttons.stream().map(b -> b.getActionId()).toList());
        assertEquals(referralUuid, buttons.get(0).getValue(),
                "the create button carries the referral id");
        assertEquals(referralUuid, buttons.get(2).getValue(),
                "the dismiss button carries the referral id");
        assertTrue(buttons.get(1).getUrl().endsWith("/recruitment/refer"),
                "View-in-intranet is a URL button to the triage queue");
    }

    @Test
    void referralPing_staysFlat_whenTriageActionsOff() {
        pipelineOnWithChannel("C-DEFAULT");
        QuarkusTransaction.requiringNew().run(() -> {
            String uuid = P12NotificationFixtures.insertReferral(em, actorUser,
                    "Flat Frida", "why", "SUBMITTED", null);
            P8ProfileFixtures.insertEvent(em, "REFERRAL_SUBMITTED", null, null, null,
                    "USER", actorUser, "NORMAL",
                    "{\"referral_uuid\":\"" + uuid + "\",\"relation\":\"COLLEAGUE\"}",
                    "{\"candidate_name\":\"Flat Frida\"}");
        });

        String message = channelMessageAfterCatchUp(1);
        assertTrue(message.contains("New referral"),
                "toggle off ⇒ the P12 flat ping — the permanent degradation path");
        verify(slackService, never()).sendMessage(anyString(), anyString(),
                org.mockito.ArgumentMatchers.<com.slack.api.model.block.LayoutBlock>anyList());
    }
}
