package dk.trustworks.intranet.recruitmentservice.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dk.trustworks.intranet.recruitmentservice.ai.AiDigestService;
import dk.trustworks.intranet.recruitmentservice.slack.SlackInboundHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every control the weekly digest renders must have somebody home.
 *
 * <p>Slack POSTs a {@code block_actions} payload for <b>any</b> element
 * carrying an {@code action_id} — link buttons included: they navigate
 * <em>and</em> call the app. {@code SlackInboundDispatchService} dispatches
 * only to keys a {@link SlackInboundHandler} bean has registered, so an
 * {@code action_id} with no handler behind it is a control that reports
 * clicks nobody answers.</p>
 *
 * <p>That is not hypothetical. The digest shipped
 * {@code digest_open_reports} (primary button), {@code digest_open_scorecards}
 * and {@code digest_feedback} (👍/👎, which silently binned every rating)
 * with no handler for any of the three. It surfaced only when a manual
 * sweep of prod logs on 2026-08-24 turned up two real clicks — the
 * signature had been sitting there for weeks, indistinguishable at WARN
 * from the ids Slack mints for collapsible containers.</p>
 *
 * <p>This test is the gate that stops the next one. It renders the digest
 * for every payload shape the renderer branches on, walks the JSON for
 * {@code action_id}s, and holds each against the real allowlist —
 * discovered the same way CDI discovers it, by finding every concrete
 * {@link SlackInboundHandler} on the classpath.</p>
 *
 * <p>DB-free and Quarkus-free by design, so it runs in the fast tier that
 * gates deploys — the tier that matters, because Slack inbound is
 * prod-only and this can never be end-to-end tested on staging.</p>
 */
class SlackDigestActionIdsHaveHandlersTest {

    /** Where handler beans live; the same package CDI scans. */
    private static final String HANDLER_PACKAGE =
            "dk.trustworks.intranet.recruitmentservice.slack";

    /** A key we know is registered — proves the discovery below really ran. */
    private static final String KNOWN_REGISTERED_KEY = "recruitment_scorecard_open";

    private static Set<String> registeredKeys;

    private SlackDigestRenderer renderer;
    private ObjectMapper mapper;

    @BeforeAll
    static void discoverAllowlist() {
        registeredKeys = new TreeSet<>();
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(HANDLER_PACKAGE);

        for (JavaClass candidate : classes) {
            if (!candidate.isAssignableTo(SlackInboundHandler.class)
                    || candidate.isInterface()
                    || candidate.getModifiers().contains(com.tngtech.archunit.core.domain
                            .JavaModifier.ABSTRACT)) {
                continue;
            }
            registeredKeys.add(keyOf(candidate));
        }
    }

