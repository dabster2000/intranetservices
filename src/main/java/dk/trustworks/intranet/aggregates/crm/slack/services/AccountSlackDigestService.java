package dk.trustworks.intranet.aggregates.crm.slack.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.slack.ai.AccountSlackDigestPrompts;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads one day of an account space (CRM spec §3.2, §4.9): one strict-schema OpenAI
 * call, then deterministic backend validation. The model proposes, this class disposes —
 * nothing it returns reaches persistence without passing the checks in {@link #parse}.
 *
 * <p><b>Model.</b> Its own tier, deliberately NOT the global {@code openai.model}: that
 * defaults to gpt-5-nano, whose reasoning spend answers {@code "{}"} for structured output
 * (the staging failure recorded in {@code application.yml}). {@code gpt-5.6-terra} is the
 * mid tier this repo already runs strict-schema extraction on ({@code SignalExtractionService},
 * the recruitment intake, the consultant profile), so its access and its structured-output
 * behaviour are known. Reasoning effort defaults to {@code medium} — one notch above the
 * signal extractor's {@code low} — because separating a reported client decision from
 * working-from-home chatter across a whole day is judgement, not field extraction, and
 * the call runs nightly per linked account rather than per keystroke. Both are
 * {@code @ConfigProperty} so an environment can move them without a deploy.
 *
 * <p><b>Privacy.</b> {@code store=false}: the payload is a day of colleagues talking about
 * a named client. It is not retained by the provider, and OpenAIService switches to
 * PII-suppressed logging. The text exists in this JVM for the duration of the call; only
 * the validated {@link SlackDigestContent} leaves this class.
 *
 * <p><b>Threading.</b> {@link #digest} must run outside any transaction (the §P9 M1 rule —
 * the guard throws). A model round-trip holding a pooled connection is how this codebase
 * has caused outages before.
 *
 * <p><b>Degradation.</b> When the model is off, unavailable, refuses, or returns something
 * unusable, this answers {@link Optional#empty()} and the sync stores the day with its
 * counts and no reading. The row still lands — "14 messages (Marta, Nicky)" is true and
 * useful even when the model is down — and a later re-sync of that day can fill it in.
 */
@JBossLog
@ApplicationScoped
public class AccountSlackDigestService {

    static final String SCHEMA_NAME = "account_slack_digest";

    /**
     * Room for the reasoning AND the answer. A full day can legitimately produce eight
     * items in four lists plus people and topics (~1.5k tokens of JSON); a medium effort
     * on a reasoning model spends the rest thinking. Too small and the call answers 2xx
     * with no output text, which OpenAIService reports as {@code "{}"}.
     */
    static final int MAX_OUTPUT_TOKENS = 6000;

    static final int MAX_ITEMS_PER_LIST = 8;
    static final int MAX_PEOPLE = 10;
    static final int MAX_TOPICS = 6;
    static final int MAX_TEXT_CHARS = 300;
    static final int MAX_WHO_CHARS = 60;
    static final int MAX_NAME_CHARS = 80;
    static final int MAX_ROLE_CHARS = 80;
    static final int MAX_TOPIC_CHARS = 40;
    /** The column is 200; the prompt asks for 160. Anything longer is cut, not refused. */
    static final int MAX_HEADLINE_CHARS = 200;

    @Inject
    OpenAIService openAIService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * The model's own off-switch, separate from the feature flag: with the flag on and
     * this off, channels are still read and digest rows still form with counts and
     * participants — only the reading is missing. That is the right degradation when the
     * model budget, not the feature, is the concern.
     */
    @ConfigProperty(name = "dk.trustworks.crm.slack.digest-enabled", defaultValue = "true")
    boolean digestEnabled;

    @ConfigProperty(name = "dk.trustworks.crm.slack.digest-model", defaultValue = "gpt-5.6-terra")
    String digestModel;

    /**
     * Optional so an EMPTY env value means "omit the reasoning node" — required if the
     * model is ever pointed at a non-reasoning (gpt-4o-family) model, which rejects it.
     * A plain String with a present-but-empty value converts to null and Quarkus aborts
     * startup with SRCFG00040.
     */
    @ConfigProperty(name = "dk.trustworks.crm.slack.digest-reasoning-effort", defaultValue = "medium")
    Optional<String> digestReasoningEffort;

    public boolean isEnabled() {
        return digestEnabled;
    }

    public String model() {
        return digestModel;
    }

    /**
     * Reads one day.
     *
     * @param clientName   the client, for the prompt's context line
     * @param channelName  without the leading {@code #}
     * @param date         the Copenhagen day, so weekday and relative dates resolve
     * @param participants first names of the Trustworks people active that day
     * @param lines        the day's messages, already rendered — never persisted
     * @return the validated reading, or empty when the model was off or answered nothing usable
     */
    public Optional<SlackDigestContent> digest(String clientName, String channelName, LocalDate date,
                                               List<String> participants,
                                               List<AccountSlackDigestPrompts.Line> lines) {
        if (QuarkusTransaction.isActive()) {
            // The §P9 M1 rule: never hold a pooled connection across the model call.
            throw new IllegalStateException("digest must not be called inside a transaction");
        }
        if (!digestEnabled) {
            log.debug("Account Slack digest is switched off — storing counts only");
            return Optional.empty();
        }
        if (lines == null || lines.isEmpty()) {
            return Optional.empty();
        }
        String json = openAIService.askQuestionWithSchema(
                AccountSlackDigestPrompts.systemPrompt(),
                AccountSlackDigestPrompts.userPrompt(clientName, channelName, date, participants, lines),
                AccountSlackDigestPrompts.schema(),
                SCHEMA_NAME,
                AccountSlackDigestPrompts.REFUSAL_FALLBACK_JSON,
                digestModel, MAX_OUTPUT_TOKENS, false,
                digestReasoningEffort.filter(e -> !e.isBlank()).orElse(null));
        return parse(json);
    }

    /**
     * Turns the model's answer into a validated reading.
     *
     * <p>Package-private and free of injected state on purpose, so the whole validation
     * surface is exercised by a plain JUnit test in the DB-free fast tier that gates
     * deploys — no Quarkus boot, no database, no network.
     *
     * <p>Every string is re-rendered through the prompt's own identifier stripping and
     * sanitizer: the system prompt forbids e-mail addresses, phone numbers and URLs in the
     * answer, and a rule the model is asked to follow is not a rule the backend can rely on.
     */
    Optional<SlackDigestContent> parse(String json) {
        // OpenAIService never throws: it reports every failure as "{}" or blank.
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            log.warnf("Account Slack digest returned no usable output (model=%s, prompt=%s)",
                    digestModel, AccountSlackDigestPrompts.PROMPT_VERSION);
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warnf("Account Slack digest returned unparseable JSON (model=%s, prompt=%s): %s",
                    digestModel, AccountSlackDigestPrompts.PROMPT_VERSION, e.getMessage());
            return Optional.empty();
        }
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }

        String headline = clean(textOrNull(node, "headline"), MAX_HEADLINE_CHARS);
        List<SlackDigestContent.Item> decisions = items(node, "decisions");
        List<SlackDigestContent.Item> nextSteps = items(node, "nextSteps");
        List<SlackDigestContent.Note> risks = notes(node, "risks");
        List<SlackDigestContent.Note> clientAsks = notes(node, "clientAsks");
        List<SlackDigestContent.Person> people = people(node, "clientPeople");
        List<String> topics = topics(node);
        double confidence = confidence(node);

        String signalType = SlackDigestContent.signalTypeOf(textOrNull(node, "signalType"));
        String relevance = SlackDigestContent.relevanceOf(signalType, headline);

        return Optional.of(new SlackDigestContent(headline, signalType, relevance, decisions,
                nextSteps, risks, clientAsks, people, topics, confidence));
    }

    /** The reading as it is stored in {@code account_slack_digest.digest_json}. */
    public String toJson(SlackDigestContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            log.warnf("Account Slack digest could not be serialised: %s", e.getMessage());
            return null;
        }
    }

    /** The stored reading, or null when the column is empty or unreadable. */
    public SlackDigestContent fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SlackDigestContent.class);
        } catch (Exception e) {
            log.warnf("Stored Slack digest could not be read: %s", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------------

    /**
     * Relevance is no longer parsed — it is derived from {@code signalType} by
     * {@link SlackDigestContent#relevanceOf}, and the model is not asked for it at all.
     *
     * <p>What stood here reconciled the model's own grade against the lists: a {@code LOW}
     * carrying a non-empty decisions, risks or clientAsks list was promoted to
     * {@code HIGH}. It was written to stop a model under-grading a real decision, and in
     * the first production run it did the opposite — "Ny CVE rammer sandsynligvis alle
     * pipelines" and "E2E-fejl i PR-builds skyldes manglende reset af velkomstbanner" were
     * promoted to HIGH next to "Marta øges til 50% mindst til juni 2027", because a build
     * failure is phrased as a risk. It also never looked at {@code nextSteps}, so a
     * steering-group meeting about contract renewals stayed LOW. The grade cannot be
     * recovered from the SHAPE of the content; it has to come from what KIND of event the
     * day was, which is what {@code signalType} now says.
     */

    static List<SlackDigestContent.Item> items(JsonNode node, String field) {
        List<SlackDigestContent.Item> out = new ArrayList<>();
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            return out;
        }
        for (JsonNode element : array) {
            if (out.size() >= MAX_ITEMS_PER_LIST || !element.isObject()) {
                break;
            }
            String text = clean(textOrNull(element, "text"), MAX_TEXT_CHARS);
            if (text == null) {
                continue;
            }
            out.add(new SlackDigestContent.Item(text,
                    clean(textOrNull(element, "who"), MAX_WHO_CHARS),
                    isoDateOrNull(textOrNull(element, "when"))));
        }
        return out;
    }

    static List<SlackDigestContent.Note> notes(JsonNode node, String field) {
        List<SlackDigestContent.Note> out = new ArrayList<>();
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            return out;
        }
        for (JsonNode element : array) {
            if (out.size() >= MAX_ITEMS_PER_LIST || !element.isObject()) {
                break;
            }
            String text = clean(textOrNull(element, "text"), MAX_TEXT_CHARS);
            if (text != null) {
                out.add(new SlackDigestContent.Note(text));
            }
        }
        return out;
    }

    static List<SlackDigestContent.Person> people(JsonNode node, String field) {
        List<SlackDigestContent.Person> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonNode array = node.path(field);
        if (!array.isArray()) {
            return out;
        }
        for (JsonNode element : array) {
            if (out.size() >= MAX_PEOPLE || !element.isObject()) {
                break;
            }
            String name = clean(textOrNull(element, "name"), MAX_NAME_CHARS);
            if (name == null || !seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(new SlackDigestContent.Person(name, clean(textOrNull(element, "role"), MAX_ROLE_CHARS)));
        }
        return out;
    }

    static List<String> topics(JsonNode node) {
        List<String> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonNode array = node.path("topics");
        if (!array.isArray()) {
            return out;
        }
        for (JsonNode element : array) {
            if (out.size() >= MAX_TOPICS) {
                break;
            }
            if (!element.isTextual()) {
                continue;
            }
            String topic = clean(element.asText(), MAX_TOPIC_CHARS);
            if (topic != null && seen.add(topic.toLowerCase(Locale.ROOT))) {
                out.add(topic);
            }
        }
        return out;
    }

    /**
     * Strip what the prompt forbids and the model may still emit — links, addresses,
     * numbers, markup, control characters — then cap. Null for nothing left.
     */
    static String clean(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String rendered = AccountSlackDigestPrompts.renderSlackMarkup(raw, id -> null);
        String cleaned = AccountSlackDigestPrompts.sanitize(rendered, maxChars);
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** {@code YYYY-MM-DD} or null. The prompt asks for ISO; anything else is dropped, not guessed. */
    static String isoDateOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() != 10) {
            return null;
        }
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static double confidence(JsonNode node) {
        JsonNode value = node.path("confidence");
        if (!value.isNumber()) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value.asDouble()));
    }

    /** Structured Outputs expresses "absent" as JSON null, so blank and null are the same thing. */
    static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
