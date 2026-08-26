package dk.trustworks.intranet.recruitmentservice.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dk.trustworks.intranet.recruitmentservice.ai.AiDigestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Block Kit payload contract of the weekly funnel digest.
 *
 * <p>The renderer emits raw JSON via {@code blocksAsString}, because
 * {@code table}, {@code container} and {@code data_visualization} exist in
 * no released {@code slack-api-model} (slackapi/java-slack-sdk issue
 * #1499). Nothing type-checks that JSON, so <b>this test is the type
 * check</b>: it asserts the structure and every documented Slack limit.
 * Without it, a malformed payload surfaces as a runtime
 * {@code invalid_blocks} — after the digest has already failed to
 * post.</p>
 *
 * <p>DB-free and Quarkus-free by design: the block builders are pure
 * functions of the event payload, so this runs in the fast tier that gates
 * deploys.</p>
 */
class SlackDigestBlocksTest {

    /** Documented Slack caps — see docs.slack.dev/reference/block-kit/blocks. */
    private static final int MAX_BLOCKS_PER_MESSAGE = 50;
    private static final int MAX_HEADER_CHARS = 150;
    private static final int MAX_MARKDOWN_CHARS_CUMULATIVE = 12000;
    private static final int MAX_TABLE_ROWS = 100;
    private static final int MAX_TABLE_CELLS_PER_ROW = 20;
    private static final int MAX_TABLE_CHARS_PER_MESSAGE = 10000;
    private static final int MAX_CONTAINER_CHILDREN = 10;
    private static final int MAX_CHARTS_PER_MESSAGE = 2;
    private static final int MAX_CHART_SERIES = 12;
    private static final int MAX_CHART_POINTS = 20;
    private static final int MAX_CHART_LABEL_CHARS = 20;
    private static final int MAX_CHART_TITLE_CHARS = 50;
    private static final int MAX_SECTION_TEXT_CHARS = 3000;

    /** Block types Slack accepts inside a container's child_blocks. */
    private static final List<String> CONTAINER_CHILD_TYPES = List.of(
            "actions", "context", "divider", "file", "header",
            "image", "input", "rich_text", "section", "table", "video");

    /** A SCREAMING_SNAKE token — what must never reach a reader. */
    private static final Pattern ENUM_CODE = Pattern.compile("\\b[A-Z][A-Z0-9]*(_[A-Z0-9]+)+\\b");

    private SlackDigestRenderer renderer;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        renderer = new SlackDigestRenderer();
        renderer.objectMapper = mapper;
        renderer.baseUrl = "https://intra.trustworks.dk";
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A representative week: movement in the funnel, a hire, open nudges. */
    private static Map<String, Object> busyWeek() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", AiDigestService.KIND_WEEKLY_FUNNEL);
        payload.put("period", "2026-W34");
        payload.put("week_from", "2026-08-17");
        payload.put("week_to", "2026-08-23");
        payload.put("window_from", "2026-05");
        payload.put("window_to", "2026-08");
        payload.put("narrative", "Bevægelsen ligger i toppen af tragten, mens de sidste trin "
                + "står stille. Et par scorecards mangler stadig.");
        payload.put("kpis", kpis(4, 2, 3, -1, 1, 0, 1, 1, 2, 0, 2, 1, 12));
        payload.put("funnel", List.of(
                row("Screening", "1. samtale", 2L, 1L, 4.25d),
                row("1. samtale", "2. samtale", 1L, -1L, 6.1d),
                row("Tilbud", "Ansat", 1L, 1L, null)));
        payload.put("sources", List.of(
                Map.of("label", "Partnerhenvisning", "count", 3L),
                Map.of("label", "LinkedIn (opsøgt)", "count", 1L)));
        payload.put("open_positions_by_track", List.of(
                Map.of("label", "Praksisteam", "count", 11L),
                Map.of("label", "Partner", "count", 1L)));
        payload.put("trend", List.of(
                Map.of("month", "2026-05", "count", 0L),
                Map.of("month", "2026-06", "count", 0L),
                Map.of("month", "2026-07", "count", 1L),
                Map.of("month", "2026-08", "count", 8L)));
        return payload;
    }

    /** The quiet week that used to render as an all-zero wall of prose. */
    private static Map<String, Object> quietWeek() {
        Map<String, Object> payload = busyWeek();
        payload.put("kpis", kpis(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1));
        payload.put("funnel", List.of());
        payload.put("sources", List.of());
        payload.put("trend", List.of());
        return payload;
    }

    /** The KPI map exactly as AiDigestService writes it: value + delta pairs. */
    private static Map<String, Object> kpis(long applications, long applicationsDelta,
                                            long stageMoves, long stageMovesDelta,
                                            long terminals, long terminalsDelta,
                                            long hires, long hiresDelta,
                                            long scorecards, long scorecardsDelta,
                                            long nudges, long nudgesDelta,
                                            long openPositions) {
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("applications", applications);
        kpis.put("applications_delta", applicationsDelta);
        kpis.put("stage_moves", stageMoves);
        kpis.put("stage_moves_delta", stageMovesDelta);
        kpis.put("terminals", terminals);
        kpis.put("terminals_delta", terminalsDelta);
        kpis.put("hires", hires);
        kpis.put("hires_delta", hiresDelta);
        kpis.put("scorecards", scorecards);
        kpis.put("scorecards_delta", scorecardsDelta);
        kpis.put("nudges", nudges);
        kpis.put("nudges_delta", nudgesDelta);
        kpis.put("open_positions", openPositions);
        return kpis;
    }

    private static Map<String, Object> row(String from, String to, long count, long delta,
                                           Double avgDays) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("from", from);
        row.put("to", to);
        row.put("count", count);
        row.put("delta", delta);
        row.put("avg_days", avgDays);
        return row;
    }

    private ArrayNode blocksOf(Map<String, Object> payload) {
        return renderer.digestBlocks(AiDigestService.KIND_WEEKLY_FUNNEL,
                SlackDigestRenderer.headerFor(AiDigestService.KIND_WEEKLY_FUNNEL,
                        String.valueOf(payload.get("period")), ""),
                String.valueOf(payload.get("narrative")), payload);
    }

    private static List<String> typesOf(ArrayNode blocks) {
        List<String> types = new ArrayList<>();
        blocks.forEach(b -> types.add(b.path("type").asText()));
        return types;
    }

    // ------------------------------------------------------------------
    // Structure
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a busy week renders the approved block sequence")
    void busyWeekBlockOrder() {
        assertEquals(List.of("header", "markdown", "table", "data_visualization",
                        "container", "section", "context"),
                typesOf(blocksOf(busyWeek())));
    }

    @Test
    @DisplayName("a quiet week drops the empty table and chart rather than rendering headers alone")
    void quietWeekOmitsEmptyVisuals() {
        List<String> types = typesOf(blocksOf(quietWeek()));
        assertFalse(types.contains("table"), "an empty funnel table is noise: " + types);
        assertFalse(types.contains("data_visualization"), "nothing to plot: " + types);
        assertTrue(types.contains("markdown"));
        assertTrue(types.contains("container"));
    }

    @Test
    @DisplayName("the quarterly digest keeps the plain prose layout")
    void rejectionDigestStaysSimple() {
        Map<String, Object> payload = busyWeek();
        payload.put("kind", AiDigestService.KIND_REJECTION_PATTERNS);
        ArrayNode blocks = renderer.digestBlocks(AiDigestService.KIND_REJECTION_PATTERNS,
                "📉 Afslagsmønstre · FY2025/26-Q4 · hele huset",
                "Kvartalets mønstre.", payload);
        assertEquals(List.of("header", "section", "context"), typesOf(blocks));
    }

    // ------------------------------------------------------------------
    // Slack's documented limits
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the payload respects every documented Block Kit limit")
    void withinSlackLimits() {
        for (Map<String, Object> payload : List.of(busyWeek(), quietWeek())) {
            ArrayNode blocks = blocksOf(payload);
            assertTrue(blocks.size() <= MAX_BLOCKS_PER_MESSAGE,
                    "too many blocks: " + blocks.size());

            int markdownChars = 0;
            int tableChars = 0;
            int charts = 0;

            for (JsonNode block : blocks) {
                switch (block.path("type").asText()) {
                    case "header" -> assertTrue(
                            block.path("text").path("text").asText().length() <= MAX_HEADER_CHARS,
                            "header too long");
                    case "markdown" -> markdownChars += block.path("text").asText().length();
                    case "section" -> assertTrue(
                            block.path("text").path("text").asText().length()
                                    <= MAX_SECTION_TEXT_CHARS, "section text too long");
                    case "table" -> tableChars += assertTableValid(block);
                    case "data_visualization" -> {
                        charts++;
                        assertChartValid(block);
                    }
                    case "container" -> tableChars += assertContainerValid(block);
                    default -> { /* actions / context / context_actions carry no caps we set */ }
                }
            }
            assertTrue(markdownChars <= MAX_MARKDOWN_CHARS_CUMULATIVE,
                    "markdown blocks total " + markdownChars);
            assertTrue(tableChars <= MAX_TABLE_CHARS_PER_MESSAGE,
                    "table cells total " + tableChars);
            assertTrue(charts <= MAX_CHARTS_PER_MESSAGE, "too many charts: " + charts);
        }
    }

    /** @return the table's character count, for the per-message aggregate */
    private static int assertTableValid(JsonNode table) {
        JsonNode rows = table.path("rows");
        assertTrue(rows.isArray() && !rows.isEmpty(), "table must have rows");
        assertTrue(rows.size() <= MAX_TABLE_ROWS, "table rows: " + rows.size());
        assertTrue(rows.size() >= 2, "a table with only a header row should not be rendered");

        int columns = rows.get(0).size();
        int chars = 0;
        for (JsonNode row : rows) {
            assertTrue(row.isArray(), "each table row must be an array of cells");
            assertTrue(row.size() <= MAX_TABLE_CELLS_PER_ROW, "cells per row: " + row.size());
            assertEquals(columns, row.size(), "ragged table row: " + row);
            for (JsonNode cell : row) {
                String type = cell.path("type").asText();
                assertTrue(List.of("raw_text", "raw_number", "rich_text").contains(type),
                        "unknown table cell type: " + type);
                chars += cell.path("text").asText().length();
            }
        }
        JsonNode settings = table.path("column_settings");
        if (!settings.isMissingNode()) {
            assertTrue(settings.size() <= columns,
                    "more column_settings than columns: " + settings.size() + " > " + columns);
            for (JsonNode setting : settings) {
                if (setting.has("align")) {
                    assertTrue(List.of("left", "center", "right")
                                    .contains(setting.path("align").asText()),
                            "bad align: " + setting);
                }
            }
        }
        return chars;
    }

    private static void assertChartValid(JsonNode chartBlock) {
        assertTrue(chartBlock.path("title").asText().length() <= MAX_CHART_TITLE_CHARS,
                "chart title too long");
        JsonNode chart = chartBlock.path("chart");
        assertTrue(List.of("bar", "line", "area", "pie").contains(chart.path("type").asText()),
                "unknown chart type");
        JsonNode series = chart.path("series");
        assertTrue(series.isArray() && !series.isEmpty(), "chart needs at least one series");
        assertTrue(series.size() <= MAX_CHART_SERIES, "too many series");
        for (JsonNode one : series) {
            JsonNode data = one.path("data");
            assertTrue(!data.isEmpty() && data.size() <= MAX_CHART_POINTS,
                    "series points: " + data.size());
            for (JsonNode point : data) {
                assertTrue(point.path("label").asText().length() <= MAX_CHART_LABEL_CHARS,
                        "chart label too long: " + point.path("label").asText());
                assertTrue(point.path("value").isNumber(), "chart value must be numeric");
            }
        }
        JsonNode categories = chart.path("axis_config").path("categories");
        assertTrue(categories.isArray() && !categories.isEmpty(), "axis_config.categories required");
        assertEquals(series.get(0).path("data").size(), categories.size(),
                "categories must line up with the series' points");
    }

    /** @return the character count of any tables nested in the container */
    private static int assertContainerValid(JsonNode container) {
        JsonNode children = container.path("child_blocks");
        assertTrue(children.isArray() && !children.isEmpty(), "container needs child_blocks");
        assertTrue(children.size() <= MAX_CONTAINER_CHILDREN,
                "container children: " + children.size());
        assertTrue(container.path("is_collapsible").asBoolean(),
                "the container exists to fold detail away");
        assertTrue(container.path("default_collapsed").asBoolean(),
                "detail must start collapsed, or nothing is gained");

        int chars = 0;
        for (JsonNode child : children) {
            String type = child.path("type").asText();
            assertTrue(CONTAINER_CHILD_TYPES.contains(type),
                    "Slack does not allow '" + type + "' inside a container");
            if ("table".equals(type)) {
                chars += assertTableValid(child);
            }
        }
        return chars;
    }

    // ------------------------------------------------------------------
    // The regressions this redesign exists to prevent
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no SCREAMING_SNAKE enum code appears anywhere a reader can see")
    void noEnumCodeLeaks() throws Exception {
        String json = mapper.writeValueAsString(blocksOf(busyWeek()));
        // action_id / block_id are machine fields; strip them before scanning.
        String visible = json.replaceAll("\"(action_id|block_id|value)\":\"[^\"]*\"", "");
        Matcher matcher = ENUM_CODE.matcher(visible);
        assertFalse(matcher.find(),
                "enum code leaked into the message: "
                        + (matcher.hitEnd() ? "" : matcher.group()));
    }

    @Test
    @DisplayName("the header and headline name the ISO week, never a month or a raw key")
    void headlineNamesTheWeek() {
        Map<String, Object> payload = busyWeek();
        String header = SlackDigestRenderer.headerFor(
                AiDigestService.KIND_WEEKLY_FUNNEL, "2026-W34", "");
        assertEquals("📈 Rekruttering · uge 34 · hele huset", header);

        String headline = renderer.headline(payload, castKpis(payload));
        assertTrue(headline.contains("uge 34"), headline);
        assertTrue(headline.contains("17.–23. august 2026"), headline);
        assertFalse(headline.contains("2026-W34"), "the ISO key is an idempotency key, not a label");
        assertFalse(headline.toLowerCase(java.util.Locale.ROOT).contains("måned"),
                "the reported period is a week: " + headline);
    }

    @Test
    @DisplayName("a practice edition names the practice so it cannot be mistaken for the company one")
    void practiceEditionIsLabelled() {
        assertEquals("📈 Rekruttering · uge 34 · Praksisteam Technology",
                SlackDigestRenderer.headerFor(AiDigestService.KIND_WEEKLY_FUNNEL,
                        "2026-W34", "Praksisteam Technology"));
    }

    @Test
    @DisplayName("headline figures come from the payload, so they cannot disagree with the table")
    void headlineUsesPayloadNumbers() {
        Map<String, Object> payload = busyWeek();
        String headline = renderer.headline(payload, castKpis(payload));
        assertTrue(headline.contains("**1 ansættelse**"), headline);   // singular
        assertTrue(headline.contains("**4 ansøgninger**"), headline);  // plural
        assertTrue(headline.contains("▲ +2"), "deltas make the numbers readable: " + headline);
        assertTrue(headline.contains("2 SLA-påmindelser"), headline);
    }

    @Test
    @DisplayName("a week with nothing in it says so instead of going silent")
    void quietWeekSaysSo() {
        Map<String, Object> payload = quietWeek();
        String headline = renderer.headline(payload, castKpis(payload));
        assertTrue(headline.contains("Ingen registreret bevægelse"), headline);
        assertTrue(headline.contains("**0 ansættelser**"), headline);
    }

    @Test
    @DisplayName("the push preview is the headline, not the whole narrative")
    void fallbackTextIsShort() {
        Map<String, Object> payload = busyWeek();
        String fallback = renderer.fallbackText(AiDigestService.KIND_WEEKLY_FUNNEL,
                "📈 Rekruttering · uge 34 · hele huset",
                "x".repeat(3000), payload);
        assertTrue(fallback.length() <= SlackDigestRenderer.FALLBACK_CLAMP,
                "fallback length " + fallback.length());
        assertFalse(fallback.contains("**"), "markdown must be stripped: " + fallback);
        assertFalse(fallback.contains("xxxx"), "the narrative must not be the push preview");
        assertTrue(fallback.contains("ansættelse"), fallback);
    }

    @Test
    @DisplayName("the provenance line states the week and marks the chart as trend context")
    void provenanceSeparatesTheTwoWindows() {
        String context = renderer.provenance(busyWeek());
        assertTrue(context.contains("uge 34"), context);
        assertTrue(context.contains("2026-05–2026-08"), context);
        assertTrue(context.contains("Diagrammet"), "the months must be labelled as chart context");
        assertTrue(context.contains("ingen persondata"), context);
    }

    @Test
    @DisplayName("Danish decimals use a comma, and a missing average renders as a dash")
    void danishNumberFormatting() {
        ArrayNode blocks = blocksOf(busyWeek());
        JsonNode table = blocks.get(2);
        assertEquals("4,3", table.path("rows").get(1).get(2).path("text").asText());
        assertEquals("—", table.path("rows").get(3).get(2).path("text").asText());
    }

    @Test
    @DisplayName("month labels are Danish short names inside Slack's 20-char cap")
    void chartLabelsAreDanish() {
        assertEquals("maj", SlackDigestRenderer.shortMonth("2026-05"));
        assertEquals("aug", SlackDigestRenderer.shortMonth("2026-08"));
        assertEquals("", SlackDigestRenderer.shortMonth(null));
    }

    @Test
    @DisplayName("a week spanning two months reads naturally in Danish")
    void dateRangeAcrossMonths() {
        assertEquals("17.–23. august 2026",
                SlackDigestRenderer.dateRange("2026-08-17", "2026-08-23"));
        assertEquals("29. juni – 5. juli 2026",
                SlackDigestRenderer.dateRange("2026-06-29", "2026-07-05"));
        assertEquals("", SlackDigestRenderer.dateRange("not-a-date", "2026-07-05"));
    }

    @Test
    @DisplayName("deltas render with direction, and flat weeks render as nothing at all")
    void deltaFormatting() {
        assertEquals(" ▲ +3", SlackDigestRenderer.delta(3));
        assertEquals(" ▼ −3", SlackDigestRenderer.delta(-3));
        assertEquals("", SlackDigestRenderer.delta(0));
    }

    @Test
    @DisplayName("an empty funnel or trend yields no block rather than an empty one")
    void emptyInputsYieldNoBlock() {
        assertNull(renderer.funnelTable(List.of()));
        assertNull(renderer.trendChart(List.of()));
        assertNull(renderer.labelledTable(List.of(), "Kilde", "Antal"));
        assertEquals("", renderer.openPositions(List.of()));
    }

    @Test
    @DisplayName("a long trend is truncated to Slack's 20-point cap, keeping the recent months")
    void longTrendIsTruncatedFromTheLeft() {
        List<Object> trend = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            trend.add(Map.of("month", String.format("2025-%02d", (i % 12) + 1), "count", (long) i));
        }
        JsonNode chart = renderer.trendChart(trend);
        assertNotNull(chart);
        JsonNode data = chart.path("chart").path("series").get(0).path("data");
        assertEquals(MAX_CHART_POINTS, data.size());
        // The most recent month must survive truncation.
        assertEquals(30, data.get(data.size() - 1).path("value").asInt());
    }

    /**
     * The deep links are {@code mrkdwn} links, not link buttons. A button
     * carries an {@code action_id}, and Slack POSTs a {@code block_actions}
     * payload for every one of those — the digest had three such ids and a
     * handler for none of them (see
     * {@code SlackDigestActionIdsHaveHandlersTest}).
     */
    @Test
    @DisplayName("the report link points at the configured base url, as a link not a button")
    void reportLinkPointsAtTheConfiguredBaseUrl() {
        ArrayNode blocks = blocksOf(busyWeek());
        JsonNode links = blocks.get(5);
        assertEquals("section", links.path("type").asText());
        assertTrue(links.path("text").path("text").asText()
                        .contains("<https://intra.trustworks.dk/recruitment/reports|Åbn rapporten>"),
                links.toString());
        assertFalse(links.toString().contains("action_id"),
                "an action_id here is a control Slack will call: " + links);
    }

    @Test
    @DisplayName("the scorecard link only appears when there are open nudges")
    void scorecardLinkIsConditional() {
        assertTrue(blocksOf(busyWeek()).get(5).path("text").path("text").asText()
                        .contains("filter=scorecards_missing"),
                "a week with open nudges links straight to them");
        assertFalse(blocksOf(quietWeek()).toString().contains("filter=scorecards_missing"),
                "a quiet week has nothing to chase");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castKpis(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("kpis");
    }
}
