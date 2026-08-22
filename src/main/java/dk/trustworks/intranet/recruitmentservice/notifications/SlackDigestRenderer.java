package dk.trustworks.intranet.recruitmentservice.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * P24 digest delivery (plan §P24): consumes {@code AI_DIGEST_GENERATED}
 * and posts the digest to the recruitment Slack channel as rich Block Kit.
 *
 * <h3>Layout (redesigned 2026-08-22)</h3>
 * The funnel digest renders as: {@code header} → {@code markdown} headline
 * → {@code table} of stage movement → {@code data_visualization} trend
 * chart → collapsed {@code container} holding the AI paragraph and the
 * secondary breakdowns → {@code actions} → {@code context} →
 * {@code context_actions} feedback buttons. Compact by default, complete
 * on demand.
 * <p>
 * <b>Every number comes from the event payload</b>, computed once by
 * {@code AiDigestService}. The renderer does no arithmetic of its own and
 * the model is forbidden from writing figures at all, so the message
 * cannot contradict itself the way the previous version did — its narrative
 * counted the newest month while its KPI grid summed four, printing
 * "8 nye ansøgninger" directly above "Applications: 9".
 *
 * <h3>Why raw JSON instead of the typed builder</h3>
 * {@code table}, {@code container} and {@code data_visualization} are
 * 2026 Block Kit types that exist in no released {@code slack-api-model}
 * (issue #1499 is open), so blocks are built as JSON and posted through
 * {@code SlackService.sendMessageReturningTs(channel, text, String)},
 * which wraps {@code blocksAsString} — Slack's own recommended
 * workaround. {@code SlackDigestRendererTest} asserts the payload against
 * the documented block limits, because nothing type-checks it here.
 *
 * <h3>Gating</h3>
 * Rides the AI digest flags — deliberately NO separate toggle (plan
 * §P24): each delivery re-reads {@code recruitment.pipeline.enabled} AND
 * the generating kind's own {@code recruitment.ai.digest.*} toggle, so
 * disabling a digest between generation and delivery suppresses the post
 * too (silent PROCESSED advance, the P22 gating model).
 *
 * <h3>Routing</h3>
 * (decided 2026-08-12) the company-wide edition goes to the <b>HR
 * channel</b> — it is a whole-company read and belongs where HR works, not
 * in the shared recruitment channel. An edition carrying
 * {@code payload.practice_uuid} is that practice's own funnel and goes to
 * that practice's channel; if the channel has since been removed the post
 * is skipped rather than spilled into a shared channel.
 *
 * <h3>PII posture</h3>
 * The digest payload IS the message content — aggregates only, by the
 * {@code AiDigestFacts} construction — so this renderer never touches
 * candidate rows, event pii, or {@code SlackCandidateFacts}.
 */
@JBossLog
@ApplicationScoped
public class SlackDigestRenderer extends RecruitmentReactor {

    public static final String NAME = "slack-digest";

    /** Slack rejects any text object above this ({@code invalid_blocks}). */
    static final int SECTION_CLAMP = 3000;

    /** Cumulative cap across every {@code markdown} block in one message. */
    static final int MARKDOWN_CLAMP = 12000;

    /** A {@code container} accepts at most this many child blocks. */
    static final int CONTAINER_MAX_CHILDREN = 10;

    /** Table caps: rows per table, and characters across all tables. */
    static final int TABLE_MAX_ROWS = 100;
    static final int TABLE_MAX_CHARS = 10000;

    /** Chart caps: charts per message, and data points per series. */
    static final int MAX_CHARTS = 2;
    static final int CHART_MAX_POINTS = 20;

    /** Slack truncates the notification preview; keep it to the headline. */
    static final int FALLBACK_CLAMP = 250;

    private static final String[] DANISH_MONTHS = {
            "januar", "februar", "marts", "april", "maj", "juni",
            "juli", "august", "september", "oktober", "november", "december"};

    private static final String[] DANISH_MONTHS_SHORT = {
            "jan", "feb", "mar", "apr", "maj", "jun",
            "jul", "aug", "sep", "okt", "nov", "dec"};

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

    /** Home of the company-wide digest — the same channel the HR notices use. */
    @ConfigProperty(name = "recruitment.hr.slack.channel-id", defaultValue = "C0B1XUB3AEB")
    String hrChannelId;

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
        String practiceUuid = str(payload.get("practice_uuid"));
        Optional<String> channel = practiceUuid.isEmpty()
                ? Optional.of(hrChannelId)
                : router.practiceChannel(practiceUuid);
        if (channel.isEmpty()) {
            log.infof("Digest %s (%s) for practice %s: that practice no longer has its own Slack "
                    + "channel — skipping rather than posting a practice digest to a shared "
                    + "channel (configure it under Settings → Recruitment AI & Slack)",
                    kind, str(payload.get("period")), practiceUuid);
            return;
        }
        String narrative = str(payload.get("narrative"));
        if (narrative.isEmpty()) {
            log.warnf("Digest event seq %d has no narrative — nothing to post", event.getSeq());
            return;
        }
        String practiceName = practiceName(practiceUuid);
        String header = headerFor(kind, str(payload.get("period")), practiceName);
        String blocksJson = objectMapper.writeValueAsString(digestBlocks(kind, header, narrative,
                payload));
        // Throwing post (not the best-effort channel variant): a failed
        // delivery must retry through the chassis, ≤3 attempts then SKIPPED.
        slackService.sendMessageWithRawBlocksReturningTs(channel.get(),
                fallbackText(kind, header, narrative, payload), blocksJson);
        log.infof("Digest %s (%s) posted to channel %s", kind, str(payload.get("period")),
                channel.get());
    }

    // ------------------------------------------------------------------
    // Block builders — digest payload facts only (aggregates by construction)
    // ------------------------------------------------------------------

    /**
     * The message's block array. Weekly funnel digests get the full
     * layout; any other kind falls back to header + narrative + context,
     * which is what the quarterly rejection digest wants anyway (it is
     * read as prose, not beside a table).
     */
    ArrayNode digestBlocks(String kind, String header, String narrative,
                           Map<String, Object> payload) {
        ArrayNode blocks = objectMapper.createArrayNode();
        blocks.add(headerBlock(header));

        if (!AiDigestService.KIND_WEEKLY_FUNNEL.equals(kind)) {
            blocks.add(sectionMrkdwn(clamp(narrative, SECTION_CLAMP)));
            blocks.add(contextBlock(provenance(payload)));
            return blocks;
        }

        Map<String, Object> kpis = mapOf(payload.get("kpis"));
        blocks.add(markdownBlock(clamp(headline(payload, kpis), MARKDOWN_CLAMP)));

        ObjectNode funnel = funnelTable(listOf(payload.get("funnel")));
        if (funnel != null) {
            blocks.add(funnel);
        }
        ObjectNode chart = trendChart(listOf(payload.get("trend")));
        if (chart != null) {
            blocks.add(chart);
        }
        blocks.add(detailsContainer(narrative, payload));
        blocks.add(actionsBlock(kpis));
        blocks.add(contextBlock(provenance(payload)));
        blocks.add(feedbackBlock());
        return blocks;
    }

    // --- individual blocks -------------------------------------------

    private ObjectNode headerBlock(String header) {
        ObjectNode block = objectMapper.createObjectNode().put("type", "header");
        block.putObject("text")
                .put("type", "plain_text")
                .put("text", clamp(header, 150))
                .put("emoji", true);
        return block;
    }

    private ObjectNode markdownBlock(String markdown) {
        return objectMapper.createObjectNode().put("type", "markdown").put("text", markdown);
    }

    private ObjectNode sectionMrkdwn(String text) {
        ObjectNode block = objectMapper.createObjectNode().put("type", "section");
        block.putObject("text").put("type", "mrkdwn").put("text", text);
        return block;
    }

    private ObjectNode contextBlock(String mrkdwn) {
        ObjectNode block = objectMapper.createObjectNode().put("type", "context");
        ObjectNode element = block.putArray("elements").addObject();
        element.put("type", "mrkdwn").put("text", clamp(mrkdwn, SECTION_CLAMP));
        return block;
    }

    /**
     * The headline: the two numbers that matter, their week-over-week
     * movement, and — only when there is one — the week's single open
     * action. Written from the payload's own figures, never recomputed.
     */
    String headline(Map<String, Object> payload, Map<String, Object> kpis) {
        long hires = num(kpis.get("hires"));
        long applications = num(kpis.get("applications"));
        long nudges = num(kpis.get("nudges"));
        long moves = num(kpis.get("stage_moves"));
        long terminals = num(kpis.get("terminals"));

        StringBuilder sb = new StringBuilder(320);
        sb.append("**").append(hires).append(' ')
                .append(plural(hires, "ansættelse", "ansættelser")).append("**")
                .append(delta(num(kpis.get("hires_delta"))))
                .append(" og **").append(applications).append(' ')
                .append(plural(applications, "ansøgning", "ansøgninger")).append("**")
                .append(delta(num(kpis.get("applications_delta"))))
                .append(" i ").append(weekLabel(payload)).append('.');

        if (nudges > 0) {
            sb.append("\n\n⚠️ **").append(nudges).append(' ')
                    .append(plural(nudges, "SLA-påmindelse", "SLA-påmindelser"))
                    .append("** sendt i ugen — de venter stadig på svar.");
        } else if (applications == 0 && hires == 0 && moves == 0 && terminals == 0) {
            sb.append("\n\nIngen registreret bevægelse i tragten i denne uge.");
        }
        return sb.toString();
    }

    /**
     * The funnel as a real table — the layout that makes the old digest's
     * enum leak structurally impossible, because stage names arrive
     * already translated in {@code payload.funnel} and never pass through
     * a language model.
     *
     * @return null when the week saw no stage movement (a table with only
     *         a header row is noise)
     */
    ObjectNode funnelTable(List<Object> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        ObjectNode table = objectMapper.createObjectNode().put("type", "table");
        ArrayNode settings = table.putArray("column_settings");
        settings.addObject().put("is_wrapped", false);
        settings.addObject().put("align", "right");
        settings.addObject().put("align", "right");
        settings.addObject().put("align", "right");

        ArrayNode out = table.putArray("rows");
        addRow(out, "Trin", "Videre", "Gns. dage", "Δ uge");
        // -1 for the header row we just added.
        for (Object raw : rows.subList(0, Math.min(rows.size(), TABLE_MAX_ROWS - 1))) {
            Map<String, Object> row = mapOf(raw);
            Object avg = row.get("avg_days");
            addRow(out,
                    str(row.get("from")) + " → " + str(row.get("to")),
                    String.valueOf(num(row.get("count"))),
                    avg == null ? "—" : decimal(avg),
                    delta(num(row.get("delta"))).trim());
        }
        return table;
    }

    /**
     * Applications per month — the trend the week numbers sit inside.
     * Kept to one chart; Slack allows {@value #MAX_CHARTS} per message and
     * a second would just compete with the table for attention.
     *
     * @return null when there is nothing to plot
     */
    ObjectNode trendChart(List<Object> trend) {
        if (trend.isEmpty()) {
            return null;
        }
        List<Object> points = trend.size() > CHART_MAX_POINTS
                ? trend.subList(trend.size() - CHART_MAX_POINTS, trend.size())
                : trend;

        ObjectNode block = objectMapper.createObjectNode()
                .put("type", "data_visualization")
                .put("title", "Ansøgninger pr. måned");
        ObjectNode chart = block.putObject("chart").put("type", "bar");
        ObjectNode series = chart.putArray("series").addObject().put("name", "Ansøgninger");
        ArrayNode data = series.putArray("data");
        ArrayNode categories = objectMapper.createArrayNode();
        for (Object raw : points) {
            Map<String, Object> point = mapOf(raw);
            String label = shortMonth(str(point.get("month")));
            data.addObject().put("label", label).put("value", num(point.get("count")));
            categories.add(label);
        }
        ObjectNode axis = chart.putObject("axis_config");
        axis.set("categories", categories);
        axis.put("x_label", "Måned").put("y_label", "Antal");
        return block;
    }

    /**
     * Everything a reader only sometimes wants, folded away: the AI
     * paragraph, the source breakdown and the open-position split. This is
     * the answer to "the digest is too dense" that does not delete
     * anything — it is one click, not one scroll.
     */
    ObjectNode detailsContainer(String narrative, Map<String, Object> payload) {
        ObjectNode container = objectMapper.createObjectNode().put("type", "container")
                .put("block_id", "digest_details")
                .put("is_collapsible", true)
                .put("default_collapsed", true);
        container.putObject("title").put("type", "plain_text").put("text", "Detaljer og AI-vurdering");
        container.putObject("subtitle").put("type", "plain_text")
                .put("text", "Kilder, åbne stillinger og den fulde tekst");

        ArrayNode children = container.putArray("child_blocks");
        children.add(sectionMrkdwn(clamp(narrative, SECTION_CLAMP)));

        ObjectNode sources = labelledTable(listOf(payload.get("sources")), "Kilde", "Ansøgninger");
        if (sources != null && children.size() < CONTAINER_MAX_CHILDREN - 1) {
            children.add(objectMapper.createObjectNode().put("type", "divider"));
            children.add(sources);
        }
        String open = openPositions(listOf(payload.get("open_positions_by_track")));
        if (!open.isEmpty() && children.size() < CONTAINER_MAX_CHILDREN) {
            children.add(contextBlock(open));
        }
        return container;
    }

    /** A two-column {@code label → count} table, or null when empty. */
    ObjectNode labelledTable(List<Object> rows, String labelHeader, String countHeader) {
        if (rows.isEmpty()) {
            return null;
        }
        ObjectNode table = objectMapper.createObjectNode().put("type", "table");
        ArrayNode settings = table.putArray("column_settings");
        settings.addObject().put("is_wrapped", false);
        settings.addObject().put("align", "right");

        ArrayNode out = table.putArray("rows");
        addRow(out, labelHeader, countHeader);
        for (Object raw : rows.subList(0, Math.min(rows.size(), TABLE_MAX_ROWS - 1))) {
            Map<String, Object> row = mapOf(raw);
            addRow(out, str(row.get("label")), String.valueOf(num(row.get("count"))));
        }
        return table;
    }

    private ObjectNode actionsBlock(Map<String, Object> kpis) {
        ObjectNode block = objectMapper.createObjectNode().put("type", "actions");
        ArrayNode elements = block.putArray("elements");
        elements.add(linkButton("Åbn rapporten", baseUrl + "/recruitment/reports",
                "digest_open_reports", true));
        if (num(kpis.get("nudges")) > 0) {
            elements.add(linkButton("Se ubesvarede scorecards",
                    baseUrl + "/recruitment/reports?filter=scorecards_missing",
                    "digest_open_scorecards", false));
        }
        return block;
    }

    private ObjectNode linkButton(String label, String url, String actionId, boolean primary) {
        ObjectNode button = objectMapper.createObjectNode().put("type", "button");
        button.putObject("text").put("type", "plain_text").put("text", label).put("emoji", true);
        button.put("url", url).put("action_id", actionId);
        if (primary) {
            button.put("style", "primary");
        }
        return button;
    }

    /**
     * 👍/👎 on the AI paragraph. The cheapest available signal about
     * whether the narrative earns its place at all.
     */
    private ObjectNode feedbackBlock() {
        ObjectNode block = objectMapper.createObjectNode().put("type", "context_actions");
        ObjectNode buttons = block.putArray("elements").addObject()
                .put("type", "feedback_buttons")
                .put("action_id", "digest_feedback");
        ObjectNode positive = buttons.putObject("positive_button").put("value", "digest_useful");
        positive.putObject("text").put("type", "plain_text").put("text", "👍");
        ObjectNode negative = buttons.putObject("negative_button").put("value", "digest_not_useful");
        negative.putObject("text").put("type", "plain_text").put("text", "👎");
        return block;
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /**
     * The notification preview — what a phone shows. Previously this was
     * the header plus the entire 3 000-character narrative, so the push
     * opened with an essay; now it is the same headline the message leads
     * with, stripped of markdown.
     */
    String fallbackText(String kind, String header, String narrative, Map<String, Object> payload) {
        if (!AiDigestService.KIND_WEEKLY_FUNNEL.equals(kind)) {
            return clamp(header + " — " + narrative, FALLBACK_CLAMP);
        }
        Map<String, Object> kpis = mapOf(payload.get("kpis"));
        String plain = headline(payload, kpis)
                .replace("**", "")
                .replace("\n\n", " ")
                .replace("\n", " ");
        return clamp(header + " — " + plain, FALLBACK_CLAMP);
    }

    /** The Danish provenance line: what period, from what, by whom. */
    String provenance(Map<String, Object> payload) {
        String from = str(payload.get("window_from"));
        String to = str(payload.get("window_to"));
        StringBuilder sb = new StringBuilder(220);
        sb.append("Tal for ").append(weekLabel(payload)).append('.');
        if (!from.isEmpty() && !to.isEmpty()) {
            sb.append(" Diagrammet viser månederne ").append(from).append('–').append(to)
                    .append(" (sidste måned er måned til dato).");
        }
        sb.append(" AI-vurdering skrevet ud fra aggregerede tal, ingen persondata.");
        return sb.toString();
    }

    /** {@code uge 34 (17.–23. august 2026)}, falling back to the raw key. */
    String weekLabel(Map<String, Object> payload) {
        String period = str(payload.get("period"));
        String weekNumber = period.contains("-W")
                ? period.substring(period.indexOf("-W") + 2).replaceFirst("^0", "")
                : period;
        String range = dateRange(str(payload.get("week_from")), str(payload.get("week_to")));
        if (weekNumber.isEmpty()) {
            return range.isEmpty() ? "perioden" : range;
        }
        return range.isEmpty() ? "uge " + weekNumber : "uge " + weekNumber + " (" + range + ")";
    }

    /** {@code 17.–23. august 2026} or {@code 29. juni – 5. juli 2026}. */
    static String dateRange(String fromIso, String toIso) {
        LocalDate from = parseDate(fromIso);
        LocalDate to = parseDate(toIso);
        if (from == null || to == null) {
            return "";
        }
        String month = DANISH_MONTHS[to.getMonthValue() - 1];
        if (from.getMonthValue() == to.getMonthValue() && from.getYear() == to.getYear()) {
            return from.getDayOfMonth() + ".–" + to.getDayOfMonth() + ". " + month + " "
                    + to.getYear();
        }
        return from.getDayOfMonth() + ". " + DANISH_MONTHS[from.getMonthValue() - 1]
                + " – " + to.getDayOfMonth() + ". " + month + " " + to.getYear();
    }

    /** {@code 2026-08} → {@code aug}; anything unparseable passes through. */
    static String shortMonth(String yearMonth) {
        if (yearMonth == null || yearMonth.length() < 7) {
            return str(yearMonth);
        }
        try {
            int month = Integer.parseInt(yearMonth.substring(5, 7));
            if (month >= 1 && month <= 12) {
                return DANISH_MONTHS_SHORT[month - 1];
            }
        } catch (NumberFormatException ignored) {
            // fall through — an odd label beats a failed render
        }
        return yearMonth;
    }

    /** {@code " ▲ +2"} / {@code " ▼ −2"} / {@code ""} when flat. */
    static String delta(long value) {
        if (value > 0) {
            return " ▲ +" + value;
        }
        if (value < 0) {
            return " ▼ −" + Math.abs(value);
        }
        return "";
    }

    static String plural(long count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

    /** Danish decimal comma, one place — {@code 4.25} → {@code 4,3}. */
    static String decimal(Object value) {
        if (value instanceof Number number) {
            return String.format(java.util.Locale.ROOT, "%.1f", number.doubleValue())
                    .replace('.', ',');
        }
        return str(value);
    }

    /** {@code 🪧 Åbne stillinger: 11 i praksisteam · 1 partner}, or "". */
    String openPositions(List<Object> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("🪧 *Åbne stillinger:* ");
        boolean first = true;
        for (Object raw : rows) {
            Map<String, Object> row = mapOf(raw);
            if (!first) {
                sb.append(" · ");
            }
            sb.append(num(row.get("count"))).append(' ').append(str(row.get("label")));
            first = false;
        }
        return sb.toString();
    }

    static String headerFor(String kind, String period, String practiceName) {
        String scope = practiceName.isEmpty() ? "hele huset" : practiceName;
        String week = period != null && period.contains("-W")
                ? "uge " + period.substring(period.indexOf("-W") + 2).replaceFirst("^0", "")
                : period;
        return switch (kind) {
            case AiDigestService.KIND_WEEKLY_FUNNEL ->
                    "📈 Rekruttering · " + week + " · " + scope;
            case AiDigestService.KIND_REJECTION_PATTERNS ->
                    "📉 Afslagsmønstre · " + period + " · " + scope;
            default -> "Rekrutteringsstatus · " + scope;
        };
    }

    /**
     * The practice's display name for the header, so a reader can tell a
     * practice edition from the company-wide one at a glance. Empty for the
     * company-wide digest and for a practice row that has since disappeared.
     */
    private String practiceName(String practiceUuid) {
        if (practiceUuid.isEmpty()) {
            return "";
        }
        dk.trustworks.intranet.model.Practice practice =
                dk.trustworks.intranet.model.Practice.findById(practiceUuid);
        return practice == null || practice.getName() == null ? "" : practice.getName();
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

    private void addRow(ArrayNode rows, String... cells) {
        ArrayNode row = rows.addArray();
        for (String cell : cells) {
            row.addObject().put("type", "raw_text").put("text", cell);
        }
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        if (value instanceof List<?> raw) {
            return (List<Object>) raw;
        }
        return List.of();
    }

    private static long num(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
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
