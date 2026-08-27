package dk.trustworks.intranet.recruitmentservice.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.recruitmentservice.dto.FormAnswer;
import dk.trustworks.intranet.recruitmentservice.dto.RoomPrepResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomPrepResponse.PrepSubject;
import dk.trustworks.intranet.recruitmentservice.dto.RoomSuggestRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomSuggestResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomSuggestResponse.RoomFactSuggestion;
import dk.trustworks.intranet.recruitmentservice.dto.RoomSuggestResponse.RoomSubjectTag;
import dk.trustworks.intranet.recruitmentservice.dto.RoomTidyRequest;
import dk.trustworks.intranet.recruitmentservice.dto.RoomTidyResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RoomTidyResponse.TidySubject;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentFactVocabulary;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardGuidance;
import dk.trustworks.intranet.recruitmentservice.model.ScorecardGuidanceCatalog;
import dk.trustworks.intranet.recruitmentservice.services.CandidateProfileReadService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Interview Room's AI features (room spec 2026-08-26 §9), all behind
 * their own {@code recruitment.ai.interview-room.*} flags (resource-gated)
 * on the existing OpenAI path. The binding rule: <em>AI may reorganise,
 * expand and cross-check a human's words. It may never produce evidence,
 * a score, or a recommendation.</em>
 * <p>
 * Posture copied from {@link AiIntakeGenerationService}: {@code store=false}
 * on every call, exactly-one-JSON-document parsing (trailing scratchpad
 * fails), every model field valueOf-/vocabulary-guarded, anything invalid
 * silently dropped, and the OpenAI round-trip runs UNTRANSACTED — event
 * appends happen in their own short transaction afterwards.
 * <p>
 * Compliance logging (spec §9): fact extraction appends
 * {@code AI_SUGGESTIONS_GENERATED} ({@code payload.origin=INTERVIEW_ROOM}),
 * the prep pack the same with {@code origin=INTERVIEW_ROOM_PREP}, and Tidy
 * appends {@code AI_NOTES_TIDIED} — structural only, the prose reaches the
 * store exclusively via {@code SCORECARD_SUBMITTED.pii} at land.
 */
@JBossLog
@ApplicationScoped
public class InterviewRoomAiService {

    static final int MAX_OUTPUT_TOKENS = 8192;
    static final int MAX_VALUE_CHARS = 300;
    static final int MAX_EVIDENCE_CHARS = 200;
    static final int MAX_INPUT_LINES = 200;
    static final int MAX_PROSE_CHARS = 2000;

    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    /** Nothing usable came back — the room simply shows no chips. */
    private static final RoomSuggestResponse EMPTY_SUGGESTION =
            new RoomSuggestResponse(List.of(), List.of());

    /** Shares the intake/triage extraction model (see {@link AiIntakeGenerationService}). */
    @ConfigProperty(name = "dk.trustworks.recruitment.ai.extraction-model",
            defaultValue = "gpt-5.6-terra")
    String extractionModel;

    @Inject
    OpenAIService openAIService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    CandidateProfileReadService profileReadService;

    @Inject
    CvContentExtractor cvContentExtractor;

    // ------------------------------------------------------------------
    // Fact extraction (§5.4)
    // ------------------------------------------------------------------

