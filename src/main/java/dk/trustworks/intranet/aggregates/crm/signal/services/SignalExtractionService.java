package dk.trustworks.intranet.aggregates.crm.signal.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.signal.ai.AccountSignalPrompts;
import dk.trustworks.intranet.aggregates.crm.signal.dto.SignalExtractionDTO;
import dk.trustworks.intranet.aggregates.crm.signal.model.enums.SignalType;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads one "Heard something?" line (CRM spec §3.4): one strict-schema OpenAI call,
 * then deterministic backend validation. The model proposes, this class disposes —
 * nothing it returns reaches persistence without passing the checks here.
 *
 * <p><b>Model.</b> Its own extraction tier, deliberately NOT the global
 * {@code openai.model}: that defaults to gpt-5-nano, whose reasoning spend answers
 * {@code "{}"} for structured output (the staging failure recorded in
 * {@code application.yml}). Reasoning effort is pinned low and injected as
 * {@code Optional} so an empty env value omits the node instead of aborting startup
 * with SRCFG00040.
 *
 * <p><b>Privacy.</b> {@code store=false}: the line names real people at client
 * organisations, so the payload is not retained by the provider and OpenAIService
 * switches to PII-suppressed logging.
 *
 * <p><b>Threading.</b> {@link #extract} must run outside any transaction (the §P9 M1
 * rule — the guard throws). A model round-trip holding a pooled connection is how this
 * codebase has caused outages before.
 *
 * <p><b>Degradation.</b> There is no rule-based fallback extractor — that was decided
 * deliberately (2026-09-12). When the model is unavailable, refuses, or returns
 * something unusable, this returns an empty reading with {@code signalType = OTHER}
 * rather than throwing, so the capture panel can still save the verbatim line against
 * the client the author picked. The capture is never lost because the model was down.
 */
@JBossLog
@ApplicationScoped
public class SignalExtractionService {

    static final String SCHEMA_NAME = "account_signal_extraction";
    static final int MAX_OUTPUT_TOKENS = 1024;

    @Inject
    OpenAIService openAIService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * The master off-switch. Extraction is reachable by every employee and costs a model
     * call per debounced keystroke burst, so there has to be a way to stop the spend
     * without a deploy. Turning it off degrades to the same empty reading an unavailable
     * model produces, which the panel already handles — captures keep working.
     */
    @ConfigProperty(name = "dk.trustworks.crm.signal.extraction-enabled", defaultValue = "true")
    boolean extractionEnabled;

    @ConfigProperty(name = "dk.trustworks.crm.signal.extraction-model", defaultValue = "gpt-5.6-terra")
    String extractionModel;

    /**
     * Optional so an EMPTY env value means "omit the reasoning node". A plain String
     * with a present-but-empty value converts to null and Quarkus aborts startup with
     * SRCFG00040 — the defaultValue does not rescue it, because the raw value IS present.
     */
    @ConfigProperty(name = "dk.trustworks.crm.signal.extraction-reasoning-effort", defaultValue = "low")
    Optional<String> extractionReasoningEffort;

    /**
     * Reads one line.
     *
     * @param authorFirstName  used to phrase the relation from the author
     * @param clients          the client allowlist, each {@code [uuid, name]} — the only
     *                         client uuids that may come back
     * @param colleagues       the colleague allowlist, each {@code [uuid, name]} — the only
     *                         colleague uuids that may come back
     * @param text             the raw line
     * @param pickedClientUuids the clients the author has already chosen with {@code @}.
     *                          These are always part of the reading; unlike before V593
     *                          they no longer SUPPRESS what the model found, or a line
     *                          naming a second account could never surface it.
     */
    public SignalExtractionDTO extract(String authorFirstName, List<String[]> clients,
                                       List<String[]> colleagues, String text,
                                       List<String> pickedClientUuids) {
        if (QuarkusTransaction.isActive()) {
            // The §P9 M1 rule: never hold a pooled connection across the model call.
            throw new IllegalStateException("extract must not be called inside a transaction");
        }
        if (!extractionEnabled) {
            log.debug("Signal extraction is switched off — returning an empty reading");
            return empty(resolveAll(pickedClientUuids, clients));
        }
        String json = openAIService.askQuestionWithSchema(
                AccountSignalPrompts.systemPrompt(),
                AccountSignalPrompts.userPrompt(authorFirstName, clients, colleagues, text),
                AccountSignalPrompts.schema(),
                SCHEMA_NAME,
                AccountSignalPrompts.REFUSAL_FALLBACK_JSON,
                extractionModel, MAX_OUTPUT_TOKENS, false,
                extractionReasoningEffort.filter(e -> !e.isBlank()).orElse(null));
        return parse(json, clients, colleagues, pickedClientUuids);
    }

