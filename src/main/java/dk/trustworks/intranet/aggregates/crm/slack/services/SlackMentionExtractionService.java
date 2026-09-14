package dk.trustworks.intranet.aggregates.crm.slack.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.slack.ai.SlackMentionPrompts;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Reads one day of a general Slack channel (source-channel spec §4.4): one strict-schema
 * OpenAI call per chunk, then deterministic backend validation. The model proposes, this
 * class disposes — nothing it returns reaches persistence without passing the checks in
 * {@link #parse}.
 *
 * <p><b>Why this lane's disposal is harsher than the digest lane's.</b> A digest is asked
 * about a channel that is about one client, and the client is known before the call. Here
 * the model chooses the account, so every one of its choices is re-checked: a
 * {@code clientId} is accepted only if it is an id this run actually put in the prompt, a
 * company name that is really a colleague's first name is thrown away, and — the one guard
 * that does not depend on the model behaving — a mention that cites no existing line of the
 * day is discarded without ceremony (D13). A reading nobody can point at is
 * indistinguishable from an invented one, so it is treated as invented.
 *
 * <p><b>Model.</b> Its own knobs, deliberately separate from
 * {@link AccountSlackDigestService}'s so this lane's spend can be stopped without stopping
 * the other (D16) — the general channels are the noisy half of the pair and the one an
 * anxious budget will want to switch off first. {@code gpt-5.6-terra} is the mid tier this
 * repo already runs strict-schema extraction on. Reasoning effort defaults to
 * {@code medium}, which is this lane's own choice and matches only the digest lane: every
 * other AI lane in the repo runs at {@code low}, and that is right for field extraction.
 * Deciding that "kundemøde hos NN kl. 10.30" is account news while the message above it
 * about a colleague's night in A&amp;E is not, and telling two companies named in one
 * sentence apart, is judgement rather than extraction — and the call runs nightly per
 * channel-day rather than per keystroke. Both are {@code @ConfigProperty} so an environment
 * can move them without a deploy.
 *
 * <p><b>Privacy.</b> {@code store=false}: the payload is a day of colleagues talking
 * amongst themselves, most of which is none of the CRM's business. It is not retained by
 * the provider, and OpenAIService switches to PII-suppressed logging. The messages exist in
 * this JVM for the length of the call; what leaves this class is validated readings,
 * company names and line numbers.
 *
 * <p><b>Threading.</b> {@link #extract} must run outside any transaction (the §P9 M1 rule —
 * the guard throws), and a busy day is several round-trips rather than one. A model
 * round-trip holding a pooled connection is how this codebase has caused outages before.
 *
 * <p><b>Degradation, and the one distinction everything downstream turns on.</b>
 * {@link OpenAIService} never throws. Every non-2xx, every timeout and every exhausted token
 * budget comes back as the literal empty object; a model that refuses comes back as the
 * schema-conformant {@code {"mentions":[]}}. Those two are indistinguishable from here by
 * their emptiness and mean opposite things — the refusal, like a well-formed answer with an
 * empty list, is an honest empty day, which most days of a general channel are, while the
 * empty object is a call that never happened. So a reading carries {@code failed}, and the
 * sync lane refuses to close a day whose model call failed: a rate-limit burst at 02:40 then
 * costs a night's delay instead of every mention of that night, for ever, behind a green run
 * row.
 *
 * <p>Nothing is filed either way. Unlike a digest — where "14 messages (Marta, Nicky)" is
 * still true and worth storing — a mention row with no reading would assert that a general
 * channel said something about a client, which is exactly what nobody knows.
 */
@JBossLog
@ApplicationScoped
public class SlackMentionExtractionService {

    static final String SCHEMA_NAME = "slack_mentions";

    /**
     * Room for the reasoning AND the answer. A day of a busy channel can legitimately
     * produce a dozen mentions, each with four lists of its own, so the budget sits well
     * above the digest lane's 6 000; too small and the call answers 2xx with no output
     * text, which OpenAIService reports as {@code "{}"}.
     */
    static final int MAX_OUTPUT_TOKENS = 8000;

    /**
     * Per model call, not per day: a day split into three chunks may legitimately answer
     * sixty. Beyond this a day has stopped being a day and started being a flood, and the
     * {@code HIGH} ones are the ones an account owner would have wanted kept.
     */
    static final int MAX_MENTIONS_PER_CALL = 20;

    /**
     * Evidence lines per mention per call. The union across chunks is deliberately NOT
     * re-capped: it is what the row's participants and its message count rest on, and
     * cutting it would under-report colleagues who genuinely wrote about the client.
     */
    static final int MAX_EVIDENCE_PER_MENTION = 20;

    /** The hints table holds 190; the model is given no reason to write more than a name. */
    static final int MAX_COMPANY_NAME_CHARS = 120;

    /**
     * Ourselves, in the spellings a Danish channel uses. "Trustworks er blevet inviteret
     * til …" is a sentence about us, not about an account, and a hint row for our own name
     * would sit at the top of the prospects panel forever.
     */
    private static final Set<String> OWN_COMPANY_KEYS = Set.of("trustworks", "tw", "intra");

    @Inject
    OpenAIService openAIService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * This lane's own off-switch, separate from the feature flag and from the digest
     * lane's: with the source-channel flag on and this off, channels are still read and
     * their cursors still advance — only the reading is missing. That is the right
     * degradation when the model budget, not the feature, is the concern.
     */
    @ConfigProperty(name = "dk.trustworks.crm.slack.mention-enabled", defaultValue = "true")
    boolean mentionEnabled;

    @ConfigProperty(name = "dk.trustworks.crm.slack.mention-model", defaultValue = "gpt-5.6-terra")
    String mentionModel;

    /**
     * Optional so an EMPTY env value means "omit the reasoning node" — required if the
     * model is ever pointed at a non-reasoning (gpt-4o-family) model, which rejects it.
     * A plain String with a present-but-empty value converts to null and Quarkus aborts
     * startup with SRCFG00040.
     */
    @ConfigProperty(name = "dk.trustworks.crm.slack.mention-reasoning-effort", defaultValue = "medium")
    Optional<String> mentionReasoningEffort;

    public boolean isEnabled() {
        return mentionEnabled;
    }

    public String model() {
        return mentionModel;
    }

    /** One validated mention: the client it was attributed to, the reading, the lines it rests on. */
    public record Mention(String clientUuid, SlackDigestContent content, List<Integer> evidence) {
    }

    /**
     * A company the model named that no client matches. It is a hint, not a row on an
     * account: the name as the model wrote it, the key it is deduplicated by, and the lines
     * it rests on — from which the sync derives the colleagues who wrote about it.
     */
    public record UnmatchedSighting(String displayName, String nameKey, List<Integer> evidence) {
    }

    /**
     * A whole day's yield, merged across its chunks.
     *
     * <p>{@code tsByLine} is the reason a caller can do anything with an evidence number.
     * Line numbers restart at 1 in every chunk, so this class renumbers them across the day
     * and hands back the one map that resolves them: number → Slack {@code ts}, which is
     * both the permalink of the first cited message and the way back to the colleague who
     * wrote each cited line. It is built from the messages and thrown away with them.
     *
     * @param droppedAsColleague how many names were dropped for being a colleague or
     *                           ourselves, for the run summary — the count is the useful
     *                           part, the names are precisely what must not be written down
     * @param failed             at least one of the day's chunks was never read by the model.
     *                           Whatever the other chunks yielded is still here and is still
     *                           true, but it is a fraction of the day: the sync lane throws it
     *                           away and re-reads the day whole rather than filing two thirds
     *                           of it as a row that will look complete for ever
     */
    public record Extraction(List<Mention> mentions, List<UnmatchedSighting> unmatched,
                             int droppedAsColleague, Map<Integer, String> tsByLine, boolean failed) {
        static Extraction nothing() {
            return new Extraction(List.of(), List.of(), 0, Map.of(), false);
        }
    }

    /**
     * One model call's worth, before the day's chunks are merged.
     *
     * <p>{@code failed} is the whole of what separates the two answers that look alike from
     * here: a well-formed answer holding an empty {@code mentions} list is a chunk that said
     * nothing about any account, and a blank body, the literal empty object or anything that
     * is not a {@code mentions} array at all is {@link OpenAIService} reporting a call that
     * did not happen. Only the second kind may hold a cursor back.
     */
    record Reading(List<Mention> mentions, List<UnmatchedSighting> unmatched, int droppedAsColleague,
                   boolean failed) {
        /** The model was not read at all: nothing is filed, and the day may not be closed. */
        static Reading failure() {
            return new Reading(List.of(), List.of(), 0, true);
        }
    }

    /**
     * Reads one day of one channel: one call per chunk, then the day merged per client.
     *
     * @param channelName    without the leading {@code #}
     * @param date           the Copenhagen day, so weekday and relative dates resolve
     * @param participants   first names of the Trustworks people active that day
     * @param allowlist      the accounts the model may attribute to, ordered and built once
     *                       for the whole run; only the first
     *                       {@link SlackMentionPrompts#MAX_ACCOUNTS_IN_PROMPT} of them reach
     *                       the model, and only those are accepted back
     * @param linkedAliases  {@code nameKey} → client uuid for every hint somebody has linked
     *                       (D4); a company name that resolves here is promoted to a matched
     *                       mention even when the model left {@code clientId} null
     * @param colleagueNames every colleague's name, so one of them turning up as a company
     *                       name is dropped rather than filed as a prospect
     * @param lines          the day's messages, already rendered — never persisted
     */
    public Extraction extract(String channelName, LocalDate date, List<String> participants,
                              List<SlackMentionPrompts.Account> allowlist,
                              Map<String, String> linkedAliases,
                              Collection<String> colleagueNames,
                              List<SlackMentionPrompts.SourceLine> lines) {
        if (QuarkusTransaction.isActive()) {
            // The §P9 M1 rule: never hold a pooled connection across the model call.
            throw new IllegalStateException("extract must not be called inside a transaction");
        }
        if (!mentionEnabled) {
            log.debug("Slack mention extraction is switched off — the day is read but nothing is filed");
            return Extraction.nothing();
        }
        if (lines == null || lines.isEmpty()) {
            return Extraction.nothing();
        }
        List<SlackMentionPrompts.Chunk> chunks =
                SlackMentionPrompts.chunks(channelName, date, participants, allowlist, lines);
        if (chunks.isEmpty()) {
            return Extraction.nothing();
        }

        Set<String> allowlistIds = allowlistIds(allowlist);
        Set<String> colleagueKeys = colleagueKeys(participants, colleagueNames);

        List<Mention> mentions = new ArrayList<>();
        List<UnmatchedSighting> unmatched = new ArrayList<>();
        Map<Integer, String> tsByLine = new LinkedHashMap<>();
        int droppedAsColleague = 0;
        boolean failed = false;
        // Each chunk numbers its own lines from 1 and the day needs one numbering, so a
        // chunk's numbers are shifted past every line the chunks before it held.
        int offset = 0;

        for (SlackMentionPrompts.Chunk chunk : chunks) {
            String json = openAIService.askQuestionWithSchema(
                    SlackMentionPrompts.systemPrompt(),
                    chunk.userPrompt(),
                    SlackMentionPrompts.schema(),
                    SCHEMA_NAME,
                    SlackMentionPrompts.REFUSAL_FALLBACK_JSON,
                    mentionModel, MAX_OUTPUT_TOKENS, false,
                    mentionReasoningEffort.filter(e -> !e.isBlank()).orElse(null));
            Reading reading = parse(json, allowlistIds, chunk.lineCount(), linkedAliases, colleagueKeys);
            if (reading.failed()) {
                // The day is going to be re-read whole tomorrow, so the chunks after this one
                // would be paid for twice and used never.
                failed = true;
                break;
            }

            for (Map.Entry<Integer, String> line : chunk.tsByLine().entrySet()) {
                tsByLine.put(line.getKey() + offset, line.getValue());
            }
            for (Mention mention : reading.mentions()) {
                mentions.add(new Mention(mention.clientUuid(), mention.content(),
                        shift(mention.evidence(), offset)));
            }
            for (UnmatchedSighting sighting : reading.unmatched()) {
                unmatched.add(new UnmatchedSighting(sighting.displayName(), sighting.nameKey(),
                        shift(sighting.evidence(), offset)));
            }
            droppedAsColleague += reading.droppedAsColleague();
            offset += chunk.lineCount();
        }

        return new Extraction(mergeMentions(mentions), mergeSightings(unmatched),
                droppedAsColleague, Map.copyOf(tsByLine), failed);
    }

    /**
     * Turns one model answer into validated readings.
     *
     * <p>Package-private and reading only its injected fields, so the whole validation
     * surface is exercised by a plain JUnit test in the DB-free fast tier that gates
     * deploys — no Quarkus boot, no database, no network.
     *
     * <p>The checks run in the order §4.4 states them, and that order is load-bearing for
     * one of them: a mention that cites nothing is dropped BEFORE the colleague test, so an
     * invented "mention" of a colleague's name never inflates {@code droppedAsColleague},
     * which an admin reads as "the model keeps mistaking Nicolas for a company".
     *
     * <p>Before any of that, the answer itself is judged. Three shapes mean the call failed
     * rather than the day being quiet — no body, the empty object {@link OpenAIService}
     * reports every failure as, and an answer that is not a {@code mentions} array at all,
     * which strict Structured Outputs cannot produce — and each of them returns
     * {@link Reading#failure()} so the caller can decline to close the day.
     *
     * @param allowlistIds  the client uuids this run actually put in the prompt
     * @param lineCount     how many numbered lines this chunk held — the ceiling evidence is
     *                      filtered against
     * @param linkedAliases {@code nameKey} → client uuid, belt and braces over the aliases
     *                      the prompt already carried
     * @param colleagueKeys normalised colleague names and our own, none of which is a company
     */
    Reading parse(String json, Set<String> allowlistIds, int lineCount,
                  Map<String, String> linkedAliases, Set<String> colleagueKeys) {
        // OpenAIService never throws: it reports every failure as "{}" or blank. A caller
        // that only try/catches silently accepts nothing — test for it explicitly.
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            log.warnf("Slack mention extraction returned no usable output (model=%s, prompt=%s)",
                    mentionModel, SlackMentionPrompts.PROMPT_VERSION);
            return Reading.failure();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warnf("Slack mention extraction returned unparseable JSON (model=%s, prompt=%s): %s",
                    mentionModel, SlackMentionPrompts.PROMPT_VERSION, e.getMessage());
            return Reading.failure();
        }
        if (node == null || !node.isObject() || !node.path("mentions").isArray()) {
            // The refusal fallback is {"mentions":[]} and passes this; anything that cannot
            // even carry a mentions list came from a failed call, not from a quiet day.
            log.warnf("Slack mention extraction returned an answer with no mentions array (model=%s, prompt=%s)",
                    mentionModel, SlackMentionPrompts.PROMPT_VERSION);
            return Reading.failure();
        }

        List<Proposal> proposals = new ArrayList<>();
        int droppedAsColleague = 0;
        for (JsonNode element : node.path("mentions")) {
            if (!element.isObject()) {
                continue;
            }

            // 1. Attribution. An id is worth having only if it is one this run showed the
            //    model; anything else is a name, and a name we have already learned belongs
            //    to a client is that client's (D4).
            String clientUuid = null;
            String displayName = null;
            String nameKey = null;
            String proposedId = AccountSlackDigestService.textOrNull(element, "clientId");
            if (proposedId != null && allowlistIds.contains(proposedId)) {
                clientUuid = proposedId;
            } else {
                displayName = AccountSlackDigestService.clean(
                        AccountSlackDigestService.textOrNull(element, "companyName"), MAX_COMPANY_NAME_CHARS);
                if (displayName == null) {
                    continue;
                }
                nameKey = nameKey(displayName);
                if (nameKey.isEmpty()) {
                    continue;
                }
                String linked = linkedAliases == null ? null : linkedAliases.get(nameKey);
                if (linked != null) {
                    clientUuid = linked;
                    displayName = null;
                    nameKey = null;
                }
            }

            // 2. Evidence (D13): the one check that takes nothing on the model's word.
            List<Integer> evidence = evidence(element, lineCount);
            if (evidence.isEmpty()) {
                continue;
            }

            // 3. A colleague, or ourselves, is not a prospect.
            if (clientUuid == null && colleagueKeys.contains(nameKey)) {
                droppedAsColleague++;
                continue;
            }

            // 4. Without a headline there is no row: the timeline line IS the headline, and
            //    "#ledelse: " on its own is worse than nothing.
            String headline = AccountSlackDigestService.clean(
                    AccountSlackDigestService.textOrNull(element, "headline"),
                    AccountSlackDigestService.MAX_HEADLINE_CHARS);
            if (headline == null) {
                continue;
            }

            // 5. The shared fields through the digest lane's own helpers and caps — the
            //    reading shape is identical (D12), so the validation of it must be too.
            List<SlackDigestContent.Item> decisions = AccountSlackDigestService.items(element, "decisions");
            List<SlackDigestContent.Item> nextSteps = AccountSlackDigestService.items(element, "nextSteps");
            List<SlackDigestContent.Note> risks = AccountSlackDigestService.notes(element, "risks");
            List<SlackDigestContent.Note> clientAsks = AccountSlackDigestService.notes(element, "clientAsks");
            List<SlackDigestContent.Person> people = AccountSlackDigestService.people(element, "clientPeople");
            List<String> topics = AccountSlackDigestService.topics(element);
            double confidence = AccountSlackDigestService.confidence(element);
            String relevance = floor(AccountSlackDigestService.relevance(
                    AccountSlackDigestService.textOrNull(element, "relevance"), headline,
                    !decisions.isEmpty() || !risks.isEmpty() || !clientAsks.isEmpty()));

            proposals.add(new Proposal(clientUuid, displayName, nameKey,
                    new SlackDigestContent(headline, relevance, decisions, nextSteps, risks,
                            clientAsks, people, topics, confidence),
                    evidence));
        }

        // 6. The flood guard. Counts only — which companies were dropped is precisely what
        //    this module does not write down.
        if (proposals.size() > MAX_MENTIONS_PER_CALL) {
            log.warnf("Slack mention extraction returned %d mentions for one call — keeping %d, HIGH first (model=%s, prompt=%s)",
                    proposals.size(), MAX_MENTIONS_PER_CALL, mentionModel, SlackMentionPrompts.PROMPT_VERSION);
            proposals = capped(proposals);
        }

        // 7. Two mentions of one client in one call are one row, as they are across chunks.
        List<Mention> mentions = new ArrayList<>();
        List<UnmatchedSighting> unmatched = new ArrayList<>();
        for (Proposal proposal : proposals) {
            if (proposal.clientUuid() != null) {
                mentions.add(new Mention(proposal.clientUuid(), proposal.content(), proposal.evidence()));
            } else {
                unmatched.add(new UnmatchedSighting(proposal.displayName(), proposal.nameKey(), proposal.evidence()));
            }
        }
        return new Reading(mergeMentions(mentions), mergeSightings(unmatched), droppedAsColleague, false);
    }

    /**
     * The lower-cased, punctuation-free, whitespace-collapsed form a company name is
     * deduplicated by — "NN", "nn" and " NN " are one hint, not three. Public because the
     * hints table is keyed by it and a second implementation of this rule would be a second
     * answer to "have we seen this company before".
     *
     * <p>Punctuation is folded to a space rather than kept, for two reasons. The obvious one
     * is matching: a colleague writes "Novo Nordisk A/S" on Monday and "Novo Nordisk AS" on
     * Tuesday, and those are one company. The load-bearing one is that this key travels in a
     * URL path segment — {@code POST /accounts/slack-suggestions/{nameKey}/decision} — and a
     * company name is free text the model wrote. An "A/S" would put an encoded slash in a
     * path segment, which Next's dynamic-segment matcher and RESTEasy's {@code @PathParam}
     * decoding each handle in their own way; the name would become undecidable in the
     * browser and nobody would learn why. Folding it here keeps the key spellable.
     *
     * <p><b>Combining marks are stripped because the database strips them, and the asymmetry
     * only runs one way.</b> {@code slack_unmatched_company.name_key} is the PRIMARY KEY and
     * the sighting table's unique key carries it, both under {@code utf8mb4_general_ci},
     * which compares an accented Latin letter equal to its base one — é is e, ö is o. If this
     * method did not fold them, a channel-day in which one colleague writes "Nestlé" and
     * another "Nestle" would produce two keys here, miss twice on the lookup that precedes
     * the insert, and meet the collation at flush as ERROR 1062 — which rolls back the whole
     * day and, since the cursor moves in that same transaction, wedges the channel. The safe
     * direction is to fold MORE here than the collation does, never less: two keys the
     * database considers one is the bug, while one key is at worst two companies filed as one
     * hint. NFD then {@code \p{M}} is exactly that fold: it leaves æ and ø alone, which have
     * no canonical decomposition and which {@code general_ci} also leaves alone — so "ørsted"
     * is not "orsted" at either end — and it folds å to a, which is what the collation does
     * with it as well.
     */
    public static String nameKey(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        // \p{IsAlphabetic} rather than [a-z]: æ, ø and å are letters in every name this reads.
        return folded.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------------

    /** One mention that survived validation; exactly one of {@code clientUuid} and {@code nameKey} is set. */
    private record Proposal(String clientUuid, String displayName, String nameKey,
                            SlackDigestContent content, List<Integer> evidence) {
    }

    /**
     * The ids the model was actually shown, cut off where {@code appendAccounts} cuts the
     * block off. An id from beyond the 400th line could only ever be one the model invented,
     * and invented ids are what this set exists to refuse.
     */
    private static Set<String> allowlistIds(List<SlackMentionPrompts.Account> allowlist) {
        if (allowlist == null) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (SlackMentionPrompts.Account account : allowlist) {
            if (account == null || account.uuid() == null || account.uuid().isBlank()) {
                continue;
            }
            if (ids.size() >= SlackMentionPrompts.MAX_ACCOUNTS_IN_PROMPT) {
                break;
            }
            ids.add(account.uuid().trim());
        }
        return ids;
    }

    /**
     * Every name that is a colleague rather than a company. Full names and their first
     * names both go in: the prompt renders a mention as a first name, so "Nicolas" is what
     * a confused model writes into {@code companyName}, while a pasted signature can just
     * as easily carry "Nicolas Vestergaard".
     */
    private static Set<String> colleagueKeys(List<String> participants, Collection<String> colleagueNames) {
        Set<String> keys = new HashSet<>(OWN_COMPANY_KEYS);
        addNameKeys(keys, participants);
        addNameKeys(keys, colleagueNames);
        return keys;
    }

    private static void addNameKeys(Set<String> keys, Collection<String> names) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            String key = nameKey(name);
            if (key.isEmpty()) {
                continue;
            }
            keys.add(key);
            int space = key.indexOf(' ');
            if (space > 0) {
                keys.add(key.substring(0, space));
            }
        }
    }

    /**
     * The line numbers this chunk actually held, lowest first. Sorted because the sync takes
     * the FIRST cited line as the row's permalink and "first" has to mean earliest;
     * deduplicated because the row's message count is distinct cited messages.
     */
    private static List<Integer> evidence(JsonNode element, int lineCount) {
        JsonNode array = element.path("evidence");
        if (!array.isArray()) {
            return List.of();
        }
        TreeSet<Integer> lines = new TreeSet<>();
        for (JsonNode value : array) {
            if (!value.isNumber() || !value.canConvertToInt()) {
                continue;
            }
            int line = value.asInt();
            if (line >= 1 && line <= lineCount) {
                lines.add(line);
            }
        }
        return lines.stream().limit(MAX_EVIDENCE_PER_MENTION).toList();
    }

    /**
     * A stored row is never {@code NONE}. The digest lane may honestly conclude that a whole
     * day was chatter, but a mention that got this far carries a headline and cited lines,
     * and calling that irrelevant would be the row arguing with itself.
     */
    private static String floor(String relevance) {
        return SlackDigestContent.RELEVANCE_HIGH.equals(relevance)
                ? SlackDigestContent.RELEVANCE_HIGH : SlackDigestContent.RELEVANCE_LOW;
    }

    /** {@code HIGH} first, each group in the model's own order, then cut. */
    private static List<Proposal> capped(List<Proposal> proposals) {
        List<Proposal> ordered = new ArrayList<>();
        for (Proposal proposal : proposals) {
            if (SlackDigestContent.RELEVANCE_HIGH.equals(proposal.content().relevance())) {
                ordered.add(proposal);
            }
        }
        for (Proposal proposal : proposals) {
            if (!SlackDigestContent.RELEVANCE_HIGH.equals(proposal.content().relevance())) {
                ordered.add(proposal);
            }
        }
        return List.copyOf(ordered.subList(0, MAX_MENTIONS_PER_CALL));
    }

    // ------------------------------------------------------------------------
    // Merging — one client, one row, however many times the day named it
    // ------------------------------------------------------------------------

    /**
     * One mention per client. A day that names E-Nettet in the morning and again after lunch
     * is one row about E-Nettet, not two: the row is keyed by (client, channel, day) in the
     * database, so merging here is what stops a second upsert overwriting the first with
     * half the day.
     */
    static List<Mention> mergeMentions(List<Mention> mentions) {
        if (mentions.size() < 2) {
            return List.copyOf(mentions);
        }
        Map<String, List<Mention>> byClient = new LinkedHashMap<>();
        for (Mention mention : mentions) {
            byClient.computeIfAbsent(mention.clientUuid(), key -> new ArrayList<>()).add(mention);
        }
        List<Mention> merged = new ArrayList<>();
        for (Map.Entry<String, List<Mention>> entry : byClient.entrySet()) {
            List<Mention> parts = entry.getValue();
            merged.add(parts.size() == 1 ? parts.get(0) : merge(entry.getKey(), parts));
        }
        return List.copyOf(merged);
    }

    /**
     * The union, in the order the day said it. Confidence is the MINIMUM of the parts: a row
     * is only as trustworthy as its weakest reading, and an average would let a confident
     * sentence vouch for a doubtful one.
     */
    private static Mention merge(String clientUuid, List<Mention> parts) {
        String relevance = parts.stream()
                .anyMatch(part -> SlackDigestContent.RELEVANCE_HIGH.equals(part.content().relevance()))
                ? SlackDigestContent.RELEVANCE_HIGH : SlackDigestContent.RELEVANCE_LOW;
        double confidence = parts.stream().mapToDouble(part -> part.content().confidence()).min().orElse(0.0d);

        TreeSet<Integer> evidence = new TreeSet<>();
        parts.forEach(part -> evidence.addAll(part.evidence()));

        SlackDigestContent content = new SlackDigestContent(
                headline(parts, relevance), relevance,
                mergeItems(parts, SlackDigestContent::decisions),
                mergeItems(parts, SlackDigestContent::nextSteps),
                mergeNotes(parts, SlackDigestContent::risks),
                mergeNotes(parts, SlackDigestContent::clientAsks),
                mergePeople(parts), mergeTopics(parts), confidence);
        return new Mention(clientUuid, content, List.copyOf(evidence));
    }

    /**
     * The headlines of the parts that carry the row's relevance, joined. A {@code LOW} part's
     * headline is dropped the moment a {@code HIGH} one exists — "Møde med Claims i den
     * kommende uge" is not what the owner should read first on a day that also lost a
     * deadline. Two {@code HIGH} readings are both shown, joined by "; " and cut at the
     * column's 200.
     */
    private static String headline(List<Mention> parts, String relevance) {
        LinkedHashSet<String> headlines = new LinkedHashSet<>();
        for (Mention part : parts) {
            if (relevance.equals(part.content().relevance()) && part.content().headline() != null) {
                headlines.add(part.content().headline());
            }
        }
        String joined = String.join("; ", headlines);
        return AccountSlackDigestService.clean(joined, AccountSlackDigestService.MAX_HEADLINE_CHARS);
    }

    private static List<SlackDigestContent.Item> mergeItems(
            List<Mention> parts, Function<SlackDigestContent, List<SlackDigestContent.Item>> field) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<SlackDigestContent.Item> out = new ArrayList<>();
        for (Mention part : parts) {
            for (SlackDigestContent.Item item : field.apply(part.content())) {
                if (out.size() >= AccountSlackDigestService.MAX_ITEMS_PER_LIST) {
                    break;
                }
                if (seen.add(item.text().toLowerCase(Locale.ROOT))) {
                    out.add(item);
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<SlackDigestContent.Note> mergeNotes(
            List<Mention> parts, Function<SlackDigestContent, List<SlackDigestContent.Note>> field) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<SlackDigestContent.Note> out = new ArrayList<>();
        for (Mention part : parts) {
            for (SlackDigestContent.Note note : field.apply(part.content())) {
                if (out.size() >= AccountSlackDigestService.MAX_ITEMS_PER_LIST) {
                    break;
                }
                if (seen.add(note.text().toLowerCase(Locale.ROOT))) {
                    out.add(note);
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<SlackDigestContent.Person> mergePeople(List<Mention> parts) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<SlackDigestContent.Person> out = new ArrayList<>();
        for (Mention part : parts) {
            for (SlackDigestContent.Person person : part.content().clientPeople()) {
                if (out.size() >= AccountSlackDigestService.MAX_PEOPLE) {
                    break;
                }
                if (seen.add(person.name().toLowerCase(Locale.ROOT))) {
                    out.add(person);
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<String> mergeTopics(List<Mention> parts) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (Mention part : parts) {
            for (String topic : part.content().topics()) {
                if (out.size() >= AccountSlackDigestService.MAX_TOPICS) {
                    break;
                }
                if (seen.add(topic.toLowerCase(Locale.ROOT))) {
                    out.add(topic);
                }
            }
        }
        return List.copyOf(out);
    }

    /** One sighting per company per day, however many times the day named it. */
    static List<UnmatchedSighting> mergeSightings(List<UnmatchedSighting> sightings) {
        if (sightings.size() < 2) {
            return List.copyOf(sightings);
        }
        Map<String, UnmatchedSighting> byName = new LinkedHashMap<>();
        for (UnmatchedSighting sighting : sightings) {
            UnmatchedSighting seen = byName.get(sighting.nameKey());
            if (seen == null) {
                byName.put(sighting.nameKey(), sighting);
                continue;
            }
            TreeSet<Integer> evidence = new TreeSet<>(seen.evidence());
            evidence.addAll(sighting.evidence());
            // The spelling first seen wins, as the hints table's display_name does.
            byName.put(sighting.nameKey(),
                    new UnmatchedSighting(seen.displayName(), seen.nameKey(), List.copyOf(evidence)));
        }
        return List.copyOf(byName.values());
    }

    /** Chunk-local line numbers into the day's own numbering. */
    private static List<Integer> shift(List<Integer> evidence, int offset) {
        if (offset == 0) {
            return evidence;
        }
        return evidence.stream().map(line -> line + offset).toList();
    }
}