    /**
     * Read the given note lines: propose vocabulary-keyed facts, and say
     * which scorecard subject each line is evidence for.
     * <p>
     * The two halves have deliberately different postures. Facts are
     * <em>ephemeral</em> — a human accepts each chip via the fact write,
     * which records {@code AI_SUGGESTION_RESOLVED}; the model never writes
     * a fact (spec §5.4). Subject tags are note metadata that never reaches
     * the ledger, so the room applies them directly and ⌘1–⌘6 overrides
     * them; there is nothing to un-write, so there is nothing to accept.
     * <p>
     * Both anchor by {@code lineIndex} into the caller's own array, so the
     * line list is NOT compacted here — a blank entry keeps its slot
     * ({@link #sanitizeLinesKeepingIndex}) or every later index would point
     * at the wrong line. Appends one {@code AI_SUGGESTIONS_GENERATED} per
     * call (skipped when nothing valid came back).
     *
     * @param subjectCodes the interview's own scorecard subjects — resolved
     *                     by the resource from the position's template, never
     *                     taken from the request body
     */
    public RoomSuggestResponse suggest(RecruitmentInterview interview,
                                       RecruitmentCandidate candidate,
                                       RoomSuggestRequest request,
                                       List<String> subjectCodes,
                                       UUID actor) {
        List<String> lines = sanitizeLinesKeepingIndex(request == null ? null : request.lines());
        if (lines.stream().allMatch(String::isBlank)) {
            return EMPTY_SUGGESTION;
        }
        Set<String> allowedSubjects = subjectCodes == null
                ? Set.of() : new LinkedHashSet<>(subjectCodes);
        LocalDate today = LocalDate.now(COPENHAGEN);
        String raw = callUntransacted(() -> openAIService.askQuestionWithSchema(
                InterviewRoomPrompts.extractionSystem(List.copyOf(allowedSubjects)),
                InterviewRoomPrompts.extractionUser(today, lines),
                InterviewRoomPrompts.extractionSchema(), "RoomFactExtraction",
                "{\"suggestions\":[],\"subjectTags\":[]}",
                extractionModel, MAX_OUTPUT_TOKENS, false, "low"));

        RoomSuggestResponse validated =
                validateExtraction(raw, allowedSubjects, lines.size());
        // Only the FACT half is an AI Act suggestion event: a subject tag
        // carries no candidate assertion, only which heading a line sits
        // under, and the note text it classifies is already the human's own.
        if (!validated.suggestions().isEmpty()) {
            recordGenerated(interview, candidate, actor, "INTERVIEW_ROOM",
                    validated.suggestions().size(), suggestionsPii(validated.suggestions()));
        }
        return validated;
    }

    // ------------------------------------------------------------------
    // Tidy + alignment (§9)
    // ------------------------------------------------------------------

