package dk.trustworks.intranet.recruitmentservice.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P24 §DoD — the digest renderer against the real chassis (raw-inserted
 * {@code AI_DIGEST_GENERATED} events + deterministic {@code catchUp()}
 * sweeps) with a mocked Slack transport: Block Kit shape (header,
 * narrative, KPI fields matching the payload, reports deep link), the
 * 3 000-char narrative clamp, flag gating per digest kind (rides the AI
 * toggles, no separate switch), unknown kinds, replay idempotency, and the
 * 2026-08-12 routing split: the company-wide edition goes to the HR
 * channel, an edition carrying {@code practice_uuid} to that practice's own
 * channel, and a practice whose channel has since been removed is skipped
 * rather than spilled into a shared channel.
 */
@QuarkusTest
class SlackDigestRendererTest {

    private static final String PIPELINE_FLAG = P8ProfileFixtures.PIPELINE_FLAG;
    private static final String WEEKLY_FLAG = "recruitment.ai.digest.weekly-funnel.enabled";
    private static final String REJECTION_FLAG = "recruitment.ai.digest.rejection-patterns.enabled";
    private static final String DEFAULT_KEY = RecruitmentSlackChannelRouter.DEFAULT_CHANNEL_KEY;
    private static final String PRACTICE_UUID = "11111111-2222-3333-4444-555555555555";
    private static final String PRACTICE_KEY =
            RecruitmentSlackChannelRouter.PRACTICE_CHANNEL_KEY_PREFIX + PRACTICE_UUID;
    private static final String PRACTICE_CHANNEL = "C-P24-PRACTICE";
    /** The shared default is deliberately NOT where digests land any more. */
    private static final String DEFAULT_CHANNEL = "C-P24-DIGEST";