    /**
     * Reads {@code key()} off a bare instance. Handler beans use field
     * injection and therefore keep the implicit no-arg constructor, and
     * {@code key()} returns a constant in every one of them — so an
     * uninjected instance answers it correctly. A handler that breaks
     * either assumption fails loudly here rather than silently dropping
     * out of the set and letting a dead button through.
     */
    private static String keyOf(JavaClass handlerClass) {
        try {
            Object instance = handlerClass.reflect()
                    .getDeclaredConstructor()
                    .newInstance();
            return ((SlackInboundHandler) instance).key();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fail("Cannot read key() from " + handlerClass.getName()
                    + ". This test discovers the inbound allowlist by instantiating each "
                    + "handler with its no-arg constructor and calling key(); that requires "
                    + "key() to return a constant and the bean to use field injection. If "
                    + "this handler needs constructor injection, expose its key as a "
                    + "public static final String and read it here instead — do NOT skip "
                    + "the class, or an unhandled action_id can ship again.", e);
        }
    }

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        renderer = new SlackDigestRenderer();
        renderer.objectMapper = mapper;
        renderer.baseUrl = "https://intra.trustworks.dk";
    }

    // ------------------------------------------------------------------
    // The gate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the discovery really found the allowlist (guards against a vacuous pass)")
    void allowlistDiscoveryIsNotEmpty() {
        assertFalse(registeredKeys.isEmpty(),
                "no SlackInboundHandler found in " + HANDLER_PACKAGE
                        + " — classpath scanning is broken, so the gate below proves nothing");
        assertTrue(registeredKeys.contains(KNOWN_REGISTERED_KEY),
                "expected the scorecard-open handler on the allowlist, found: " + registeredKeys);
    }

    @Test
    @DisplayName("every action_id the digest renders has a registered handler")
    void everyRenderedActionIdHasAHandler() {
        for (Map.Entry<String, Map<String, Object>> variant : payloadVariants().entrySet()) {
            for (String kind : List.of(AiDigestService.KIND_WEEKLY_FUNNEL,
                    AiDigestService.KIND_REJECTION_PATTERNS)) {
                Set<String> rendered = actionIdsIn(blocksOf(kind, variant.getValue()));
                Set<String> orphans = new LinkedHashSet<>(rendered);
                orphans.removeAll(registeredKeys);
                assertTrue(orphans.isEmpty(),
                        "the " + kind + " digest (" + variant.getKey() + ") renders action_id(s) "
                                + orphans + " with no SlackInboundHandler behind them. Slack POSTs "
                                + "a block_actions payload for every action_id — link buttons "
                                + "included — and SlackInboundDispatchService drops keys that are "
                                + "not on the allowlist, so this is a dead control. Either "
                                + "register a handler for it, or render the element without an "
                                + "action_id (a mrkdwn link instead of a link button). "
                                + "Registered keys: " + registeredKeys);
            }
        }
    }

    @Test
    @DisplayName("the digest stays a read-only message — no action_id at all")
    void digestRendersNoInteractiveElements() {
        for (Map.Entry<String, Map<String, Object>> variant : payloadVariants().entrySet()) {
            Set<String> rendered = actionIdsIn(
                    blocksOf(AiDigestService.KIND_WEEKLY_FUNNEL, variant.getValue()));
            assertTrue(rendered.isEmpty(),
                    "the digest is a read-only message by design (SlackDigestRenderer javadoc); "
                            + variant.getKey() + " rendered " + rendered + ". If a control is "
                            + "genuinely wanted here, register its handler and relax THIS test — "
                            + "never the one above it.");
        }
    }

    @Test
    @DisplayName("the report deep link survives as a link, not a button")
    void reportsDeepLinkIsStillReachable() {
        for (Map.Entry<String, Map<String, Object>> variant : payloadVariants().entrySet()) {
            String json = blocksOf(AiDigestService.KIND_WEEKLY_FUNNEL,
                    variant.getValue()).toString();
            assertTrue(json.contains("<https://intra.trustworks.dk/recruitment/reports|"),
                    "removing the dead button must not remove the way to the report ("
                            + variant.getKey() + "): " + json);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ArrayNode blocksOf(String kind, Map<String, Object> payload) {
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        copy.put("kind", kind);
        return renderer.digestBlocks(kind, "📈 Rekruttering · uge 34 · hele huset",
                String.valueOf(copy.get("narrative")), copy);
    }

    /** Every {@code action_id} anywhere in the tree, containers included. */
    private static Set<String> actionIdsIn(JsonNode node) {
        Set<String> found = new LinkedHashSet<>();
        collectActionIds(node, found);
        return found;
    }

    private static void collectActionIds(JsonNode node, Set<String> found) {
        if (node.isObject()) {
            JsonNode actionId = node.get("action_id");
            if (actionId != null && actionId.isTextual()) {
                found.add(actionId.asText());
            }
            node.fields().forEachRemaining(e -> collectActionIds(e.getValue(), found));
        } else if (node.isArray()) {
            node.forEach(child -> collectActionIds(child, found));
        }
    }

    /**
     * The payload shapes {@code digestBlocks} branches on. Both nudge
     * states matter: the second deep link only rendered when
     * {@code nudges > 0}, which is exactly why it was easy to miss.
     */
    private static Map<String, Map<String, Object>> payloadVariants() {
        Map<String, Map<String, Object>> variants = new LinkedHashMap<>();
        variants.put("busy week, nudges open", weekPayload(2, 1));
        variants.put("busy week, no nudges", weekPayload(2, 0));
        variants.put("quiet week", weekPayload(0, 0));
        return variants;
    }

    private static Map<String, Object> weekPayload(int hires, int nudges) {
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("hires", (long) hires);
        kpis.put("hires_delta", 0L);
        kpis.put("applications", (long) (hires * 2));
        kpis.put("applications_delta", 0L);
        kpis.put("stage_moves", (long) hires);
        kpis.put("terminals", 0L);
        kpis.put("nudges", (long) nudges);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("period", "2026-W34");
        payload.put("week_from", "2026-08-17");
        payload.put("week_to", "2026-08-23");
        payload.put("window_from", "2026-05");
        payload.put("window_to", "2026-08");
        payload.put("narrative", "Bevægelsen ligger i toppen af tragten.");
        payload.put("kpis", kpis);
        payload.put("funnel", hires == 0 ? List.of() : funnelRows());
        payload.put("sources", hires == 0 ? List.of()
                : List.of(Map.of("label", "Partnerhenvisning", "count", 3L)));
        payload.put("open_positions_by_track", hires == 0 ? List.of()
                : List.of(Map.of("label", "Praksisteam", "count", 11L)));
        payload.put("trend", hires == 0 ? List.of()
                : List.of(Map.of("month", "2026-08", "count", 8L)));
        return payload;
    }

    private static List<Object> funnelRows() {
        List<Object> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("from", "Screening");
        row.put("to", "1. samtale");
        row.put("count", 2L);
        row.put("delta", 1L);
        row.put("avg_days", 4.25d);
        rows.add(row);
        return rows;
    }
}
