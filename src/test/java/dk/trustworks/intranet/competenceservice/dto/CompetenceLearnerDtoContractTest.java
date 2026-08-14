package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.competenceservice.content.CompetenceContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract test spec §6.4 and §12.1 require: <strong>no learner DTO contains
 * {@code correct}</strong>.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The prototype this module replaces shipped its questions to the browser as
 * {@code [["answer text", true], …]} and computed the score there. Every correct answer was in
 * the DOM before the candidate read the first question, and the "result" the page reported back
 * was whatever the page chose to report. For a quiz that is an annoyance; for a module whose
 * entire product is <em>evidence an auditor will trust</em> it is disqualifying (§10.2) — an
 * approval record derived from a number the candidate's own browser computed proves nothing
 * about the candidate, and one demonstrated console tab is enough to invalidate every row the
 * module ever wrote.
 *
 * <p>The production design removes the possibility rather than the temptation: the stored
 * {@link CompetenceContent.Option} carries {@code correct}, the wire {@link LearnerOption} is a
 * <em>different type</em> that has no such component, and {@link CompetenceLearnerMapper} is the
 * one place the first becomes the second. That is a good design, and it is also exactly the kind
 * of design a well-meaning refactor undoes: someone serves the payload record directly "to save a
 * mapping", adds a {@code @JsonIgnore} instead of a type, or widens {@code LearnerOption} for a
 * review screen. None of those break a page, so none of them are noticed — the module simply
 * stops being worth anything, silently, and only for attempts taken after that commit.
 *
 * <p>This test is the standing guard against that. It runs in the DB-free fast tier, which gates
 * every deploy, and it asserts the property three ways: structurally over the learner types, on
 * the actual serialised bytes, and on the shuffle that the wire order is supposed to carry. The
 * serialisation assertion is the one that really protects the module — the structural one only
 * protects the shape, and a leak can be introduced without changing any shape at all.
 */
class CompetenceLearnerDtoContractTest {

    /**
     * The DTO package, resolved relative to the module root the way
     * {@code PermissionCatalogueArtifactTest} resolves {@code docs/access/permission-catalogue.json}
     * — surefire runs with the Maven module directory as the working directory.
     */
    private static final Path DTO_DIR =
            Path.of("src/main/java/dk/trustworks/intranet/competenceservice/dto");

    /** The stored model, swept as a canary: it must still say {@code correct}. */
    private static final Path STORED_CONTENT_SOURCE =
            Path.of("src/main/java/dk/trustworks/intranet/competenceservice/content/CompetenceContent.java");

    /**
     * Every source file that participates in a response body from {@code /knowledge/competence/me/**}
     * (§6.1), plus the mapper that builds those bodies. Swept for the literal token.
     */
    private static final Set<String> LEARNER_SOURCES = new TreeSet<>(List.of(
            "AttemptDTO",
            "AttemptHistoryDTO",
            "AttemptResultDTO",
            "AttemptSubmissionRequest",
            "CompetenceLearnerMapper",
            "CompetencePercent",
            "CourseContentDTO",
            "CourseScreenDTO",
            "LearnerOption",
            "LearnerQuestion",
            "MyRequirementDTO"));

    /**
     * Everything else in the package: admin, approval and authoring shapes, which no employee
     * ever receives. Listed only so that a <em>new</em> file in the package fails
     * {@link #everyDtoInThePackageIsClassified()} until somebody decides which side it is on.
     * That is the point of the list — the sweep is worthless if a learner DTO can be added
     * without anyone adding it here.
     */
    private static final Set<String> NON_LEARNER_SOURCES = new TreeSet<>(List.of(
            "AudiencePreviewDTO",
            "CompetenceExportFormat",
            "DecisionOutcomeDTO",
            "DecisionRequest",
            "DecisionResponse",
            "DraftUpsertRequest",
            "ImportResultDTO",
            "PendingApprovalDTO",
            "PublishPreviewDTO",
            "PublishRequest",
            "RequirementAdminDTO",
            "RequirementUpsertRequest",
            "SettingsAuditDTO",
            "SettingsDTO",
            "SettingsUpdateRequest",
            "VersionSummaryDTO"));

    /**
     * The types that actually appear in a learner response body, including
     * {@link CompetenceContent.Block} — which {@link CourseScreenDTO} reuses from the stored
     * payload on purpose. That reuse is safe only for as long as {@code Block} carries nothing a
     * reader may not see, so it is asserted here rather than assumed.
     */
    private static final List<Class<?>> LEARNER_TYPES = List.of(
            MyRequirementDTO.class,
            MyRequirementDTO.NextAction.class,
            CourseContentDTO.class,
            CourseScreenDTO.class,
            CompetenceContent.Block.class,
            AttemptDTO.class,
            LearnerQuestion.class,
            LearnerOption.class,
            AttemptResultDTO.class,
            AttemptHistoryDTO.class);