    /**
     * Tidy the tagged lines into per-subject prose. Server-enforced: only
     * subjects present in the input may appear in the output ("writes
     * nothing into a subject that has no lines — it will not fill a gap"),
     * and verbatim lines are re-appended untouched when the model dropped
     * them. Appends {@code AI_NOTES_TIDIED} — structural only.
     */
    public RoomTidyResponse tidy(RecruitmentInterview interview,
                                 RecruitmentCandidate candidate,
                                 RoomTidyRequest request,
                                 UUID actor,
                                 boolean alignment) {
        List<RoomTidyRequest.TidyLine> lines = request == null || request.lines() == null
                ? List.of()
                : request.lines().stream().limit(MAX_INPUT_LINES).toList();
        Set<String> inputSubjects = lines.stream()
                .map(RoomTidyRequest.TidyLine::subjectCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (inputSubjects.isEmpty()) {
            return new RoomTidyResponse(List.of(), List.of());
        }
        List<String> promptLines = lines.stream()
                .filter(line -> line.text() != null && !line.text().isBlank())
                .map(line -> "[" + (line.subjectCode() == null ? "loose" : line.subjectCode())
                        + (line.verbatim() ? ", verbatim" : "") + "] " + line.text().strip())
                .toList();

        long startedAt = System.nanoTime();
        String raw = callUntransacted(() -> openAIService.askQuestionWithSchema(
                InterviewRoomPrompts.tidySystem(alignment),
                InterviewRoomPrompts.tidyUser(promptLines),
                InterviewRoomPrompts.tidySchema(), "RoomTidy",
                "{\"subjects\":[],\"alignmentNotes\":[]}",
                extractionModel, MAX_OUTPUT_TOKENS, false, "low"));
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

        RoomTidyResponse validated = validateTidy(raw, inputSubjects, alignment);
        List<TidySubject> subjects = validated.subjects();
        List<String> alignmentNotes = validated.alignmentNotes();

        // AI Act logging (§9): the event store is the audit trail —
        // structural facts only, never the prose.
        int linesIn = promptLines.size();
        int subjectsOut = subjects.size();
        long finalLatency = latencyMs;
        QuarkusTransaction.requiringNew().run(() -> eventRecorder.record(
                RecruitmentEventBuilder.event(RecruitmentEventType.AI_NOTES_TIDIED)
                        .candidate(candidate.getUuid())
                        .actorUser(actor.toString())
                        .payload("interview_uuid", interview.getUuid())
                        .payload("lines_in", linesIn)
                        .payload("subjects_out", subjectsOut)
                        .payload("alignment", alignment)
                        .payload("model", extractionModel)
                        .payload("latency_ms", finalLatency)
                        .payload("prompt_version", InterviewRoomPrompts.PROMPT_VERSION)));
        return new RoomTidyResponse(List.copyOf(subjects), List.copyOf(alignmentNotes));
    }

    // ------------------------------------------------------------------
    // Prep pack (§9)
    // ------------------------------------------------------------------

    /**
     * Specialise catalogue probes to this candidate from the CV and
     * answers. Questions, never conclusions: entries that do not end in a
     * question mark are dropped. Appends {@code AI_SUGGESTIONS_GENERATED}
     * with {@code origin=INTERVIEW_ROOM_PREP}.
     */
    public RoomPrepResponse prep(RecruitmentInterview interview,
                                 RecruitmentCandidate candidate,
                                 List<String> subjectCodes,
                                 UUID actor) {
        Set<String> allowedSubjects = new LinkedHashSet<>(subjectCodes);
        StringBuilder user = new StringBuilder("Standard probes per subject:\n");
        for (String code : allowedSubjects) {
            ScorecardGuidanceCatalog.forCode(code).ifPresent(guidance ->
                    user.append(code).append(":\n").append(guidance.probes().stream()
                            .map(p -> "  - " + p).collect(Collectors.joining("\n"))).append('\n'));
        }
        List<FormAnswer> answers =
                profileReadService.answersForCandidate(candidate.getUuid()).answers();
        if (!answers.isEmpty()) {
            user.append("\nForm answers:\n");
            answers.stream().limit(20).forEach(a -> user.append("  ")
                    .append(a.label() == null ? a.questionKey() : a.label()).append(": ")
                    .append(a.answer()).append('\n'));
        }
        CvContentExtractor.CvContent cv = cvContentExtractor.extract(candidate.getUuid());
        if (cv.hasText()) {
            String text = cv.text();
            user.append("\nCV extract:\n")
                    .append(text, 0, Math.min(text.length(), 8000)).append('\n');
        }

        String raw = callUntransacted(() -> openAIService.askQuestionWithSchema(
                InterviewRoomPrompts.prepSystem(), user.toString(),
                InterviewRoomPrompts.prepSchema(), "RoomPrepPack",
                "{\"probes\":[]}", extractionModel, MAX_OUTPUT_TOKENS, false, "low"));

        List<PrepSubject> probes = validatePrep(raw, allowedSubjects);
        if (!probes.isEmpty()) {
            recordGenerated(interview, candidate, actor, "INTERVIEW_ROOM_PREP",
                    probes.stream().mapToInt(p -> p.questions().size()).sum(),
                    probesPii(probes));
        }
        return new RoomPrepResponse(List.copyOf(probes));
    }

    // ------------------------------------------------------------------
    // Model-output validation — pure, and tested directly. The model is
    // UNTRUSTED: everything outside the vocabulary / subject set / question
    // contract is silently dropped (posture of AiIntakeGenerationService).
    // ------------------------------------------------------------------

    /**
     * Extraction: vocabulary-guarded facts and subject-set-guarded tags,
     * both anchored to a line the caller actually sent.
     * <p>
     * The model is UNTRUSTED. A fact needs a known vocabulary key, a
     * non-blank value AND its evidence quote; a tag needs a subject code
     * from THIS interview's template. Either one needs a {@code lineIndex}
     * inside {@code [0, lineCount)} — an out-of-range index is the shape a
     * hallucinated anchor takes, and letting one through would attach a
     * chip to a line the interviewer never wrote. At most one tag per line
     * survives (first wins): a line is evidence for one subject or none.
     */
    static RoomSuggestResponse validateExtraction(String raw, Set<String> allowedSubjects,
                                                  int lineCount) {
        JsonNode parsed = parseSingleDocument(raw);
        if (parsed == null) {
            return EMPTY_SUGGESTION;
        }

        List<RoomFactSuggestion> suggestions = new ArrayList<>();
        JsonNode array = parsed.get("suggestions");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                Integer lineIndex = lineIndex(node, lineCount);
                String field = text(node, "field");
                String value = clamp(text(node, "value"), MAX_VALUE_CHARS);
                String evidence = clamp(text(node, "evidence"), MAX_EVIDENCE_CHARS);
                if (lineIndex == null
                        || !RecruitmentFactVocabulary.isKnown(field)
                        || value == null || value.isBlank()
                        || evidence == null || evidence.isBlank()) {
                    continue; // untrusted output — outside the contract, drop
                }
                suggestions.add(new RoomFactSuggestion(UUID.randomUUID().toString(),
                        lineIndex, field, value, evidence));
            }
        }

