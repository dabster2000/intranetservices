package dk.trustworks.intranet.competenceservice.domain;

import com.fasterxml.jackson.databind.JsonNode;
import dk.trustworks.intranet.competenceservice.content.CompetenceContent;
import dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec;
import dk.trustworks.intranet.competenceservice.dto.CompetenceExportFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads both wire format versions and normalises them to the stored shape.
 *
 * <p>Works on the parsed tree rather than binding to a record, because v1 and v2 both
 * call the key {@code questions} while meaning different things: v1 holds
 * {@code {q, a: [["text", true], ...]}} and v2 holds
 * {@code {id, text, options: [{id, text, correct}]}}. Binding both to one type would let
 * one silently win.
 *
 * <p>Option ids are derived from position ({@code q3o2}), which makes them stable for a
 * given file and reproducible across a re-import — the submission protocol sends ids, so
 * they have to mean something.
 *
 * <p>Errors are collected per topic rather than thrown on the first one: someone
 * importing a four-topic file wants to see everything wrong with it at once.
 */
public final class CompetenceImportAdapter {

    private CompetenceImportAdapter() {
    }

    /**
     * @param compId  which topic the problems belong to, or {@code null} for envelope-level
     * @param errors  what is wrong
     */
    public record TopicErrors(String compId, List<String> errors) {
    }

    /**
     * @param topics   successfully converted topics, ready to be written as DRAFTs
     * @param problems per-topic error lists; a non-empty list means nothing should be saved
     */
    public record Result(List<ParsedTopic> topics, List<TopicErrors> problems) {

        public boolean hasProblems() {
            return !problems.isEmpty();
        }
    }

    public record ParsedTopic(String compId,
                              String kref,
                              String name,
                              String desc,
                              List<String> targetPracticeUuids,
                              List<String> targetTeams,
                              List<String> targetUseruuids,
                              Integer cadenceDaysOverride,
                              String courseVersion,
                              CompetenceContent.CoursePayload course,
                              String testVersion,
                              CompetenceContent.TestPayload test) {
    }

    public static Result parse(String json) {
        List<ParsedTopic> topics = new ArrayList<>();
        List<TopicErrors> problems = new ArrayList<>();

        JsonNode root;
        try {
            root = CompetencePayloadCodec.mapper().readTree(json);
        } catch (Exception e) {
            problems.add(new TopicErrors(null, List.of("Filen er ikke gyldig JSON.")));
            return new Result(topics, problems);
        }
        if (root == null || !root.isObject()) {
            problems.add(new TopicErrors(null, List.of("Filen skal indeholde et JSON-objekt.")));
            return new Result(topics, problems);
        }
        String kind = text(root, "kind");
        if (kind != null && !CompetenceExportFormat.KIND.equals(kind)) {
            problems.add(new TopicErrors(null,
                    List.of("Ukendt filtype: " + kind + ". Forventede " + CompetenceExportFormat.KIND + ".")));
            return new Result(topics, problems);
        }
        int formatVersion = root.hasNonNull("formatVersion") ? root.get("formatVersion").asInt(1) : 1;
        if (formatVersion < 1 || formatVersion > CompetenceExportFormat.CURRENT_FORMAT_VERSION) {
            problems.add(new TopicErrors(null,
                    List.of("Ikke-understøttet formatVersion: " + formatVersion + ".")));
            return new Result(topics, problems);
        }
        JsonNode topicsNode = root.get("topics");
        if (topicsNode == null || !topicsNode.isArray() || topicsNode.isEmpty()) {
            problems.add(new TopicErrors(null, List.of("Filen indeholder ingen emner (topics).")));
            return new Result(topics, problems);
        }

        for (JsonNode topicNode : topicsNode) {
            List<String> errors = new ArrayList<>();
            String compId = text(topicNode, "compId");
            if (compId == null || compId.isBlank()) {
                errors.add("Emnet mangler compId.");
                problems.add(new TopicErrors(null, errors));
                continue;
            }
            String kref = text(topicNode, "kref");
            String name = text(topicNode, "name");
            if (kref == null || kref.isBlank()) {
                errors.add("Emnet mangler kref.");
            }
            if (name == null || name.isBlank()) {
                errors.add("Emnet mangler navn.");
            }

            CompetenceContent.CoursePayload course = null;
            String courseVersion = null;
            JsonNode courseNode = topicNode.get("course");
            if (courseNode != null && !courseNode.isNull()) {
                courseVersion = text(courseNode, "version");
                try {
                    JsonNode screens = courseNode.get("screens");
                    course = CompetencePayloadCodec.readCourse(
                            "{\"screens\":" + (screens == null ? "[]" : screens.toString()) + "}");
                    errors.addAll(CompetenceContentValidator.validateVersionLabel(courseVersion));
                    errors.addAll(CompetenceContentValidator.validateCourse(course));
                } catch (Exception e) {
                    errors.add("Kursusindholdet kunne ikke læses: " + shortMessage(e));
                }
            }

            CompetenceContent.TestPayload test = null;
            String testVersion = null;
            JsonNode quizNode = topicNode.get("quiz");
            if (quizNode != null && !quizNode.isNull()) {
                testVersion = text(quizNode, "version");
                try {
                    test = readQuestions(quizNode.get("questions"), formatVersion);
                    errors.addAll(CompetenceContentValidator.validateVersionLabel(testVersion));
                    errors.addAll(CompetenceContentValidator.validateTest(test));
                } catch (Exception e) {
                    errors.add("Testindholdet kunne ikke læses: " + shortMessage(e));
                }
            }

            if (course == null && test == null) {
                errors.add("Emnet indeholder hverken kursus eller test.");
            }

            if (errors.isEmpty()) {
                topics.add(new ParsedTopic(
                        compId, kref, name, text(topicNode, "desc"),
                        stringList(topicNode.get("targetPracticeUuids")),
                        stringList(topicNode.get("targetTeams")),
                        stringList(topicNode.get("targetUseruuids")),
                        topicNode.hasNonNull("cadenceDaysOverride")
                                ? topicNode.get("cadenceDaysOverride").asInt() : null,
                        courseVersion, course, testVersion, test));
            } else {
                problems.add(new TopicErrors(compId, errors));
            }
        }
        return new Result(topics, problems);
    }

