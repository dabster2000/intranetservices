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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * P22 §DoD: the partner-channel reactor against the real chassis with a
 * mocked {@link SlackService}. Covers flag gating, partner-only scope,
 * channel creation with circle invites + confidentiality header,
 * replay idempotency (one channel per position, ever), membership sync on
 * circle events (including the self-heal for positions opened before the
 * toggle), and the close → summary + archive path.
 */
@QuarkusTest
class SlackChannelReactorTest {

    private static final String PIPELINE_FLAG = P8ProfileFixtures.PIPELINE_FLAG;
    private static final String CHANNELS_FLAG = "recruitment.slack.partner-channels.enabled";

    @Inject
    EntityManager em;

    @Inject
    SlackChannelReactor reactor;

    @InjectMock
    SlackService slackService;

    private String practiceUuid;
    private String actorUser;
    private String circleMember;
    private String positionUuid;

    private String previousPipeline;
    private String previousChannels;

    @BeforeEach
    void seed() throws Exception {
        practiceUuid = UUID.randomUUID().toString();
        actorUser = UUID.randomUUID().toString();
        circleMember = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, actorUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, circleMember, "Pia", "Partner");
            P12NotificationFixtures.setUserSlackLink(em, circleMember, "U-P22-CH-CIRCLE");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Partner — Cyber Security",
                    "PARTNER", practiceUuid, null, null);
            P8ProfileFixtures.insertCircleMember(em, positionUuid, circleMember);
            previousPipeline = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "false");
            previousChannels = P8ProfileFixtures.setFlag(em, CHANNELS_FLAG, "false");
        });
        reactor.catchUp();
        when(slackService.createPrivateChannel(anyString())).thenReturn("C-PRIV-1");
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_slack_channels WHERE position_uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_events WHERE position_uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(), List.of(positionUuid),
                    List.of(actorUser, circleMember), practiceUuid);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousPipeline);
            P8ProfileFixtures.restoreFlag(em, CHANNELS_FLAG, previousChannels);
        });
        reactor.catchUp();
    }

    // ---- helpers ---------------------------------------------------------------

    private void flagsOn() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
            P8ProfileFixtures.setFlag(em, CHANNELS_FLAG, "true");
        });
    }

    private long insertPositionEvent(String type, String payload) {
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, type, null, null, positionUuid,
                        "USER", actorUser, "CIRCLE", payload, null));
    }

    private long insertPositionOpened() {
        return insertPositionEvent("POSITION_OPENED",
                "{\"title\":\"Partner — Cyber Security\",\"hiring_track\":\"PARTNER\"}");
    }

    private String channelRow() {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> rows = em.createNativeQuery(
                            "SELECT channel_id FROM recruitment_slack_channels WHERE position_uuid = :p")
                    .setParameter("p", positionUuid).getResultList();
            return rows.isEmpty() ? null : rows.get(0).toString();
        });
    }

    private Object archivedAt() {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> rows = em.createNativeQuery(
                            "SELECT archived_at FROM recruitment_slack_channels WHERE position_uuid = :p")
                    .setParameter("p", positionUuid).getResultList();
            return rows.isEmpty() ? null : rows.get(0);
        });
    }

    // ---- Flag gating + scope -----------------------------------------------------

    @Test
    void flagOff_noChannel_offsetStillAdvances() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true"));
        long seq = insertPositionOpened();

        reactor.catchUp();

        verifyNoInteractions(slackService);
        assertNull(channelRow());
        assertTrue(reactor.watermark() >= seq, "flag-off events must advance the offset");
    }

    @Test
    void nonPartnerPosition_ignored() {
        flagsOn();
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_positions SET hiring_track = 'PRACTICE_TEAM' "
                                + "WHERE uuid = :p")
                        .setParameter("p", positionUuid).executeUpdate());
        insertPositionOpened();

        reactor.catchUp();

        verifyNoInteractions(slackService);
        assertNull(channelRow());
    }

    // ---- Creation -----------------------------------------------------------------

    @Test
    void positionOpened_createsPrivateChannel_invitesCircle_postsHeader() throws Exception {
        flagsOn();
        insertPositionOpened();

        reactor.catchUp();

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(1)).createPrivateChannel(name.capture());
        assertTrue(name.getValue().startsWith("recr-partner-cyber-security-"),
                "channel name is recr-<slug>-<id>, got " + name.getValue());
        ArgumentCaptor<User> invited = ArgumentCaptor.forClass(User.class);
        verify(slackService, times(1)).inviteToChannel(invited.capture(), eq("C-PRIV-1"));
        assertEquals(circleMember, invited.getValue().getUuid());
        ArgumentCaptor<String> header = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendMessage(eq("C-PRIV-1"), header.capture());
        assertTrue(header.getValue().contains("Confidential"), "the header states confidentiality");
        assertEquals("C-PRIV-1", channelRow(), "projection row persisted");
    }

    @Test
    void replay_reconcilesMembership_neverCreatesSecondChannel() throws Exception {
        flagsOn();
        long seq = insertPositionOpened();
        reactor.catchUp();

        // Simulate a redelivery whose dedupe row is gone: a second circle
        // event arrives — same position, channel row already exists.
        insertPositionEvent("CIRCLE_MEMBER_ADDED",
                "{\"member_uuid\":\"" + circleMember + "\",\"role_in_circle\":\"PARTICIPANT\"}");
        reactor.catchUp();
        reactor.deliverLive(seq);

        verify(slackService, times(1)).createPrivateChannel(anyString());
        // Membership reconciled on both deliveries (invite tolerates already_in_channel).
        verify(slackService, times(2)).inviteToChannel(any(User.class), eq("C-PRIV-1"));
    }

    // ---- Membership sync -----------------------------------------------------------

    @Test
    void circleMemberRemoved_kicksFromChannel() throws Exception {
        flagsOn();
        insertPositionOpened();
        reactor.catchUp();

        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("DELETE FROM recruitment_circle_members "
                                + "WHERE position_uuid = :p AND user_uuid = :u")
                        .setParameter("p", positionUuid)
                        .setParameter("u", circleMember).executeUpdate());
        insertPositionEvent("CIRCLE_MEMBER_REMOVED",
                "{\"member_uuid\":\"" + circleMember + "\"}");
        reactor.catchUp();

        ArgumentCaptor<User> kicked = ArgumentCaptor.forClass(User.class);
        verify(slackService, times(1)).kickFromChannel(kicked.capture(), eq("C-PRIV-1"));
        assertEquals(circleMember, kicked.getValue().getUuid());
    }

    @Test
    void positionOpenedBeforeToggle_channelSelfHealsOnNextCircleEvent() throws Exception {
        // POSITION_OPENED swept while the toggle was off (no channel).
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true"));
        insertPositionOpened();
        reactor.catchUp();
        assertNull(channelRow());

        // Toggle on → the next circle event creates the channel with the
        // CURRENT circle (the no-retroactive rule, self-healing forward).
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, CHANNELS_FLAG, "true"));
        insertPositionEvent("CIRCLE_MEMBER_ADDED",
                "{\"member_uuid\":\"" + circleMember + "\",\"role_in_circle\":\"PARTICIPANT\"}");
        reactor.catchUp();

        verify(slackService, times(1)).createPrivateChannel(anyString());
        assertEquals("C-PRIV-1", channelRow());
    }

    // ---- Close ----------------------------------------------------------------------

    @Test
    void positionClosed_postsSummary_archives_setsArchivedAt_idempotently() throws Exception {
        flagsOn();
        insertPositionOpened();
        reactor.catchUp();

        insertPositionEvent("POSITION_CLOSED",
                "{\"title\":\"Partner — Cyber Security\",\"hiring_track\":\"PARTNER\"}");
        reactor.catchUp();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(2)).sendMessage(eq("C-PRIV-1"), summary.capture());
        assertTrue(summary.getValue().contains("Position closed"),
                "the summary announces the close");
        assertTrue(summary.getValue().contains("archived"), "the summary announces the archive");
        verify(slackService, times(1)).archiveChannel("C-PRIV-1");
        assertNotNull(archivedAt(), "archived_at persisted");

        // A second close replay is a no-op (archived_at set).
        insertPositionEvent("POSITION_CLOSED",
                "{\"title\":\"Partner — Cyber Security\",\"hiring_track\":\"PARTNER\"}");
        reactor.catchUp();
        verify(slackService, times(1)).archiveChannel("C-PRIV-1");
    }
}
