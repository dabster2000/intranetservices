package dk.trustworks.intranet.recruitmentservice.notifications;

import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.HeaderBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
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
 * toggles, no separate switch), the blank-channel skip, unknown kinds,
 * and replay idempotency.
 */
@QuarkusTest
class SlackDigestRendererTest {

    private static final String PIPELINE_FLAG = P8ProfileFixtures.PIPELINE_FLAG;
    private static final String WEEKLY_FLAG = "recruitment.ai.digest.weekly-funnel.enabled";
    private static final String REJECTION_FLAG = "recruitment.ai.digest.rejection-patterns.enabled";
    private static final String DEFAULT_KEY = RecruitmentSlackChannelRouter.DEFAULT_CHANNEL_KEY;
    private static final String CHANNEL = "C-P24-DIGEST";

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

    @BeforeEach
    void seed() throws Exception {
        QuarkusTransaction.requiringNew().run(() -> {
            previousPipeline = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "false");
            previousWeekly = P8ProfileFixtures.setFlag(em, WEEKLY_FLAG, "false");
            previousRejection = P8ProfileFixtures.setFlag(em, REJECTION_FLAG, "false");
            previousDefault = P8ProfileFixtures.setFlag(em, DEFAULT_KEY, "");
        });
        // Drain any backlog with the flags OFF so each test's sweep only
        // reflects its own trigger events.
        reactor.catchUp();
        when(slackService.sendMessageReturningTs(anyString(), anyString(), any()))
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
        });
        reactor.catchUp();
    }

    // ---- helpers ---------------------------------------------------------------

    private void flagsOn(String kindFlag) {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
            P8ProfileFixtures.setFlag(em, kindFlag, "true");
            P8ProfileFixtures.setFlag(em, DEFAULT_KEY, CHANNEL);
        });
    }

    private long insertDigestEvent(String kind, String period, String narrative, String kpisJson) {
        String payload = """
                {"kind":"%s","period":"%s","window_from":"2026-04","window_to":"2026-07",\
                "model":"test-model","prompt_version":"digest-v1","narrative":"%s","kpis":%s}"""
                .formatted(kind, period, narrative, kpisJson);
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "AI_DIGEST_GENERATED", null, null, null,
                        "SCHEDULER", null, "NORMAL", payload, null));
    }

    @SuppressWarnings("unchecked")
    private List<LayoutBlock> postedBlocks() throws Exception {
        ArgumentCaptor<List<LayoutBlock>> captor =
                ArgumentCaptor.forClass((Class<List<LayoutBlock>>) (Class<?>) List.class);
        verify(slackService).sendMessageReturningTs(eq(CHANNEL), anyString(), captor.capture());
        return captor.getValue();
    }

    private static String headerText(List<LayoutBlock> blocks) {
        return ((HeaderBlock) blocks.get(0)).getText().getText();
    }

    private static String sectionText(LayoutBlock block) {
        return ((MarkdownTextObject) ((SectionBlock) block).getText()).getText();
    }

    private static String contextText(List<LayoutBlock> blocks) {
        ContextBlock context = (ContextBlock) blocks.get(blocks.size() - 1);
        return ((MarkdownTextObject) context.getElements().get(0)).getText();
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

        List<LayoutBlock> blocks = postedBlocks();
        assertEquals("Recruitment week in numbers · 2026-W30", headerText(blocks));
        assertEquals("Kort dansk fortælling om ugen.", sectionText(blocks.get(1)));
        SectionBlock kpiSection = (SectionBlock) blocks.get(2);
        List<String> fields = kpiSection.getFields().stream()
                .map(f -> ((MarkdownTextObject) f).getText())
                .toList();
        assertTrue(fields.contains("*Applications:* 12"), "KPI fields must match the payload");
        assertTrue(fields.contains("*Hires:* 1"));
        assertTrue(contextText(blocks).contains("/recruitment/reports|"),
                "the deep link must land on the reports page");
    }

    @Test
    void rejectionDigest_ridesItsOwnToggleAndHeader() throws Exception {
        flagsOn(REJECTION_FLAG); // weekly stays OFF — the kinds gate independently
        insertDigestEvent("REJECTION_PATTERNS", "FY2025/26-Q4", "Kvartalets mønstre.",
                "{\"rejections\":9,\"applications\":40}");
        reactor.catchUp();

        List<LayoutBlock> blocks = postedBlocks();
        assertEquals("Quarterly rejection patterns · FY2025/26-Q4", headerText(blocks));
        assertEquals("Kvartalets mønstre.", sectionText(blocks.get(1)));
    }

    @Test
    void longNarrative_clampedToTheSlackSectionLimit() throws Exception {
        flagsOn(WEEKLY_FLAG);
        insertDigestEvent("WEEKLY_FUNNEL", "2026-W31", "x".repeat(4500), "{}");
        reactor.catchUp();

        String narrative = sectionText(postedBlocks().get(1));
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
                .sendMessageReturningTs(anyString(), anyString(), any());
        assertTrue(reactor.watermark() >= seq, "the watermark must advance past the gated event");

        // Enabling later must not backfill — the event is already claimed.
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, WEEKLY_FLAG, "true"));
        reactor.catchUp();
        verify(slackService, org.mockito.Mockito.never())
                .sendMessageReturningTs(anyString(), anyString(), any());
    }

    @Test
    void blankDefaultChannel_visibleSkip_watermarkAdvances() throws Exception {
        flagsOn(WEEKLY_FLAG);
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, DEFAULT_KEY, ""));
        long seq = insertDigestEvent("WEEKLY_FUNNEL", "2026-W33", "Ingen kanal.", "{}");
        reactor.catchUp();

        verify(slackService, org.mockito.Mockito.never())
                .sendMessageReturningTs(anyString(), anyString(), any());
        assertTrue(reactor.watermark() >= seq);
    }

    @Test
    void unknownKind_neverPosts() throws Exception {
        flagsOn(WEEKLY_FLAG);
        long seq = insertDigestEvent("SOMETHING_ELSE", "x", "?", "{}");
        reactor.catchUp();

        verify(slackService, org.mockito.Mockito.never())
                .sendMessageReturningTs(anyString(), anyString(), any());
        assertTrue(reactor.watermark() >= seq);
    }

    @Test
    void replay_isIdempotent_onePostPerDigest() throws Exception {
        flagsOn(WEEKLY_FLAG);
        insertDigestEvent("WEEKLY_FUNNEL", "2026-W34", "Én gang.", "{}");
        reactor.catchUp();
        reactor.catchUp();

        verify(slackService, times(1)).sendMessageReturningTs(eq(CHANNEL), anyString(), any());
    }
}
