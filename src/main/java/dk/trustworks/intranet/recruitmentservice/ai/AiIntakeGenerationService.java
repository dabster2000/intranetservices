package dk.trustworks.intranet.recruitmentservice.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplicationAnswer;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.services.PublicApplyQuestions;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSpecializationCatalog;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The P9 intake generation pipeline (AI spec §4.1/§4.3, contract §5.1):
 * one OpenAI round-trip that produces field suggestions and/or a candidate
 * brief for one anchor application, followed by a hard server-side
 * constraint check and event append. The pipeline phases (prepare →
 * model call → validate+append) are shared verbatim by
 * {@link AiIntakeReactor} (origin {@code reactor}, via {@link #generate}
 * — in the chassis delivery transaction) and the synchronous regenerate
 * endpoint (origin {@code regenerate}, via {@link #generateUntransacted}
 * — OpenAI outside any transaction, security review M1) so the two paths
 * can never drift.
 * <p>
 * Privacy/injection posture:
 * <ul>
 *   <li>Every OpenAI call passes {@code store=false} (suppressed logging
 *       path — candidate PII goes to OpenAI, never to logs).</li>
 *   <li>Model output is <b>untrusted</b>: the answer must be exactly one
 *       JSON document (trailing scratchpad fails the parse), enums are
 *       valueOf-guarded, specializations must be inside the practice
 *       catalog, free-text values are trimmed/capped/control-char-stripped,
 *       evidence is mandatory. Anything invalid is silently dropped; a
 *       section with nothing valid left appends no event.</li>
 *   <li>AI text (values, evidence, bullets) lives exclusively in the
 *       event's pii section — payload carries structural facts only.</li>
 * </ul>
 * Two failure classes, both fail closed rather than persist what came back:
 * OpenAI failure/refusal ("{}" / blank) throws from {@link #callModel}, and
 * contaminated output — unparseable, trailing tokens, a brief that is not
 * the promised array, or a "bullet" that is really model scratchpad —
 * throws from {@link #validateBullets}. Either way the reactor's 2-attempt
 * posture retries once via catch-up, then skips; the regenerate endpoint
 * surfaces a 500. A merely <em>thin</em> brief (under three bullets) stays
 * a silent no-op, as contract §4.3 requires.
 */
@JBossLog
@ApplicationScoped
public class AiIntakeGenerationService {

    public static final String ORIGIN_REACTOR = "reactor";
    public static final String ORIGIN_REGENERATE = "regenerate";

    /** Suggestion field codes (contract §4.1) — recorded verbatim in events. */
    public static final String FIELD_EDUCATION_LEVEL = "EDUCATION_LEVEL";
    public static final String FIELD_EXPERIENCE_LEVEL = "EXPERIENCE_LEVEL";
    public static final String FIELD_SPECIALIZATIONS = "SPECIALIZATIONS";
    public static final String FIELD_LANGUAGES = "LANGUAGES";
    public static final String FIELD_CURRENT_EMPLOYER = "CURRENT_EMPLOYER";

    /**
     * Combined intake+brief output budget (contract §5) — keeps the in-tx call bounded.
     * <p>
     * On a reasoning-class model this budget covers hidden reasoning tokens FIRST and the
     * visible answer second: at 2 000 the model regularly spent the whole budget thinking and
     * answered 2xx with no output text, which surfaces as {@code "{}"} and a 500 ("no usable
     * output") — the 2026-08-01 staging incident, and the same defect the employee-documents
     * feature hit and fixed by raising its budgets. 8 192 matches that precedent. The budget is
     * a spend ceiling, not a target: a successful call still bills only the tokens it uses.
     */
    static final int MAX_OUTPUT_TOKENS = 8192;
    static final int MAX_EVIDENCE_CHARS = 200;
    static final int MAX_BULLET_CHARS = 400;
    /** Contract §4.3: the brief is 3–5 bullets — fewer than 3 valid ⇒ no brief. */
    static final int MIN_BULLETS = 3;
    static final int MAX_BULLETS = 5;
    static final int MAX_LANGUAGES = 10;
    static final int MAX_LANGUAGE_CHARS = 120;
    static final int MAX_EMPLOYER_CHARS = 200;

    private static final String SCHEMA_NAME = "RecruitmentAiIntake";

    /**
     * The model answer is parsed as EXACTLY ONE JSON document — the injected
     * {@link ObjectMapper} is deliberately not used here.
     * <p>
     * Jackson leaves {@code FAIL_ON_TRAILING_TOKENS} off by default, so
     * {@code readTree} happily parses the leading object of
     * {@code {"brief":[…]}<model scratchpad>} and drops the rest on the floor.
     * That silence is what let a contaminated 2026-08 production generation
     * (candidate 824f6d35, {@code gpt-5.6-terra}, {@code brief-v1}) reach
     * {@code recruitment_events.pii} carrying the model's own deliberation and
     * harmony channel markers ("assistant to=system" / "assistant to=final")
     * behind the real bullets. Trailing tokens now fail the parse, which is
     * the same posture {@code IndividualBonusAiService} already takes on
     * untrusted structured output.
     */
    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Structural tells that a "bullet" is the model talking to itself rather
     * than describing the candidate. Deliberately STRUCTURAL, never semantic:
     * these byte sequences cannot occur in the short descriptive Danish prose
     * the brief is specified to be, whereas a word list ("json", "schema")
     * would false-reject a real bullet about an IT consultant's CV. The hard
     * guards against long scratchpad dumps are the {@link #MAX_BULLET_CHARS}
     * cap (now a rejection, not a truncation) and the single-document parse
     * above; this list catches the short leaks those two miss.
     */
    private static final List<String> SCRATCHPAD_MARKERS = List.of(
            "assistant to=",   // harmony channel routing, e.g. "assistant to=final"
            "<|", "|>",        // any harmony control token (<|start|>, <|channel|>, …)
            "```",             // a code fence around the model's own JSON
            "\"brief\":", "\"bullets\":", "\"suggestions\":"); // a re-emitted envelope

    /**
     * The extraction model for the TEXT path (shared with
     * {@link AiReferralTriageReactor}, mirroring how {@code draft-model} is shared by the
     * composer and the digests). Kept off the global {@code openai.model} deliberately:
     * that default is {@code gpt-5-nano}, whose reasoning spend caused the 2026-08-01
     * empty-output failures, and it retires 2026-12-11. Override per env with
     * {@code DK_TRUSTWORKS_RECRUITMENT_AI_EXTRACTION_MODEL}.
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.ai.extraction-model", defaultValue = "gpt-5.6-terra")
    String extractionModel;

    /**
     * {@code reasoning.effort} for the extraction call. Reading facts out of a CV is
     * mechanical work, so a low effort keeps the output budget for the actual answer.
     * <p>
     * Declared {@code Optional<String>} deliberately: an EMPTY value must mean "omit the
     * reasoning node" (required if {@link #extractionModel} is ever pointed at a
     * non-reasoning gpt-4o-family model, which rejects the node). A plain {@code String}
     * cannot express that — SmallRye converts an empty-but-present value to null, and
     * because the raw value is non-null the {@code defaultValue} never rescues it, so the
     * whole application fails to boot with SRCFG00040. That is the same trap this repo
     * already has with {@code cvtool.username}/{@code cvtool.password}.
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.ai.extraction-reasoning-effort", defaultValue = "low")
    Optional<String> extractionReasoningEffort;

    /** The effort to send, or null to omit the reasoning node entirely. */
    private String reasoningEffortOrNull() {
        return extractionReasoningEffort.filter(e -> !e.isBlank()).orElse(null);
    }

    @Inject
    ObjectMapper objectMapper;

    @Inject
    OpenAIService openAIService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    RecruitmentSpecializationCatalog specializationCatalog;

    @Inject
    CvContentExtractor cvContentExtractor;

    @Inject
    dk.trustworks.intranet.recruitmentservice.services.RecruitmentAiFeatureFlag aiFlags;

    /** One validated suggestion: {@code value} is a String or a List of Strings. */
    record Suggestion(String field, Object value, String evidence) {
    }

    /**
     * Phase-1 output: every read-only input the model round-trip and the
     * append phase need, gathered before any network call.
     */
    private record PreparedGeneration(RecruitmentCandidate candidate, RecruitmentApplication anchor,
                                      RecruitmentPosition position, List<String> catalog,
                                      RecruitmentEventVisibility visibility,
                                      boolean intakeOn, boolean briefOn,
                                      String systemPrompt, ObjectNode schema,
                                      String candidateName, String answersText,
                                      CvContentExtractor.CvContent cv) {
    }

    /** Phase-2 output: the model's raw JSON plus the model name that produced it. */
    private record ModelOutput(String model, String json) {
    }

    /**
     * Run one generation round-trip for the anchor application and append
     * the resulting AI events in the caller's transaction.
     * <p>
     * <b>Reactor path only.</b> {@code handle()} runs inside the chassis
     * delivery transaction by design, so the OpenAI round-trip is in-tx
     * here — contract-accepted (§5): deliveries are effectively serialized
     * and capped at {@link #MAX_OUTPUT_TOKENS}. Synchronous request paths
     * must use {@link #generateUntransacted} instead (security review M1 —
     * concurrent in-tx OpenAI calls pin pool connections).
     *
     * @param candidate         the candidate (subject of the events)
     * @param anchor            the anchor application (subject; its position
     *                          provides title/practice/track)
     * @param origin            {@link #ORIGIN_REACTOR} or {@link #ORIGIN_REGENERATE}
     * @param sourceEventSeq    the triggering event's seq (null for regenerate
     *                          — the key is then omitted from payload)
     * @param triggerVisibility the triggering event's visibility (null for
     *                          regenerate — derived from the position's track)
     * @throws IllegalStateException when OpenAI fails/refuses (empty output) or
     *                               returns contaminated output ({@link #validateBullets})
     */
    public void generate(RecruitmentCandidate candidate, RecruitmentApplication anchor,
                         String origin, Long sourceEventSeq,
                         RecruitmentEventVisibility triggerVisibility) {
        PreparedGeneration prepared = prepare(candidate, anchor, triggerVisibility);
        if (prepared == null) {
            return;
        }
        ModelOutput output = callModel(prepared);
        validateAndAppend(prepared, output, origin, sourceEventSeq);
    }

    /**
     * The M1-safe variant for synchronous request paths (the regenerate
     * endpoint): no transaction may be active when it runs. Inputs are
     * gathered in a short read-only transaction that <em>completes</em> —
     * returning its pooled connection — before the OpenAI round-trip; the
     * round-trip itself runs untransacted (up to the ~110 s read timeout
     * without holding any DB resource); validate+append then runs in a
     * fresh short transaction of its own, so suggestions and brief still
     * commit atomically. Behavior (events, errors, origins) is identical
     * to {@link #generate}.
     *
     * @throws IllegalStateException when OpenAI fails/refuses (empty output),
     *                               returns contaminated output
     *                               ({@link #validateBullets}), or a
     *                               transaction is unexpectedly active
     */
    public void generateUntransacted(RecruitmentCandidate candidate, RecruitmentApplication anchor,
                                     String origin, Long sourceEventSeq,
                                     RecruitmentEventVisibility triggerVisibility) {
        if (QuarkusTransaction.isActive()) {
            // A surrounding tx would be held across the OpenAI round-trip —
            // exactly the pool-exhaustion posture this method exists to avoid.
            throw new IllegalStateException(
                    "generateUntransacted must not be called inside a transaction");
        }
        // Phase 1 — read-only input gathering in its own completed tx.
        PreparedGeneration prepared = QuarkusTransaction.requiringNew()
                .call(() -> prepare(candidate, anchor, triggerVisibility));
        if (prepared == null) {
            return;
        }
        // Phase 2 — the OpenAI round-trip, untransacted: no DB connection held.
        ModelOutput output = callModel(prepared);
        // Phase 3 — validate + append both events atomically in a fresh tx.
        QuarkusTransaction.requiringNew()
                .run(() -> validateAndAppend(prepared, output, origin, sourceEventSeq));
    }

    /** Phase 1: read-only queries + CV extraction; null ⇒ nothing to generate. */
    private PreparedGeneration prepare(RecruitmentCandidate candidate, RecruitmentApplication anchor,
                                       RecruitmentEventVisibility triggerVisibility) {
        boolean intakeOn = aiFlags.isIntakeEnabled();
        boolean briefOn = aiFlags.isBriefEnabled();
        if (!intakeOn && !briefOn) {
            return null;
        }
        RecruitmentPosition position = RecruitmentPosition.findById(anchor.getPositionUuid());
        if (position == null) {
            log.warnf("AI intake: anchor application %s has no position row — skipping", anchor.getUuid());
            return null;
        }

        List<String> catalog = position.getPracticeUuid() == null
                ? List.of()
                : specializationCatalog.forPractice(position.getPracticeUuid());
        String candidateName = (nullSafe(candidate.getFirstName()) + " "
                + nullSafe(candidate.getLastName())).trim();
        String answersText = answersText(candidate.getUuid(), anchor.getUuid());
        CvContentExtractor.CvContent cv = cvContentExtractor.extract(candidate.getUuid());

        ObjectNode schema = AiIntakePrompts.schema(intakeOn, briefOn);
        String system = AiIntakePrompts.systemPrompt(intakeOn, briefOn,
                enumNames(CandidateEducationLevel.values()),
                enumNames(CandidateExperienceLevel.values()),
                catalog);
        return new PreparedGeneration(candidate, anchor, position, catalog,
                effectiveVisibility(triggerVisibility, position), intakeOn, briefOn,
                system, schema, candidateName, answersText, cv);
    }

    /** Phase 2: the OpenAI round-trip — network only, no DB access. */
    private ModelOutput callModel(PreparedGeneration prepared) {
        String json;
        String model;
        if (prepared.cv().hasImage()) {
            // Vision path — the CV is an image (or a rendered page-1 PNG of
            // an image-only PDF). Raw PDF bytes never reach input_image.
            model = openAIService.getVisionModel();
            json = openAIService.askWithSchemaAndImage(prepared.systemPrompt(),
                    AiIntakePrompts.userPromptForImage(prepared.candidateName(),
                            prepared.position().getTitle(),
                            prepared.position().getPracticeName(), prepared.answersText()),
                    prepared.cv().base64Image(), prepared.cv().mimeType(),
                    prepared.schema(), SCHEMA_NAME,
                    null, model, MAX_OUTPUT_TOKENS, false);
        } else {
            // Text path — pinned to the extraction model with an explicit reasoning effort.
            // The vision branch above deliberately keeps its own (non-reasoning) vision model
            // and passes NO effort: gpt-4o-family models reject the reasoning node.
            model = extractionModel;
            json = openAIService.askQuestionWithSchema(prepared.systemPrompt(),
                    AiIntakePrompts.userPrompt(prepared.candidateName(),
                            prepared.position().getTitle(),
                            prepared.position().getPracticeName(), prepared.answersText(),
                            prepared.cv().text()),
                    prepared.schema(), SCHEMA_NAME, null, extractionModel, MAX_OUTPUT_TOKENS, false,
                    reasoningEffortOrNull());
        }
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            // Never log the prompt or output body — just the fact.
            throw new IllegalStateException(
                    "AI intake generation returned no usable output for candidate "
                            + prepared.candidate().getUuid());
        }
        return new ModelOutput(model, json);
    }

    /** Phase 3: parse, constraint-check and append the AI events. */
    private void validateAndAppend(PreparedGeneration prepared, ModelOutput output,
                                   String origin, Long sourceEventSeq) {
        JsonNode root;
        try {
            root = parseSingleJsonDocument(output.json());
        } catch (Exception e) {
            // Structural facts only — the body may hold candidate PII.
            throw new IllegalStateException(
                    "AI intake generation returned unparseable output for candidate "
                            + prepared.candidate().getUuid()
                            + " (model=" + output.model() + ", chars=" + output.json().length()
                            + ", cause=" + e.getClass().getSimpleName() + ")");
        }

        // BOTH sections are constraint-checked before ANYTHING is appended: a
        // contaminated brief must not leave a half-written generation behind
        // (the suggestions event used to be appended first, so a brief that
        // blew up mid-append relied on the transaction rolling back).
        List<Suggestion> suggestions = prepared.intakeOn()
                ? validateSuggestions(root.path("suggestions"), prepared.catalog())
                : List.of();
        List<String> bullets = prepared.briefOn()
                ? validateBullets(root.path("brief"))
                : List.of();

        String generationId = UUID.randomUUID().toString();
        if (!suggestions.isEmpty()) {
            appendSuggestionsEvent(prepared.candidate(), prepared.anchor(), prepared.position(),
                    prepared.visibility(), generationId, origin, sourceEventSeq,
                    output.model(), suggestions);
        }
        if (!bullets.isEmpty()) {
            appendBriefEvent(prepared.candidate(), prepared.anchor(), prepared.position(),
                    prepared.visibility(), generationId, origin, sourceEventSeq,
                    output.model(), bullets);
        }
    }

    // ---- Validation (the hard guard — model output is untrusted) ---------------

    /**
     * Parse the model answer as EXACTLY one JSON document. Anything after the
     * closing brace — the model's scratchpad, a second harmony channel, a
     * trailing code fence — is a parse failure, not something to ignore.
     *
     * @throws com.fasterxml.jackson.core.JsonProcessingException on malformed
     *         input OR on trailing tokens after the first complete value
     */
    static JsonNode parseSingleJsonDocument(String json)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        return STRICT_JSON.readTree(json);
    }

    /**
     * Constraint-check the model's suggestion section (contract §5.1):
     * enums valueOf-guarded, specializations restricted to the practice
     * catalog (empty catalog ⇒ all dropped), free text trimmed/capped/
     * control-char-stripped, evidence mandatory. At most one suggestion
     * per field by construction of the schema.
     */
    List<Suggestion> validateSuggestions(JsonNode node, List<String> catalog) {
        List<Suggestion> out = new ArrayList<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        String education = enumOrNull(CandidateEducationLevel.class, text(node, "educationLevel"));
        addIfEvidence(out, FIELD_EDUCATION_LEVEL, education,
                sanitize(text(node, "educationLevelEvidence"), MAX_EVIDENCE_CHARS));

        String experience = enumOrNull(CandidateExperienceLevel.class, text(node, "experienceLevel"));
        addIfEvidence(out, FIELD_EXPERIENCE_LEVEL, experience,
                sanitize(text(node, "experienceLevelEvidence"), MAX_EVIDENCE_CHARS));

        List<String> specializations = stringList(node.path("specializations")).stream()
                .map(v -> sanitizeListValue(v, 100))
                .filter(v -> v != null && catalog.contains(v))
                .distinct()
                .toList();
        addIfEvidence(out, FIELD_SPECIALIZATIONS, specializations.isEmpty() ? null : specializations,
                sanitize(text(node, "specializationsEvidence"), MAX_EVIDENCE_CHARS));

        List<String> languages = stringList(node.path("languages")).stream()
                .map(v -> sanitizeListValue(v, MAX_LANGUAGE_CHARS))
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .limit(MAX_LANGUAGES)
                .toList();
        addIfEvidence(out, FIELD_LANGUAGES, languages.isEmpty() ? null : languages,
                sanitize(text(node, "languagesEvidence"), MAX_EVIDENCE_CHARS));

        String employer = sanitizeStrict(text(node, "currentEmployer"), MAX_EMPLOYER_CHARS);
        addIfEvidence(out, FIELD_CURRENT_EMPLOYER, employer,
                sanitize(text(node, "currentEmployerEvidence"), MAX_EVIDENCE_CHARS));
        return out;
    }

    /**
     * Bullets: trimmed, control-char-stripped, shape- and content-checked.
     * The schema declares {@code brief} as a nullable array of strings with
     * minItems/maxItems, but model output stays untrusted — a response that
     * is <em>incomplete</em> is never schema-validated by the API at all.
     * <p>
     * Two outcomes, deliberately different:
     * <ul>
     *   <li><b>Thin</b> — the model had little to say: an explicit
     *       {@code null} brief, or fewer than {@link #MIN_BULLETS} non-blank
     *       bullets (contract §4.3). Returns an empty list ⇒ no
     *       {@code AI_BRIEF_GENERATED} event, no error, watermark advances.</li>
     *   <li><b>Contaminated</b> — the section is not the shape the schema
     *       promised (missing, non-array, non-text item) or a "bullet" is
     *       really model scratchpad (over the length cap, or carrying a
     *       {@link #SCRATCHPAD_MARKERS} tell). Throws, so the reactor's
     *       2-attempt posture retries once and the regenerate endpoint
     *       surfaces a 500 — raw model text is never persisted.</li>
     * </ul>
     * The over-cap rule is a REJECTION, not the truncation it used to be:
     * cutting a multi-thousand-character scratchpad dump at
     * {@link #MAX_BULLET_CHARS} is exactly how deliberation prose and an
     * unterminated JSON fragment ended up looking like a bullet in
     * production. Real bullets run ~120 characters; 400 is already 3× that.
     * <p>
     * Sibling failure mode, same root cause: a reasoning model can also
     * spend the whole {@link #MAX_OUTPUT_TOKENS} budget thinking and answer
     * 2xx with no visible text at all (the {@code gpt-5-nano} empty
     * structured output incidents of 2026-07-24 / 2026-08-01). That shape is
     * caught upstream in {@link #callModel} as {@code "{}"}. Both are the
     * hidden reasoning channel leaking into the answer channel; both must
     * fail closed rather than persist whatever came back.
     */
    List<String> validateBullets(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            throw contaminated("brief section absent (the strict schema declares it required)");
        }
        if (node.isNull()) {
            return List.of(); // nullable array — nothing to say, not a failure
        }
        if (!node.isArray()) {
            throw contaminated("brief section is " + node.getNodeType() + ", expected an array");
        }
        List<String> bullets = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw contaminated("brief bullet is " + item.getNodeType() + ", expected a string");
            }
            String bullet = clean(item.asText());
            if (bullet == null) {
                continue; // blank bullet — thin, not contaminated
            }
            if (bullet.length() > MAX_BULLET_CHARS) {
                throw contaminated("brief bullet is " + bullet.length() + " chars, cap is "
                        + MAX_BULLET_CHARS);
            }
            if (looksLikeModelScratchpad(bullet)) {
                throw contaminated("brief bullet carries model-scratchpad markers");
            }
            bullets.add(bullet);
        }
        if (bullets.size() < MIN_BULLETS) {
            return List.of();
        }
        // Over-generation past maxItems is benign — take the first five.
        return List.copyOf(bullets.size() > MAX_BULLETS ? bullets.subList(0, MAX_BULLETS) : bullets);
    }

    /**
     * Whether a cleaned bullet is the model's own deliberation rather than
     * candidate prose. One whole-brief tell is enough: when the model breaks
     * channel discipline on one bullet, the neighbouring bullets are just as
     * likely to be mid-thought drafts, so the caller rejects the section
     * rather than dropping the single item.
     */
    static boolean looksLikeModelScratchpad(String bullet) {
        if (bullet == null || bullet.isEmpty()) {
            return false;
        }
        String probe = bullet.toLowerCase(Locale.ROOT);
        for (String marker : SCRATCHPAD_MARKERS) {
            if (probe.contains(marker)) {
                return true;
            }
        }
        // A bullet that opens as a JSON document is a re-emitted envelope,
        // never a sentence about a candidate.
        char first = probe.charAt(0);
        return first == '{' || first == '[';
    }

    /** Contaminated-output failure — message carries structure only, never model text. */
    private static IllegalStateException contaminated(String detail) {
        return new IllegalStateException("AI intake generation returned contaminated output: " + detail);
    }

    private static void addIfEvidence(List<Suggestion> out, String field, Object value, String evidence) {
        if (value == null || evidence == null || evidence.isBlank()) {
            return; // no value or no evidence ⇒ dropped (contract §5.1)
        }
        out.add(new Suggestion(field, value, evidence));
    }

    // ---- Event append ----------------------------------------------------------

    private void appendSuggestionsEvent(RecruitmentCandidate candidate, RecruitmentApplication anchor,
                                        RecruitmentPosition position, RecruitmentEventVisibility visibility,
                                        String generationId, String origin, Long sourceEventSeq,
                                        String model, List<Suggestion> suggestions) {
        List<Map<String, Object>> piiSuggestions = suggestions.stream()
                .map(s -> {
                    Map<String, Object> entry = new LinkedHashMap<String, Object>();
                    entry.put("id", generationId + ":" + s.field());
                    entry.put("field", s.field());
                    entry.put("value", s.value());
                    entry.put("evidence", s.evidence());
                    return entry;
                })
                .toList();
        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.AI_SUGGESTIONS_GENERATED)
                .candidate(candidate.getUuid())
                .application(anchor.getUuid())
                .position(position.getUuid())
                .actorSystem()
                .visibility(visibility)
                .payload("generation_id", generationId)
                .payload("origin", origin)
                .payload("fields", suggestions.stream().map(Suggestion::field).toList())
                .payload("model", model)
                .payload("prompt_version", AiIntakePrompts.PROMPT_VERSION_INTAKE)
                .pii("suggestions", piiSuggestions);
        if (sourceEventSeq != null) {
            event.payload("source_event_seq", sourceEventSeq);
        }
        eventRecorder.record(event);
    }

    private void appendBriefEvent(RecruitmentCandidate candidate, RecruitmentApplication anchor,
                                  RecruitmentPosition position, RecruitmentEventVisibility visibility,
                                  String generationId, String origin, Long sourceEventSeq,
                                  String model, List<String> bullets) {
        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.AI_BRIEF_GENERATED)
                .candidate(candidate.getUuid())
                .application(anchor.getUuid())
                .position(position.getUuid())
                .actorSystem()
                .visibility(visibility)
                .payload("generation_id", generationId)
                .payload("origin", origin)
                .payload("model", model)
                .payload("prompt_version", AiIntakePrompts.PROMPT_VERSION_BRIEF)
                .pii("bullets", bullets);
        if (sourceEventSeq != null) {
            event.payload("source_event_seq", sourceEventSeq);
        }
        eventRecorder.record(event);
    }

    /**
     * CIRCLE propagation (contract §4, dossier §10 rule 1): CIRCLE when the
     * triggering event was CIRCLE OR the anchor position is partner track —
     * whichever is stricter. The AI events always carry the position
     * subject, so the timeline's fail-closed position-less CIRCLE branch
     * never hides them from circle members.
     */
    static RecruitmentEventVisibility effectiveVisibility(RecruitmentEventVisibility triggerVisibility,
                                                          RecruitmentPosition position) {
        if (triggerVisibility == RecruitmentEventVisibility.CIRCLE
                || position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            return RecruitmentEventVisibility.CIRCLE;
        }
        return RecruitmentEventVisibility.NORMAL;
    }

    // ---- Prompt inputs ---------------------------------------------------------

    /**
     * Labelled form answers: the application-scoped leg when present, else
     * the candidate-scoped leg (unsolicited applicants — V437, findings §P5).
     */
    private String answersText(String candidateUuid, String applicationUuid) {
        List<RecruitmentApplicationAnswer> answers = RecruitmentApplicationAnswer.list(
                "applicationUuid", applicationUuid);
        if (answers.isEmpty()) {
            answers = RecruitmentApplicationAnswer.list("candidateUuid", candidateUuid);
        }
        if (answers.isEmpty()) {
            return null;
        }
        Map<String, PublicApplyQuestions.Question> questions = PublicApplyQuestions.all().stream()
                .collect(Collectors.toMap(PublicApplyQuestions.Question::key, Function.identity()));
        return answers.stream()
                .map(a -> (questions.containsKey(a.getQuestionKey())
                        ? questions.get(a.getQuestionKey()).label()
                        : a.getQuestionKey()) + ": " + nullSafe(a.getAnswer()))
                .collect(Collectors.joining("\n"));
    }

    // ---- Small helpers ---------------------------------------------------------

    private static List<String> enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private static <E extends Enum<E>> String enumOrNull(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            return null; // out-of-enum model output — dropped
        }
    }

    /** Trim + strip control chars, no length policy; blank ⇒ null. */
    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Trim, strip control chars, truncate at the cap; blank ⇒ null. (Evidence.) */
    static String sanitize(String value, int maxLength) {
        String cleaned = clean(value);
        if (cleaned == null) {
            return null;
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength).trim() : cleaned;
    }

    /**
     * Strict variant for VALUES that get persisted onto the candidate:
     * over-cap values are DROPPED (null), not truncated — a truncated
     * employer/language is a fabrication, not a suggestion (contract §5.1,
     * test contract §8.3).
     */
    static String sanitizeStrict(String value, int maxLength) {
        String cleaned = clean(value);
        return cleaned == null || cleaned.length() > maxLength ? null : cleaned;
    }

    /**
     * List-value variant of {@link #sanitizeStrict}: additionally strips
     * quotes/backslashes — the candidate-update path rejects them in
     * tags/specializations, and an accepted suggestion must be applicable
     * without a 400.
     */
    static String sanitizeListValue(String value, int maxLength) {
        String cleaned = sanitizeStrict(value, maxLength);
        if (cleaned == null) {
            return null;
        }
        String stripped = cleaned.replace("\"", "").replace("\\", "").trim();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