    /**
     * Turns the model's answer into a validated reading.
     *
     * <p>Package-private and free of injected state on purpose, so the whole validation
     * surface is exercised by a plain JUnit test in the DB-free fast tier that gates
     * deploys — no Quarkus boot, no database, no network.
     */
    SignalExtractionDTO parse(String json, List<String[]> clients, List<String[]> colleagues,
                              List<String> pickedClientUuids) {
        List<String> verifiedPicks = resolveAll(pickedClientUuids, clients);

        // OpenAIService never throws: it reports every failure as "{}" or blank. A caller
        // that only try/catches silently accepts nothing — test for it explicitly.
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            log.warnf("Signal extraction returned no usable output (model=%s, prompt=%s) — saving the line unread",
                    extractionModel, AccountSignalPrompts.PROMPT_VERSION);
            return empty(verifiedPicks);
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warnf(e, "Signal extraction returned unparseable JSON (model=%s, prompt=%s)",
                    extractionModel, AccountSignalPrompts.PROMPT_VERSION);
            return empty(verifiedPicks);
        }

        // The author's picks first, then anything the model found that they have not
        // picked yet — that tail is what the panel offers as "you also mentioned X".
        // Before V593 a pick REPLACED the model's reading, which is precisely how a line
        // naming two accounts could only ever keep one of them.
        LinkedHashSet<String> clientUuids = new LinkedHashSet<>(verifiedPicks);
        clientUuids.addAll(resolveAll(stringList(node, "clientUuids"), clients));

        // Only surface the unmatched fragment when there is genuinely no client, so the
        // panel can say what it failed to match instead of silently finding nothing.
        String clientText = clientUuids.isEmpty() ? textOrNull(node, "clientText") : null;

        return new SignalExtractionDTO(
                List.copyOf(clientUuids),
                clientText,
                textOrNull(node, "personName"),
                textOrNull(node, "personRole"),
                textOrNull(node, "relationText"),
                resolveAll(stringList(node, "colleagueUuids"), colleagues),
                parseType(textOrNull(node, "signalType")).name(),
                confidence(node));
    }

    /**
     * The allowlist re-check. A uuid is accepted only if it is one of the uuids we put in
     * the prompt — the model never reaches persistence with an id nobody verified, and a
     * forged or hallucinated uuid is dropped rather than resolving to someone else's
     * client or to a colleague who was never mentioned.
     *
     * <p>Used for BOTH lists. A colleague uuid is checked exactly as hard as a client
     * uuid: the consequence of accepting an unverified one is a relationship edge
     * asserting that a named employee knows a named third party, which is worse than a
     * wrong account, not better.
     *
     * <p>Order is the candidates' own, de-duplicated. Never null.
     */
    private static List<String> resolveAll(List<String> candidates, List<String[]> allowlist) {
        if (candidates == null || candidates.isEmpty() || allowlist == null) {
            return List.of();
        }
        Set<String> allowed = allowlist.stream()
                .filter(entry -> entry != null && entry.length > 0 && entry[0] != null)
                .map(entry -> entry[0])
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && allowed.contains(candidate.trim())) {
                resolved.add(candidate.trim());
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * A JSON array of strings, or empty. A model that answers a schema-required array
     * with a scalar, a null or an object is a model that answered wrong — dropping it is
     * the same posture as an unparseable uuid, not a reason to throw.
     */
    private static List<String> stringList(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode element : value) {
            if (element.isTextual() && !element.asText().isBlank()) {
                out.add(element.asText().trim());
            }
        }
        return out;
    }

    private static SignalExtractionDTO empty(List<String> clientUuids) {
        return new SignalExtractionDTO(clientUuids == null ? List.of() : List.copyOf(clientUuids),
                null, null, null, null, List.of(), SignalType.OTHER.name(), 0.0d);
    }

    /** Unknown, misspelled and absent types all become OTHER — never an exception. */
    private static SignalType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return SignalType.OTHER;
        }
        try {
            return SignalType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SignalType.OTHER;
        }
    }

    private static Double confidence(JsonNode node) {
        JsonNode value = node.path("confidence");
        if (!value.isNumber()) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value.asDouble()));
    }

    /** Structured Outputs expresses "absent" as JSON null, so blank and null are the same thing. */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