    /**
     * v1 tuples become v2 options; v2 questions pass through. Ids are positional so a
     * re-import of the same file produces identical ids.
     */
    static CompetenceContent.TestPayload readQuestions(JsonNode questionsNode, int formatVersion) {
        if (questionsNode == null || !questionsNode.isArray()) {
            return new CompetenceContent.TestPayload(List.of());
        }
        List<CompetenceContent.Question> questions = new ArrayList<>();
        int qi = 0;
        for (JsonNode q : questionsNode) {
            qi++;
            boolean tupleShape = q.has("a") || (q.has("q") && !q.has("options"));
            if (formatVersion >= 2 && !tupleShape) {
                String id = text(q, "id");
                String questionId = (id == null || id.isBlank()) ? "q" + qi : id;
                List<CompetenceContent.Option> options = new ArrayList<>();
                JsonNode optionsNode = q.get("options");
                int oi = 0;
                if (optionsNode != null && optionsNode.isArray()) {
                    for (JsonNode o : optionsNode) {
                        oi++;
                        String optionId = text(o, "id");
                        options.add(new CompetenceContent.Option(
                                (optionId == null || optionId.isBlank()) ? questionId + "o" + oi : optionId,
                                text(o, "text"),
                                o.hasNonNull("correct") && o.get("correct").asBoolean()));
                    }
                }
                questions.add(new CompetenceContent.Question(questionId, text(q, "text"), options));
                continue;
            }
            // v1: { "q": "...", "a": [["text", true], ["text", false]] }
            String questionId = "q" + qi;
            List<CompetenceContent.Option> options = new ArrayList<>();
            JsonNode answers = q.get("a");
            int oi = 0;
            if (answers != null && answers.isArray()) {
                for (JsonNode tuple : answers) {
                    oi++;
                    String optionText;
                    boolean correct;
                    if (tuple.isArray()) {
                        optionText = tuple.size() > 0 ? tuple.get(0).asText(null) : null;
                        correct = tuple.size() > 1 && tuple.get(1).asBoolean();
                    } else {
                        optionText = text(tuple, "text");
                        correct = tuple.hasNonNull("correct") && tuple.get("correct").asBoolean();
                    }
                    options.add(new CompetenceContent.Option(questionId + "o" + oi, optionText, correct));
                }
            }
            questions.add(new CompetenceContent.Question(questionId, text(q, "q"), options));
        }
        return new CompetenceContent.TestPayload(questions);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String shortMessage(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) + "…" : message;
    }
}