    /**
     * {@code correct} as a whole identifier. Deliberately does not match {@code correctCount},
     * {@code getCorrectCount} or {@code incorrect}: the aggregate count of right answers is part
     * of {@link AttemptResultDTO} by design (§6.1 — how many, never which ones), and a test that
     * banned the substring would have to be weakened the first time it fired for a legitimate
     * reason, which is how guards die.
     */
    private static final Pattern CORRECT_TOKEN =
            Pattern.compile("(?<![A-Za-z0-9_$])correct(?![A-Za-z0-9_$])", Pattern.CASE_INSENSITIVE);

    // ---- 1. Structural ---------------------------------------------------

    @Test
    @DisplayName("no learner type declares a component or field named 'correct'")
    void learnerTypesHaveNoCorrectMember() {
        for (Class<?> type : LEARNER_TYPES) {
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    assertFalse(isCorrect(component.getName()),
                            type.getSimpleName() + " declares a record component '" + component.getName()
                                    + "'. Learner types must not carry a correctness flag (§6.4, §10.2).");
                }
            }
            for (Field field : type.getDeclaredFields()) {
                assertFalse(isCorrect(field.getName()),
                        type.getSimpleName() + " declares a field '" + field.getName()
                                + "'. Learner types must not carry a correctness flag (§6.4, §10.2).");
            }
        }
    }

    @Test
    @DisplayName("the structural check can actually see a correctness flag (canary)")
    void storedOptionStillCarriesCorrect() {
        // Without this, the test above passes just as happily if getRecordComponents() ever
        // stops returning anything — a green vacuum, which is the worst state for a guard.
        boolean found = Stream.of(CompetenceContent.Option.class.getRecordComponents())
                .anyMatch(c -> isCorrect(c.getName()));
        assertTrue(found, "CompetenceContent.Option no longer declares 'correct'. If the stored "
                + "model really has changed, this canary needs a new subject — do not just delete it.");
    }

    @Test
    @DisplayName("every file in the dto package is classified learner or non-learner")
    void everyDtoInThePackageIsClassified() throws IOException {
        assertTrue(Files.isDirectory(DTO_DIR),
                "Cannot find " + DTO_DIR.toAbsolutePath() + " — surefire is expected to run with the "
                        + "intranetservices module directory as its working directory.");

        Set<String> onDisk = sourceNamesIn(DTO_DIR);
        Set<String> classified = new TreeSet<>(LEARNER_SOURCES);
        classified.addAll(NON_LEARNER_SOURCES);

        assertEquals(classified, onDisk,
                "The dto package and this test's classification have drifted. A new DTO must be added "
                        + "to LEARNER_SOURCES (and swept) or to NON_LEARNER_SOURCES (and reasoned about) "
                        + "— a learner DTO nobody classified is exactly the leak this test exists to catch.");
    }

    @Test
    @DisplayName("the token 'correct' appears in no learner source, comments aside")
    void learnerSourcesDoNotMentionCorrect() throws IOException {
        for (String name : LEARNER_SOURCES) {
            Path source = DTO_DIR.resolve(name + ".java");
            assertTrue(Files.exists(source), "Missing " + source);
            String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));
            assertFalse(CORRECT_TOKEN.matcher(code).find(),
                    name + ".java uses the identifier 'correct' outside a comment. A learner-facing "
                            + "type must neither declare nor read a correctness flag (§6.4, §10.2). "
                            + "Comments are stripped before this scan precisely so the rule can be "
                            + "explained in prose where it is enforced.");
        }
    }

    @Test
    @DisplayName("the sweep survives comment stripping (canary)")
    void storedContentSourceStillTripsTheSweep() throws IOException {
        // If stripComments ever ate real code, every sweep above would pass for the wrong reason.
        String code = stripComments(Files.readString(STORED_CONTENT_SOURCE, StandardCharsets.UTF_8));
        assertTrue(CORRECT_TOKEN.matcher(code).find(),
                "CompetenceContent.java no longer trips the token scan. Either the stored model "
                        + "changed or stripComments() is now removing code — check the latter first.");
        assertFalse(CORRECT_TOKEN.matcher(stripComments("/** the correct answer */ int x;")).find(),
                "stripComments() failed to remove a javadoc comment.");
        assertTrue(CORRECT_TOKEN.matcher("boolean correct;").find(),
                "The token pattern no longer matches a plain declaration.");
        assertFalse(CORRECT_TOKEN.matcher("int correctCount; a.getCorrectCount(); incorrect;").find(),
                "The token pattern must not fire on correctCount/getCorrectCount/incorrect.");
    }

    // ---- 2. Behavioural: the bytes on the wire ---------------------------

    @Test
    @DisplayName("the serialised learner questions contain no 'correct', but every id and text")
    void serialisedQuestionsCarryNoCorrectnessFlag() throws IOException {
        CompetenceContent.TestPayload payload = payload();

        List<LearnerQuestion> questions = CompetenceLearnerMapper.questions(payload, Map.of());
        String json = new ObjectMapper().writeValueAsString(questions);

        assertFalse(json.toLowerCase().contains("correct"),
                "A learner response body contains 'correct'. This is the prototype's flaw returning "
                        + "(§10.2): " + json);
        // The flag is a boolean, and none of this payload's texts are boolean-shaped, so a stray
        // true/false in the body means a flag came along under some other name.
        assertFalse(json.contains("true") || json.contains("false"),
                "A learner response body contains a boolean. The learner shape has no boolean "
                        + "anywhere; a renamed correctness flag would look exactly like this: " + json);

        for (CompetenceContent.Question question : payload.questions()) {
            assertTrue(json.contains("\"" + question.id() + "\""), "Missing question id " + question.id());
            assertTrue(json.contains(question.text()), "Missing question text " + question.text());
            for (CompetenceContent.Option option : question.options()) {
                assertTrue(json.contains("\"" + option.id() + "\""), "Missing option id " + option.id());
                assertTrue(json.contains(option.text()), "Missing option text " + option.text());
            }
        }
    }

    @Test
    @DisplayName("each serialised option object has exactly the keys id and text")
    void serialisedOptionsHaveExactlyIdAndText() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(CompetenceLearnerMapper.questions(payload(), Map.of()));

        JsonNode root = mapper.readTree(json);
        assertEquals(2, root.size());
        for (JsonNode question : root) {
            assertEquals(Set.of("id", "text", "options"), keysOf(question));
            assertTrue(question.get("options").size() > 0);
            for (JsonNode option : question.get("options")) {
                assertEquals(Set.of("id", "text"), keysOf(option),
                        "A learner option carries a key beyond {id, text}: " + option);
            }
        }
    }

    @Test
    @DisplayName("the whole AttemptDTO graph serialises without a correctness flag")
    void serialisedAttemptCarriesNoCorrectnessFlag() throws IOException {
        // Built directly rather than through AttemptDTO.of, which needs an entity. startedAt is
        // left null on purpose: this is a plain ObjectMapper with no JavaTimeModule, exactly as a
        // reviewer would reach for it, and the point of the test is the question graph.
        AttemptDTO dto = new AttemptDTO(
                "attempt-uuid", "req-uuid", "sikker-udvikling", "7.b.1",
                "Sikker udvikling", "1.0", 2, null,
                CompetenceLearnerMapper.questions(payload(), Map.of()));

        String json = new ObjectMapper().writeValueAsString(dto);
        assertFalse(json.toLowerCase().contains("correct"),
                "AttemptDTO serialised a correctness flag: " + json);
        assertTrue(json.contains("\"questions\""), "AttemptDTO lost its questions: " + json);
    }

    @Test
    @DisplayName("authored text may say 'correct'; the wire still carries no correctness key")
    void authoredTextContainingTheWordIsNotAFalsePositive() throws IOException {
        // English content is allowed, and "the correct approach" is a plausible option text. The
        // guarantee is about the key, not about the vocabulary — a test that could not tell the
        // difference would be turned off the first time an author wrote an English distractor.
        CompetenceContent.TestPayload payload = new CompetenceContent.TestPayload(List.of(
                new CompetenceContent.Question("q1", "Which is the correct approach?", List.of(
                        new CompetenceContent.Option("q1o1", "Validate on the server", true),
                        new CompetenceContent.Option("q1o2", "This one is not correct", false)))));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(CompetenceLearnerMapper.questions(payload, Map.of()));

        assertFalse(json.contains("\"correct\""),
                "A learner response body carries a 'correct' key: " + json);
        assertTrue(json.contains("This one is not correct"), "Authored text was mangled: " + json);
        for (JsonNode option : mapper.readTree(json).get(0).get("options")) {
            assertEquals(Set.of("id", "text"), keysOf(option));
        }
    }

    // ---- 3. Shuffle fidelity --------------------------------------------

    @Test
    @DisplayName("options come back in the recorded order")
    void recordedOrderIsHonoured() {
        List<LearnerQuestion> questions = CompetenceLearnerMapper.questions(
                payload(), Map.of("q1", List.of("q1o3", "q1o1", "q1o4", "q1o2")));

        assertEquals(List.of("q1o3", "q1o1", "q1o4", "q1o2"), optionIds(questions.get(0)));
    }

    @Test
    @DisplayName("a question missing from the order map falls back to payload order")
    void unrecordedQuestionFallsBackToPayloadOrder() {
        // q2 has no entry: an attempt written before the shuffle existed, or an option_order_json
        // that could not be read. A differently-ordered list is the right cost; an error page in
        // the middle of a test is not.
        List<LearnerQuestion> questions = CompetenceLearnerMapper.questions(
                payload(), Map.of("q1", List.of("q1o2", "q1o1", "q1o3", "q1o4")));

        assertEquals(List.of("q1o2", "q1o1", "q1o3", "q1o4"), optionIds(questions.get(0)));
        assertEquals(List.of("q2o1", "q2o2", "q2o3", "q2o4"), optionIds(questions.get(1)));
    }

    @Test
    @DisplayName("an empty or null order map is payload order throughout")
    void emptyOrderMapIsPayloadOrder() {
        for (Map<String, List<String>> order : listOfMaps()) {
            List<LearnerQuestion> questions = CompetenceLearnerMapper.questions(payload(), order);
            assertEquals(List.of("q1o1", "q1o2", "q1o3", "q1o4"), optionIds(questions.get(0)));
            assertEquals(List.of("q2o1", "q2o2", "q2o3", "q2o4"), optionIds(questions.get(1)));
        }
    }

    @Test
    @DisplayName("the recorded order is a preference: unknown ids drop, unmentioned ones append")
    void recordedOrderIsAPreferenceNotTheSourceOfTruth() {
        // A missing option can be the correct one, so an unreadable or partial record must never
        // silently shorten the paper a candidate sits.
        List<LearnerQuestion> questions = CompetenceLearnerMapper.questions(
                payload(), Map.of("q1", List.of("q1o4", "ghost", "q1o4", "q1o2")));

        assertEquals(List.of("q1o4", "q1o2", "q1o1", "q1o3"), optionIds(questions.get(0)),
                "Recorded ids first (unknown and duplicate dropped), then the rest in payload order.");
    }

    @Test
    @DisplayName("a null payload is an empty question list, never a NullPointerException")
    void nullPayloadIsEmpty() {
        assertTrue(CompetenceLearnerMapper.questions(null, Map.of()).isEmpty());
    }

    // ---- fixtures and helpers -------------------------------------------

    /** Two questions, four options each, exactly one of them flagged {@code correct}. */
    private static CompetenceContent.TestPayload payload() {
        List<CompetenceContent.Question> questions = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            String q = "q" + i;
            questions.add(new CompetenceContent.Question(q, "Spørgsmål " + i, List.of(
                    new CompetenceContent.Option(q + "o1", "Svar A" + i, true),
                    new CompetenceContent.Option(q + "o2", "Svar B" + i, false),
                    new CompetenceContent.Option(q + "o3", "Svar C" + i, false),
                    new CompetenceContent.Option(q + "o4", "Svar D" + i, false))));
        }
        return new CompetenceContent.TestPayload(questions);
    }

    private static List<Map<String, List<String>>> listOfMaps() {
        List<Map<String, List<String>>> orders = new ArrayList<>();
        orders.add(Map.of());
        orders.add(null);
        return orders;
    }

    private static List<String> optionIds(LearnerQuestion question) {
        return question.options().stream().map(LearnerOption::id).toList();
    }

    private static boolean isCorrect(String name) {
        return "correct".equalsIgnoreCase(name);
    }

    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new LinkedHashSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            keys.add(it.next());
        }
        return keys;
    }

    private static Set<String> sourceNamesIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".java"))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    /**
     * Removes block and line comments so the sweep reads code, not prose.
     *
     * <p>Comments have to be excluded: the files being swept are the ones that <em>explain</em>
     * why no correctness flag may travel, and they cannot do that without naming it. String
     * literals are deliberately <em>not</em> excluded — a literal {@code "correct"} in a learner
     * DTO is a JSON key or a field name being built by hand, which is precisely the thing this
     * test is looking for.
     */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                out.append(' ');
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
