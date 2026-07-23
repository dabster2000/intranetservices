package dk.trustworks.intranet.recruitmentservice.ai;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *   <li>Model output is <b>untrusted</b>: enums are valueOf-guarded,
 *       specializations must be inside the practice catalog, free-text
 *       values are trimmed/capped/control-char-stripped, evidence is
 *       mandatory. Anything invalid is silently dropped; a section with
 *       nothing valid left appends no event.</li>
 *   <li>AI text (values, evidence, bullets) lives exclusively in the
 *       event's pii section — payload carries structural facts only.</li>
 * </ul>
 * OpenAI failure/refusal ("{}" / blank) throws — the reactor's 2-attempt
 * posture retries once via catch-up, then skips; the regenerate endpoint
 * surfaces a 500.
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

    /** Combined intake+brief output budget (contract §5) — keeps the in-tx call bounded. */
    static final int MAX_OUTPUT_TOKENS = 2000;
    static final int MAX_EVIDENCE_CHARS = 200;
    static final int MAX_BULLET_CHARS = 400;
    /** Contract §4.3: the brief is 3–5 bullets — fewer than 3 valid ⇒ no brief. */
    static final int MIN_BULLETS = 3;
    static final int MAX_BULLETS = 5;
    static final int MAX_LANGUAGES = 10;
    static final int MAX_LANGUAGE_CHARS = 120;
    static final int MAX_EMPLOYER_CHARS = 200;

    private static final String SCHEMA_NAME = "RecruitmentAiIntake";

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
     * @throws IllegalStateException when OpenAI fails/refuses (empty output)
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
     * @throws IllegalStateException when OpenAI fails/refuses (empty output)
     *                               or a transaction is unexpectedly active
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
            model = openAIService.getDefaultModel();
            json = openAIService.askQuestionWithSchema(prepared.systemPrompt(),
                    AiIntakePrompts.userPrompt(prepared.candidateName(),
                            prepared.position().getTitle(),
                            prepared.position().getPracticeName(), prepared.answersText(),
                            prepared.cv().text()),
                    prepared.schema(), SCHEMA_NAME, null, null, MAX_OUTPUT_TOKENS, false);
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
            root = objectMapper.readTree(output.json());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "AI intake generation returned unparseable output for candidate "
                            + prepared.candidate().getUuid());
        }

        String generationId = UUID.randomUUID().toString();

        if (prepared.intakeOn()) {
            List<Suggestion> suggestions = validateSuggestions(root.path("suggestions"), prepared.catalog());
            if (!suggestions.isEmpty()) {
                appendSuggestionsEvent(prepared.candidate(), prepared.anchor(), prepared.position(),
                        prepared.visibility(), generationId, origin, sourceEventSeq,
                        output.model(), suggestions);
            }
        }
        if (prepared.briefOn()) {
            List<String> bullets = validateBullets(root.path("brief"));
            if (!bullets.isEmpty()) {
                appendBriefEvent(prepared.candidate(), prepared.anchor(), prepared.position(),
                        prepared.visibility(), generationId, origin, sourceEventSeq,
                        output.model(), bullets);
            }
        }
    }

    // ---- Validation (the hard guard — model output is untrusted) ---------------

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
     * Bullets: trimmed, control-char-stripped, capped in length and count.
     * Contract §4.3 mandates 3–5 bullets — when fewer than
     * {@link #MIN_BULLETS} non-empty bullets survive the filtering, the
     * whole brief is treated as absent (empty list ⇒ no
     * {@code AI_BRIEF_GENERATED} event). The schema also declares
     * minItems/maxItems, but the model output stays untrusted.
     */
    List<String> validateBullets(JsonNode node) {
        List<String> bullets = stringList(node).stream()
                .map(b -> sanitize(b, MAX_BULLET_CHARS))
                .filter(b -> b != null && !b.isBlank())
                .limit(MAX_BULLETS)
                .toList();
        return bullets.size() < MIN_BULLETS ? List.of() : bullets;
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

    /** Trim, strip control chars, truncate at the cap; blank ⇒ null. (Evidence/bullets.) */
    static String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        if (cleaned.isEmpty()) {
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
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        if (cleaned.isEmpty() || cleaned.length() > maxLength) {
            return null;
        }
        return cleaned;
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
