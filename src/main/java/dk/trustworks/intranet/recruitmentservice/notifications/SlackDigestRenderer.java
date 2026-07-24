package dk.trustworks.intranet.recruitmentservice.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.ai.AiDigestService;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactor;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * P24 digest delivery (plan §P24): consumes {@code AI_DIGEST_GENERATED}
 * and posts the digest to the recruitment Slack channel as rich Block Kit
 * — header, the Danish narrative (3 000-char clamped, the P12 Block Kit
 * limit), a KPI field grid and a deep link to {@code /recruitment/reports}.
 * <p>
 * Rides the AI digest flags — deliberately NO separate toggle (plan §P24):
 * each delivery re-reads {@code recruitment.pipeline.enabled} AND the
 * generating kind's own {@code recruitment.ai.digest.*} toggle, so
 * disabling a digest between generation and delivery suppresses the post
 * too (silent PROCESSED advance, the P22 gating model). Routing is the
 * P12 settings-routed default channel — digests are cross-practice, so
 * the per-practice overrides never apply; a blank default channel is a
 * visible INFO skip (the standing "routing off = notifications off" rule).
 * <p>
 * PII posture: the digest payload IS the message content — aggregates
 * only, by the {@code AiDigestFacts} construction — so this renderer
 * never touches candidate rows, event pii, or {@code SlackCandidateFacts}.
 */
@JBossLog
@ApplicationScoped
public class SlackDigestRenderer extends RecruitmentReactor {

    public static final String NAME = "slack-digest";

    /** Slack rejects any text object above this ({@code invalid_blocks}). */
    static final int SECTION_CLAMP = 3000;

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentAiFeatureFlag aiFlags;

    @Inject
    RecruitmentSlackChannelRouter router;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    @Override
    public String name() {
        return NAME;
    }

    /** The P12 posture: one live try + two catch-up retries, then durable SKIPPED. */
    @Override
    protected int maxDeliveryAttempts() {
        return 3;
    }

    @Override
    protected void handle(RecruitmentEvent event) throws Exception {
        if (event.getEventType() != RecruitmentEventType.AI_DIGEST_GENERATED) {
            return;
        }
        Map<String, Object> payload = parse(event.getPayload());
        String kind = str(payload.get("kind"));
        boolean kindEnabled = switch (kind) {
            case AiDigestService.KIND_WEEKLY_FUNNEL -> aiFlags.isWeeklyFunnelDigestEnabled();
            case AiDigestService.KIND_REJECTION_PATTERNS -> aiFlags.isRejectionPatternsDigestEnabled();
            default -> false; // unknown kind — nothing to render
        };
        if (!kindEnabled || !featureFlag.isPipelineEnabled()) {
            return; // silent PROCESSED advance — no backfill on later enable
        }
        Optional<String> channel = router.channelFor(null);
        if (channel.isEmpty()) {
            log.infof("Digest %s (%s): no default Slack channel configured — skipping post "
                    + "(configure it under Settings → Recruitment AI & Slack)",
                    kind, str(payload.get("period")));
            return;
        }
        String narrative = str(payload.get("narrative"));
        if (narrative.isEmpty()) {
            log.warnf("Digest event seq %d has no narrative — nothing to post", event.getSeq());
            return;
        }
        String header = headerFor(kind, str(payload.get("period")));
        List<com.slack.api.model.block.LayoutBlock> blocks =
                digestBlocks(header, narrative, kpisOf(payload));
        // Throwing post (not the best-effort channel variant): a failed
        // delivery must retry through the chassis, ≤3 attempts then SKIPPED.
        slackService.sendMessageReturningTs(channel.get(), clamp(header + " — " + narrative, 4000),
                blocks);
        log.infof("Digest %s (%s) posted to channel %s", kind, str(payload.get("period")),
                channel.get());
    }

    // ------------------------------------------------------------------
    // Block builders — digest payload facts only (aggregates by construction)
    // ------------------------------------------------------------------

    List<com.slack.api.model.block.LayoutBlock> digestBlocks(String header, String narrative,
                                                             Map<String, Object> kpis) {
        List<com.slack.api.model.block.LayoutBlock> blocks = new ArrayList<>();
        blocks.add(com.slack.api.model.block.Blocks.header(h -> h.text(
                com.slack.api.model.block.composition.BlockCompositions.plainText(
                        clamp(header, 150)))));
        blocks.add(com.slack.api.model.block.Blocks.section(s -> s.text(
                com.slack.api.model.block.composition.BlockCompositions.markdownText(
                        clamp(narrative, SECTION_CLAMP)))));
        if (!kpis.isEmpty()) {
            List<com.slack.api.model.block.composition.TextObject> fields = new ArrayList<>();
            kpis.forEach((key, value) -> {
                if (fields.size() < 10 && value instanceof Number number) {
                    fields.add(com.slack.api.model.block.composition.BlockCompositions.markdownText(
                            "*" + humanizeKpiKey(key) + ":* " + number.longValue()));
                }
            });
            if (!fields.isEmpty()) {
                blocks.add(com.slack.api.model.block.Blocks.section(s -> s.fields(fields)));
            }
        }
        blocks.add(com.slack.api.model.block.Blocks.context(
                com.slack.api.model.block.element.BlockElements.asContextElements(
                        com.slack.api.model.block.composition.BlockCompositions.markdownText(
                                "AI-generated from aggregate numbers only — details on the <"
                                        + baseUrl + "/recruitment/reports|reports page>"))));
        return blocks;
    }

    static String headerFor(String kind, String period) {
        String suffix = period.isEmpty() ? "" : " · " + period;
        return switch (kind) {
            case AiDigestService.KIND_WEEKLY_FUNNEL -> "Recruitment week in numbers" + suffix;
            case AiDigestService.KIND_REJECTION_PATTERNS -> "Quarterly rejection patterns" + suffix;
            default -> "Recruitment digest" + suffix;
        };
    }

    /** {@code open_positions} → {@code Open positions}. */
    static String humanizeKpiKey(String key) {
        String words = key.replace('_', ' ').trim();
        if (words.isEmpty()) {
            return key;
        }
        return Character.toUpperCase(words.charAt(0)) + words.substring(1).toLowerCase(Locale.ROOT);
    }

    static String clamp(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1).stripTrailing() + "…";
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> kpisOf(Map<String, Object> payload) {
        if (payload.get("kpis") instanceof Map<?, ?> raw) {
            Map<String, Object> kpis = new java.util.LinkedHashMap<>();
            raw.forEach((k, v) -> kpis.put(String.valueOf(k), v));
            return kpis;
        }
        return Map.of();
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }
}