        Map<Integer, RoomSubjectTag> tags = new LinkedHashMap<>();
        JsonNode tagArray = parsed.get("subjectTags");
        if (tagArray != null && tagArray.isArray()) {
            for (JsonNode node : tagArray) {
                Integer lineIndex = lineIndex(node, lineCount);
                String subjectCode = text(node, "subjectCode");
                if (lineIndex == null || subjectCode == null
                        || !allowedSubjects.contains(subjectCode)) {
                    continue; // not a subject this interview scores — drop
                }
                tags.putIfAbsent(lineIndex, new RoomSubjectTag(lineIndex, subjectCode));
            }
        }
        return new RoomSuggestResponse(List.copyOf(suggestions), List.copyOf(tags.values()));
    }

    /** A line anchor the caller can actually resolve, or null. */
    private static Integer lineIndex(JsonNode node, int lineCount) {
        JsonNode value = node.get("lineIndex");
        if (value == null || !value.isIntegralNumber()) {
            return null;
        }
        int index = value.asInt();
        return index >= 0 && index < lineCount ? index : null;
    }

    /** Tidy: the gap rule — only subjects present in the input may appear. */
    static RoomTidyResponse validateTidy(String raw, Set<String> inputSubjects,
                                         boolean alignment) {
        List<TidySubject> subjects = new ArrayList<>();
        List<String> alignmentNotes = new ArrayList<>();
        JsonNode parsed = parseSingleDocument(raw);
        if (parsed != null) {
            JsonNode subjectsNode = parsed.get("subjects");
            if (subjectsNode != null && subjectsNode.isArray()) {
                for (JsonNode node : subjectsNode) {
                    String code = text(node, "subjectCode");
                    String prose = clamp(text(node, "prose"), MAX_PROSE_CHARS);
                    // The gap rule, enforced here rather than trusted: a
                    // subject the interviewer wrote nothing under gets nothing.
                    if (code != null && inputSubjects.contains(code)
                            && prose != null && !prose.isBlank()) {
                        subjects.add(new TidySubject(code, prose));
                    }
                }
            }
            if (alignment) {
                JsonNode notes = parsed.get("alignmentNotes");
                if (notes != null && notes.isArray()) {
                    for (JsonNode node : notes) {
                        String note = clamp(node.asText(null), 400);
                        if (note != null && !note.isBlank()) {
                            alignmentNotes.add(note);
                        }
                    }
                }
            }
        }
        return new RoomTidyResponse(List.copyOf(subjects), List.copyOf(alignmentNotes));
    }

    /** Prep: questions only — entries that do not end in "?" are dropped. */
    static List<PrepSubject> validatePrep(String raw, Set<String> allowedSubjects) {
        List<PrepSubject> probes = new ArrayList<>();
        JsonNode parsed = parseSingleDocument(raw);
        JsonNode array = parsed == null ? null : parsed.get("probes");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                String code = text(node, "subjectCode");
                if (code == null || !allowedSubjects.contains(code)) {
                    continue;
                }
                JsonNode questionsNode = node.get("questions");
                if (questionsNode == null || !questionsNode.isArray()) {
                    continue;
                }
                List<String> questions = new ArrayList<>();
                for (JsonNode q : questionsNode) {
                    String question = clamp(q.asText(null), 300);
                    // Questions, never conclusions — the contract is enforced,
                    // not trusted (§9).
                    if (question != null && question.trim().endsWith("?")) {
                        questions.add(question.trim());
                    }
                }
                if (!questions.isEmpty()) {
                    probes.add(new PrepSubject(code, List.copyOf(questions)));
                }
            }
        }
        return probes;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void recordGenerated(RecruitmentInterview interview, RecruitmentCandidate candidate,
                                 UUID actor, String origin, int count, String piiJson) {
        QuarkusTransaction.requiringNew().run(() -> eventRecorder.record(
                RecruitmentEventBuilder.event(RecruitmentEventType.AI_SUGGESTIONS_GENERATED)
                        .candidate(candidate.getUuid())
                        .actorUser(actor.toString())
                        .payload("origin", origin)
                        .payload("interview_uuid", interview.getUuid())
                        .payload("count", count)
                        .payload("model", extractionModel)
                        .payload("prompt_version", InterviewRoomPrompts.PROMPT_VERSION)
                        .pii("suggestions", piiJson)));
    }

    private static String suggestionsPii(List<RoomFactSuggestion> suggestions) {
        return suggestions.stream()
                .map(s -> s.field() + "=" + s.value() + " (\"" + s.evidence() + "\")")
                .collect(Collectors.joining("; "));
    }

    private static String probesPii(List<PrepSubject> probes) {
        return probes.stream()
                .map(p -> p.subjectCode() + ": " + String.join(" | ", p.questions()))
                .collect(Collectors.joining("; "));
    }

    /**
     * The OpenAI round-trip must not hold a transaction or its pooled
     * connection (security review M1 — the intake regenerate precedent).
     */
    private String callUntransacted(java.util.function.Supplier<String> call) {
        if (QuarkusTransaction.isActive()) {
            throw new IllegalStateException(
                    "Interview Room AI calls must run outside a transaction");
        }
        return call.get();
    }

    /** Exactly one JSON document — trailing model scratchpad fails the parse. */
    private static JsonNode parseSingleDocument(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return STRICT_JSON.readTree(raw);
        } catch (Exception e) {
            log.warn("Interview Room AI answer was not a single JSON document — dropping");
            return null;
        }
    }

    /**
     * Clean each line WITHOUT compacting the list: an entry that sanitises
     * to nothing becomes an empty string and keeps its slot, because the
     * response anchors back to the caller's array by index. Dropping it
     * here would silently shift every later line's identity.
     */
    private static List<String> sanitizeLinesKeepingIndex(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream()
                .limit(MAX_INPUT_LINES)
                .map(line -> line == null
                        ? ""
                        : line.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").strip())
                .toList();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static String clamp(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String stripped = value.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").strip();
        return stripped.length() <= maxChars ? stripped : stripped.substring(0, maxChars);
    }
}