    /** The company-wide digest's home — config, so the test cannot drift from it. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "recruitment.hr.slack.channel-id", defaultValue = "C0B1XUB3AEB")
    String hrChannel;

    @Inject
    EntityManager em;

    @Inject
    SlackDigestRenderer reactor;

    @InjectMock
    SlackService slackService;

    private String previousPipeline;
    private String previousWeekly;
    private String previousRejection;
    private String previousDefault;
    private String previousPractice;

    @BeforeEach
    void seed() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> {
            previousPipeline = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "false");
            previousWeekly = P8ProfileFixtures.setFlag(em, WEEKLY_FLAG, "false");
            previousRejection = P8ProfileFixtures.setFlag(em, REJECTION_FLAG, "false");
            previousDefault = P8ProfileFixtures.setFlag(em, DEFAULT_KEY, "");
            previousPractice = P8ProfileFixtures.setFlag(em, PRACTICE_KEY, "");
        });
        // Drain any backlog with the flags OFF so each test's sweep only
        // reflects its own trigger events.
        reactor.catchUp();
        when(slackService.sendMessageWithRawBlocksReturningTs(anyString(), anyString(), anyString()))
                .thenReturn("1700000000.000200");
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_events "
                            + "WHERE event_type = 'AI_DIGEST_GENERATED'")
                    .executeUpdate();
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousPipeline);
            P8ProfileFixtures.restoreFlag(em, WEEKLY_FLAG, previousWeekly);
            P8ProfileFixtures.restoreFlag(em, REJECTION_FLAG, previousRejection);
            P8ProfileFixtures.restoreFlag(em, DEFAULT_KEY, previousDefault);
            P8ProfileFixtures.restoreFlag(em, PRACTICE_KEY, previousPractice);
        });
        reactor.catchUp();
    }

    // ---- helpers ---------------------------------------------------------------

    private void flagsOn(String kindFlag) {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
            P8ProfileFixtures.setFlag(em, kindFlag, "true");
            P8ProfileFixtures.setFlag(em, DEFAULT_KEY, DEFAULT_CHANNEL);
        });
    }

    private long insertDigestEvent(String kind, String period, String narrative, String kpisJson) {
        return insertDigestEvent(kind, period, narrative, kpisJson, null);
    }

    /** {@code practiceUuid} non-null ⇒ a practice edition, as the generator writes it. */
    private long insertDigestEvent(String kind, String period, String narrative, String kpisJson,
                                   String practiceUuid) {
        String practice = practiceUuid == null ? ""
                : "\"practice_uuid\":\"" + practiceUuid + "\",";
        String payload = """
                {"kind":"%s","period":"%s",%s"window_from":"2026-04","window_to":"2026-07",\
                "week_from":"2026-07-20","week_to":"2026-07-26",\
                "model":"test-model","prompt_version":"digest-v2","narrative":"%s","kpis":%s}"""
                .formatted(kind, period, practice, narrative, kpisJson);
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "AI_DIGEST_GENERATED", null, null, null,
                        "SCHEDULER", null, "NORMAL", payload, null));
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The renderer posts raw JSON (2026 block types the Java SDK does not
     * model), so the assertion surface is the parsed payload rather than
     * typed {@code LayoutBlock}s. {@code SlackDigestBlocksTest} covers the
     * shape in the fast tier; this test covers the delivery path.
     */
    private JsonNode postedBlocks() throws Exception {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendMessageWithRawBlocksReturningTs(
                eq(hrChannel), anyString(), captor.capture());
        return JSON.readTree(captor.getValue());
    }

    private static List<String> typesOf(JsonNode blocks) {
        List<String> types = new ArrayList<>();
        blocks.forEach(b -> types.add(b.path("type").asText()));
        return types;
    }

    private static JsonNode firstOfType(JsonNode blocks, String type) {
        for (JsonNode block : blocks) {
            if (type.equals(block.path("type").asText())) {
                return block;
            }
        }
        throw new AssertionError("no '" + type + "' block in " + typesOf(blocks));
    }

    private static String headerText(JsonNode blocks) {
        return blocks.get(0).path("text").path("text").asText();
    }

    /** The AI paragraph now lives inside the collapsed details container. */
    private static String narrativeText(JsonNode blocks) {
        JsonNode container = firstOfType(blocks, "container");
        return container.path("child_blocks").get(0).path("text").path("text").asText();
    }

    private static String contextText(JsonNode blocks) {
        return firstOfType(blocks, "context").path("elements").get(0).path("text").asText();
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Test
    void weeklyDigest_postsHeaderNarrativeKpisAndDeepLink() throws Exception {
        flagsOn(WEEKLY_FLAG);
        insertDigestEvent("WEEKLY_FUNNEL", "2026-W30", "Kort dansk fortælling om ugen.",
                "{\"applications\":12,\"hires\":1}");
        reactor.catchUp();

        JsonNode blocks = postedBlocks();
        assertEquals("📈 Rekruttering · uge 30 · hele huset", headerText(blocks));
        assertEquals("Kort dansk fortælling om ugen.", narrativeText(blocks));

        String headline = firstOfType(blocks, "markdown").path("text").asText();
        assertTrue(headline.contains("**1 ansættelse**"), headline);
        assertTrue(headline.contains("**12 ansøgninger**"), headline);
        assertTrue(headline.contains("uge 30"), "the reported period is the week: " + headline);

        assertEquals("https://intra.trustworks.dk/recruitment/reports",
                firstOfType(blocks, "actions").path("elements").get(0).path("url").asText(),
                "the deep link must land on the reports page");
        assertTrue(contextText(blocks).contains("uge 30"), contextText(blocks));
    }

    @Test
    void rejectionDigest_ridesItsOwnToggleAndHeader() throws Exception {
        flagsOn(REJECTION_FLAG); // weekly stays OFF — the kinds gate independently
        insertDigestEvent("REJECTION_PATTERNS", "FY2025/26-Q4", "Kvartalets mønstre.",
                "{\"rejections\":9,\"applications\":40}");
        reactor.catchUp();

        JsonNode blocks = postedBlocks();
        assertEquals("📉 Afslagsmønstre · FY2025/26-Q4 · hele huset", headerText(blocks));
        // The quarterly digest is read as prose, so it keeps the plain layout.
        assertEquals(List.of("header", "section", "context"), typesOf(blocks));
        assertEquals("Kvartalets mønstre.",
                blocks.get(1).path("text").path("text").asText());
    }

    @Test
    void longNarrative_clampedToTheSlackSectionLimit() throws Exception {
        flagsOn(WEEKLY_FLAG);
        insertDigestEvent("WEEKLY_FUNNEL", "2026-W31", "x".repeat(4500), "{}");
        reactor.catchUp();

        String narrative = narrativeText(postedBlocks());
        assertTrue(narrative.length() <= SlackDigestRenderer.SECTION_CLAMP,
                "narrative section must respect the 3000-char Block Kit limit");
        assertTrue(narrative.endsWith("…"));
    }

    // =========================================================================
    // Gating & degradation
    // =========================================================================

    @Test
    void kindToggleOff_silentAdvance_noBackfillOnLaterEnable() throws Exception {
        flagsOn(WEEKLY_FLAG);
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, WEEKLY_FLAG, "false"));
        long seq = insertDigestEvent("WEEKLY_FUNNEL", "2026-W32", "Aldrig postet.", "{}");
        reactor.catchUp();

        verify(slackService, org.mockito.Mockito.never())
                .sendMessageWithRawBlocksReturningTs(anyString(), anyString(), anyString());
        assertTrue(reactor.watermark() >= seq, "the watermark must advance past the gated event");

        // Enabling later must not backfill — the event is already claimed.
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, WEEKLY_FLAG, "true"));
        reactor.catchUp();
        verify(slackService, org.mockito.Mockito.never())
                .sendMessageWithRawBlocksReturningTs(anyString(), anyString(), anyString());
    }

    /**
     * The company-wide edition is a whole-company read and belongs in the HR
     * channel — emphatically NOT the shared recruitment channel it used to
     * go to, and not silenced by that channel being blank.
     */
    @Test
    void companyWideDigest_goesToTheHrChannel_notTheSharedDefault() throws Exception {
        flagsOn(WEEKLY_FLAG);
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, DEFAULT_KEY, ""));
        long seq = insertDigestEvent("WEEKLY_FUNNEL", "2026-W33", "Hele huset.", "{}");
        reactor.catchUp();

        verify(slackService).sendMessageWithRawBlocksReturningTs(eq(hrChannel), anyString(), anyString());
        verify(slackService, org.mockito.Mockito.never())
                .sendMessageWithRawBlocksReturningTs(eq(DEFAULT_CHANNEL), anyString(), anyString());
        assertTrue(reactor.watermark() >= seq);
    }

    /** A practice edition goes to that practice's channel, named in the header. */
    @Test
    void practiceDigest_goesToThatPracticesChannel() throws Exception {
        flagsOn(WEEKLY_FLAG);
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, PRACTICE_KEY, PRACTICE_CHANNEL));
        long seq = insertDigestEvent("WEEKLY_FUNNEL", "2026-W35", "Kun denne praksis.", "{}",
                PRACTICE_UUID);
        reactor.catchUp();

        verify(slackService).sendMessageWithRawBlocksReturningTs(eq(PRACTICE_CHANNEL), anyString(), anyString());
        verify(slackService, org.mockito.Mockito.never())
                .sendMessageWithRawBlocksReturningTs(eq(hrChannel), anyString(), anyString());
        assertTrue(reactor.watermark() >= seq);
    }

    /**
     * A practice edition whose channel has since been removed is dropped, not
     * redirected: a per-practice funnel in a shared channel is exactly the
     * noise the split was meant to remove.
     */
    @Test
    void practiceDigest_withoutItsOwnChannel_isSkipped_notSpilledIntoAShared() throws Exception {
        flagsOn(WEEKLY_FLAG); // default channel IS configured — still no fallback
        long seq = insertDigestEvent("WEEKLY_FUNNEL", "2026-W36", "Ingen kanal mere.", "{}",
                PRACTICE_UUID);
        reactor.catchUp();

        verify(slackService, org.mockito.Mockito.never())
                .sendMessageWithRawBlocksReturningTs(anyString(), anyString(), anyString());
        assertTrue(reactor.watermark() >= seq);
    }

    @Test
    void unknownKind_neverPosts() throws Exception {
        flagsOn(WEEKLY_FLAG);
        long seq = insertDigestEvent("SOMETHING_ELSE", "x", "?", "{}");
        reactor.catchUp();

        verify(slackService, org.mockito.Mockito.never())
                .sendMessageWithRawBlocksReturningTs(anyString(), anyString(), anyString());
        assertTrue(reactor.watermark() >= seq);
    }

    @Test
    void replay_isIdempotent_onePostPerDigest() throws Exception {
        flagsOn(WEEKLY_FLAG);
        insertDigestEvent("WEEKLY_FUNNEL", "2026-W34", "Én gang.", "{}");
        reactor.catchUp();
        reactor.catchUp();

        verify(slackService, times(1)).sendMessageWithRawBlocksReturningTs(eq(hrChannel), anyString(), anyString());
    }
}
