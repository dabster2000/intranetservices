package dk.trustworks.intranet.recruitmentservice.services;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.ai.AiDigestService;
import dk.trustworks.intranet.recruitmentservice.dto.GdprQueueResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P24 §DoD — the DPO exception digest against the local DB with a mocked
 * Slack transport: every queue category + upcoming deletions + the
 * circle/channel-membership drift check rendered into one DM per
 * DPO-role holder, event-derived idempotency per (recipient, ISO week),
 * DM-before-event rollback on Slack failure, flag gating
 * ({@code recruitment.gdpr.enabled} — the engine's flag, not the
 * pipeline's), the unlinked-recipient visible skip, the empty-week
 * message, and the drift-check-unavailable posture. The shared local DB
 * carries a real DPO-role holder — every assertion is scoped to the
 * fixture users.
 */
@QuarkusTest
class RecruitmentDpoDigestServiceTest {

    private static final String GDPR_FLAG = "recruitment.gdpr.enabled";
    private static final String DPO_DIGEST_FLAG = "recruitment.slack.dpo-digest.enabled";
    private static final String SENTINEL = RecruitmentEventPiiAssertions.PII_SENTINEL;

    @Inject
    RecruitmentDpoDigestService service;

    @Inject
    EntityManager em;

    @InjectMock
    SlackService slackService;

    private String marker;
    private String dpoUserUuid;
    private String dpoUnlinkedUuid;
    private String circleUserUuid;
    private String intruderUserUuid;
    private String practiceUuid;
    private String partnerPositionUuid;
    private String channelId;
    private String art14CandidateUuid;
    private String consentCandidateUuid;
    private String dsarCandidateUuid;
    private String upcomingCandidateUuid;

    private String previousGdpr;
    private String previousDigest;

    @BeforeEach
    void seed() throws Exception {
        marker = UUID.randomUUID().toString().substring(0, 8);
        dpoUserUuid = UUID.randomUUID().toString();
        dpoUnlinkedUuid = UUID.randomUUID().toString();
        circleUserUuid = UUID.randomUUID().toString();
        intruderUserUuid = UUID.randomUUID().toString();
        practiceUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        channelId = "C-P24DPO-" + marker;
        art14CandidateUuid = UUID.randomUUID().toString();
        consentCandidateUuid = UUID.randomUUID().toString();
        dsarCandidateUuid = UUID.randomUUID().toString();
        upcomingCandidateUuid = UUID.randomUUID().toString();

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        QuarkusTransaction.requiringNew().run(() -> {
            previousGdpr = P8ProfileFixtures.setFlag(em, GDPR_FLAG, "true");
            previousDigest = P8ProfileFixtures.setFlag(em, DPO_DIGEST_FLAG, "true");
            deleteDigestEvents();

            P8ProfileFixtures.insertUser(em, dpoUserUuid, "Dora", "Dpo" + marker);
            P8ProfileFixtures.insertRole(em, dpoUserUuid, "DPO");
            linkSlack(dpoUserUuid, "U-P24DPO-" + marker);
            P8ProfileFixtures.insertUser(em, dpoUnlinkedUuid, "Ulla", "Unlinked" + marker);
            P8ProfileFixtures.insertRole(em, dpoUnlinkedUuid, "DPO");

            // Drift fixtures: a live partner channel whose Slack membership
            // carries one human who is NOT a circle member.
            P8ProfileFixtures.insertUser(em, circleUserUuid, "Cilla", "Circle" + marker);
            linkSlack(circleUserUuid, "U-P24C-" + marker);
            P8ProfileFixtures.insertUser(em, intruderUserUuid, "Ivan", "Intruder" + marker);
            linkSlack(intruderUserUuid, "U-P24I-" + marker);
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, partnerPositionUuid,
                    "Partner Search " + marker, "PARTNER", null, null, null);
            P8ProfileFixtures.insertCircleMember(em, partnerPositionUuid, circleUserUuid);
            em.createNativeQuery("INSERT INTO recruitment_slack_channels "
                            + "(position_uuid, channel_id) VALUES (:p, :c)")
                    .setParameter("p", partnerPositionUuid)
                    .setParameter("c", channelId)
                    .executeUpdate();

            // One candidate per queue category, sentinel-named (the DM may
            // carry the name — the event payload must not).
            P8ProfileFixtures.insertCandidate(em, art14CandidateUuid,
                    SENTINEL + "-Art14-" + marker, "Hansen", "ACTIVE", null, null, "test");
            em.createNativeQuery("UPDATE recruitment_candidates SET art14_required = 1, "
                            + "art14_deadline = :d, email = :e WHERE uuid = :u")
                    .setParameter("d", now.plusDays(2))
                    .setParameter("e", "p24.art14+" + marker + "@example.invalid")
                    .setParameter("u", art14CandidateUuid)
                    .executeUpdate();

            P8ProfileFixtures.insertCandidate(em, consentCandidateUuid,
                    SENTINEL + "-Consent-" + marker, "Jensen", "POOLED", "PROSPECT", null, "test");
            em.createNativeQuery("UPDATE recruitment_candidates SET retention_deadline = :d "
                            + "WHERE uuid = :u")
                    .setParameter("d", now.plusDays(20))
                    .setParameter("u", consentCandidateUuid)
                    .executeUpdate();

            P8ProfileFixtures.insertCandidate(em, dsarCandidateUuid,
                    SENTINEL + "-Dsar-" + marker, "Larsen", "ACTIVE", null, null, "test");
            P8ProfileFixtures.insertEvent(em, "DSAR_RECEIVED", dsarCandidateUuid,
                    null, null, "USER", dpoUserUuid, "NORMAL", "{}", null);

            P8ProfileFixtures.insertCandidate(em, upcomingCandidateUuid,
                    SENTINEL + "-Soon-" + marker, "Olsen", "ACTIVE", null, null, "test");
            em.createNativeQuery("UPDATE recruitment_candidates SET retention_deadline = :d "
                            + "WHERE uuid = :u")
                    .setParameter("d", now.plusDays(3))
                    .setParameter("u", upcomingCandidateUuid)
                    .executeUpdate();
        });

        // Unstubbed channels (none expected) list empty; the fixture channel
        // carries the circle member AND the intruder.
        when(slackService.listHumanChannelMembers(anyString())).thenReturn(List.of());
        when(slackService.listHumanChannelMembers(channelId))
                .thenReturn(List.of("U-P24C-" + marker, "U-P24I-" + marker));
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_slack_channels WHERE position_uuid = :p")
                    .setParameter("p", partnerPositionUuid).executeUpdate();
            deleteDigestEvents();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(art14CandidateUuid, consentCandidateUuid, dsarCandidateUuid,
                            upcomingCandidateUuid),
                    List.of(partnerPositionUuid),
                    List.of(dpoUserUuid, dpoUnlinkedUuid, circleUserUuid, intruderUserUuid),
                    practiceUuid);
            P8ProfileFixtures.restoreFlag(em, GDPR_FLAG, previousGdpr);
            P8ProfileFixtures.restoreFlag(em, DPO_DIGEST_FLAG, previousDigest);
        });
    }

    // =========================================================================
    // The digest itself
    // =========================================================================

    @Test
    void digest_dmsEveryLinkedDpoHolder_withAllCategoriesAndDrift() throws Exception {
        RecruitmentDpoDigestService.DpoDigestSummary summary = service.run();

        assertTrue(summary.enabled());
        assertTrue(summary.driftChecked());

        String rendered = renderedDigestFor(dpoUserUuid);
        assertTrue(rendered.contains(SENTINEL + "-Art14-" + marker),
                "Art. 14 due candidates must be listed");
        assertTrue(rendered.contains(SENTINEL + "-Consent-" + marker),
                "consents expiring must be listed");
        assertTrue(rendered.contains(SENTINEL + "-Dsar-" + marker),
                "open DSARs must be listed");
        assertTrue(rendered.contains(SENTINEL + "-Soon-" + marker),
                "upcoming automatic deletions must be listed");
        assertTrue(rendered.contains("Ivan Intruder" + marker),
                "the drift check must name the non-circle channel member");
        assertTrue(rendered.contains("Partner Search " + marker),
                "the drift entry must name the position");
        assertFalse(rendered.contains("Cilla Circle" + marker),
                "legitimate circle members are never flagged as drift");

        RecruitmentEvent event = digestEventFor(dpoUserUuid);
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
        Map<String, Object> payload = parse(event.getPayload());
        assertEquals(AiDigestService.isoWeekKey(LocalDate.now(AiDigestService.COPENHAGEN)),
                payload.get("digest_week"));
        assertTrue(((Number) payload.get("art14_due")).intValue() >= 1);
        assertTrue(((Number) payload.get("consents_expiring")).intValue() >= 1);
        assertTrue(((Number) payload.get("open_dsars")).intValue() >= 1);
        assertTrue(((Number) payload.get("upcoming_anonymizations")).intValue() >= 1);
        assertEquals(Boolean.TRUE, payload.get("drift_checked"));
        assertTrue(((Number) payload.get("drift_members")).intValue() >= 1);

        // The unlinked DPO holder is a visible skip: no DM attempt, no event.
        assertEquals(0, dmCountFor(dpoUnlinkedUuid));
        assertTrue(digestEventsFor(dpoUnlinkedUuid).isEmpty());
    }

    @Test
    void digest_idempotentPerRecipientPerWeek() throws Exception {
        service.run();
        service.run();

        assertEquals(1, dmCountFor(dpoUserUuid), "one digest DM per recipient per ISO week");
        assertEquals(1, digestEventsFor(dpoUserUuid).size());
    }

    @Test
    void slackFailure_rollsBackBookkeeping_nextRunRetries() throws Exception {
        doThrow(new java.io.IOException("Slack down"))
                .when(slackService).sendMessage(any(User.class), anyString(), anyList());
        RecruitmentDpoDigestService.DpoDigestSummary failed = service.run();
        assertTrue(failed.failures() >= 1);
        assertTrue(digestEventsFor(dpoUserUuid).isEmpty(),
                "a failed DM must roll its bookkeeping event back");

        reset(slackService);
        when(slackService.listHumanChannelMembers(anyString())).thenReturn(List.of());
        service.run();
        assertEquals(1, digestEventsFor(dpoUserUuid).size(), "the next run retries the missed DM");
    }

    @Test
    void driftCheckUnavailable_digestStillSent_andSaysSo() throws Exception {
        doThrow(new java.io.IOException("members lookup failed"))
                .when(slackService).listHumanChannelMembers(anyString());
        RecruitmentDpoDigestService.DpoDigestSummary summary = service.run();

        assertFalse(summary.driftChecked());
        String rendered = renderedDigestFor(dpoUserUuid);
        assertTrue(rendered.contains("could not run"),
                "the DM must state that memberships were not verified");
        Map<String, Object> payload = parse(digestEventFor(dpoUserUuid).getPayload());
        assertEquals(Boolean.FALSE, payload.get("drift_checked"));
    }

    @Test
    void flagsOff_inert() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, DPO_DIGEST_FLAG, "false"));
        assertFalse(service.run().enabled());

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, DPO_DIGEST_FLAG, "true");
            P8ProfileFixtures.setFlag(em, GDPR_FLAG, "false");
        });
        assertFalse(service.run().enabled(),
                "the digest rides the GDPR engine's flag, not the pipeline flag");
        verifyNoInteractions(slackService);
    }

    @Test
    void emptyWeek_saysNothingNeedsYou() {
        GdprQueueResponse emptyQueue = new GdprQueueResponse(true,
                new GdprQueueResponse.Kpis(0, 0, 0, 0),
                List.of(), List.of(), List.of(), List.of());
        RecruitmentDpoDigestService.DriftReport cleanDrift =
                new RecruitmentDpoDigestService.DriftReport(true, List.of());

        String text = service.digestText(emptyQueue, List.of(), cleanDrift, "2026-W30");
        assertTrue(text.contains("Nothing needs you this week"));

        List<LayoutBlock> blocks = service.digestBlocks(emptyQueue, List.of(), cleanDrift, "2026-W30");
        String rendered = renderBlocks(blocks);
        assertTrue(rendered.contains("Nothing needs you this week"),
                "an empty week still proves the engine runs");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void linkSlack(String userUuid, String slackId) {
        em.createNativeQuery("UPDATE user SET slackusername = :s WHERE uuid = :u")
                .setParameter("s", slackId).setParameter("u", userUuid).executeUpdate();
    }

    /** All digest DMs to the given fixture user, rendered to one string. */
    @SuppressWarnings("unchecked")
    private String renderedDigestFor(String userUuid) throws Exception {
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<List<LayoutBlock>> blocksCaptor =
                ArgumentCaptor.forClass((Class<List<LayoutBlock>>) (Class<?>) List.class);
        verify(slackService, org.mockito.Mockito.atLeastOnce())
                .sendMessage(userCaptor.capture(), anyString(), blocksCaptor.capture());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < userCaptor.getAllValues().size(); i++) {
            if (userUuid.equals(userCaptor.getAllValues().get(i).getUuid())) {
                sb.append(renderBlocks(blocksCaptor.getAllValues().get(i)));
            }
        }
        assertFalse(sb.isEmpty(), "expected a digest DM to fixture user " + userUuid);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private int dmCountFor(String userUuid) throws Exception {
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        try {
            verify(slackService, org.mockito.Mockito.atLeastOnce())
                    .sendMessage(userCaptor.capture(), anyString(), anyList());
        } catch (Throwable never) {
            return 0;
        }
        return (int) userCaptor.getAllValues().stream()
                .filter(u -> userUuid.equals(u.getUuid()))
                .count();
    }

    private static String renderBlocks(List<LayoutBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (LayoutBlock block : blocks) {
            if (block instanceof SectionBlock section
                    && section.getText() instanceof MarkdownTextObject text) {
                sb.append(text.getText()).append('\n');
            }
        }
        return sb.toString();
    }

    private RecruitmentEvent digestEventFor(String userUuid) {
        List<RecruitmentEvent> events = digestEventsFor(userUuid);
        assertEquals(1, events.size(), "exactly one DPO_DIGEST_SENT expected for " + userUuid);
        return events.get(0);
    }

    private List<RecruitmentEvent> digestEventsFor(String userUuid) {
        return QuarkusTransaction.requiringNew().call(() ->
                RecruitmentEvent.<RecruitmentEvent>list("eventType",
                                RecruitmentEventType.DPO_DIGEST_SENT).stream()
                        .filter(e -> {
                            Map<String, Object> payload = parse(e.getPayload());
                            return payload.get("nudged_user_uuids") instanceof List<?> list
                                    && list.contains(userUuid);
                        })
                        .toList());
    }

    private void deleteDigestEvents() {
        em.createNativeQuery("DELETE FROM recruitment_events "
                        + "WHERE event_type = 'DPO_DIGEST_SENT'")
                .executeUpdate();
    }

    private Map<String, Object> parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
        } catch (Exception e) {
            throw new AssertionError("payload is not valid JSON", e);
        }
    }
}
