package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.content.CompetenceContent;
import dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec;
import dk.trustworks.intranet.competenceservice.domain.CompetenceImportAdapter.ParsedTopic;
import dk.trustworks.intranet.competenceservice.domain.CompetenceImportAdapter.Result;
import dk.trustworks.intranet.competenceservice.domain.CompetenceImportAdapter.TopicErrors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §12.1: "Import: formatVersion 1 → 2 up-conversion; malformed payload rejected with
 * per-topic errors."
 *
 * <p>The fixtures are inline text blocks rather than resource files on purpose — this test
 * is the readable specification of the wire format (§9.6), and a reader who has to open a
 * second file to see what a v1 quiz answer looks like has lost the point. The two shapes
 * sit next to each other here: v1 answers are {@code [["tekst", true], …]} tuples with no
 * ids at all, v2 answers are {@code {id, text, correct}} objects. Both live under the key
 * {@code questions}, which is exactly why the adapter walks the tree instead of binding to
 * a record.
 *
 * <p>The error assertions matter as much as the happy path. {@code importContent} writes
 * nothing when any topic fails, so a blanket "file is invalid" would leave an author with
 * a four-topic file and no idea which topic to fix; every rejection below is therefore
 * pinned to the topic it belongs to, and only envelope-level failures carry a null compId.
 */
class CompetenceImportAdapterTest {

    /**
     * The original export. No {@code formatVersion} key at all, no targeting keys, and
     * quiz answers as positional tuples. Everything the stored shape needs — question ids,
     * option ids — has to be manufactured from position.
     */
    private static final String V1_FILE = """
            {
              "kind": "kompetencemodul-full-export",
              "exportedAt": "2026-08-04T00:00:00.000Z",
              "topics": [
                {
                  "compId": "7.b.1",
                  "kref": "K1",
                  "name": "Informationssikkerhed",
                  "desc": "Grundlæggende sikker adfærd i hverdagen",
                  "course": {
                    "version": "2026-08",
                    "screens": [
                      {
                        "role": "intro",
                        "title": "Introduktion",
                        "lede": "Hvorfor dette emne findes",
                        "blocks": [
                          { "type": "paragraph", "text": "Sikkerhed er en vane, ikke et værktøj." }
                        ]
                      }
                    ]
                  },
                  "quiz": {
                    "version": "2026-08",
                    "questions": [
                      {
                        "q": "Hvad kendetegner et stærkt kodeord?",
                        "a": [
                          ["Længde over 12 tegn", true],
                          ["Dit fødselsår", false],
                          ["Firmanavnet", false]
                        ]
                      },
                      {
                        "q": "Hvem må du dele din adgangskode med?",
                        "a": [
                          ["Ingen", true],
                          ["Din nærmeste leder", false]
                        ]
                      }
                    ]
                  }
                }
              ]
            }
            """;

    /**
     * The current export. Explicit {@code formatVersion 2}, author-supplied ids on both
     * questions and options, and the three targeting arrays — one populated, one explicitly
     * empty, one explicitly null.
     */
    private static final String V2_FILE = """
            {
              "kind": "kompetencemodul-full-export",
              "formatVersion": 2,
              "exportedAt": "2026-09-01T00:00:00.000Z",
              "topics": [
                {
                  "compId": "7.b.2",
                  "kref": "K2",
                  "name": "Adgangsstyring",
                  "desc": "Hvem må hvad, og hvordan bevises det",
                  "targetPracticeUuids": ["3f7a0e10-tech"],
                  "targetTeams": [],
                  "targetUseruuids": null,
                  "cadenceDaysOverride": 180,
                  "course": {
                    "version": "2026-09",
                    "screens": [
                      {
                        "role": "content",
                        "title": "Roller og rettigheder",
                        "blocks": [
                          { "type": "list", "ordered": false,
                            "items": ["Mindste privilegium", "Adskillelse af pligter"] }
                        ]
                      }
                    ]
                  },
                  "quiz": {
                    "version": "2026-09",
                    "questions": [
                      {
                        "id": "kode-1",
                        "text": "Hvad betyder mindste privilegium?",
                        "options": [
                          { "id": "kode-1-a", "text": "Kun den adgang opgaven kræver", "correct": true },
                          { "id": "kode-1-b", "text": "Adgang til alt i eget team", "correct": false }
                        ]
                      }
                    ]
                  }
                }
              ]
            }
            """;

    // -----------------------------------------------------------------------
    // formatVersion 1 → 2 up-conversion
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a v1 file — no formatVersion key, no targeting — is accepted whole")
    void v1FileParsesWithoutProblems() {
        Result result = CompetenceImportAdapter.parse(V1_FILE);

        assertFalse(result.hasProblems(), "unexpected problems: " + result.problems());
        assertEquals(1, result.topics().size());

        ParsedTopic topic = result.topics().get(0);
        assertEquals("7.b.1", topic.compId());
        assertEquals("K1", topic.kref());
        assertEquals("Informationssikkerhed", topic.name());
        assertEquals("Grundlæggende sikker adfærd i hverdagen", topic.desc());
        assertEquals("2026-08", topic.courseVersion());
        assertEquals("2026-08", topic.testVersion());
        assertEquals(1, topic.course().screens().size());
        assertNull(topic.cadenceDaysOverride(), "a v1 file carries no cadence override");
    }

    @Test
    @DisplayName("v1 tuple answers become options with positional ids and exactly one correct")
    void v1AnswersBecomePositionallyIdentifiedOptions() {
        ParsedTopic topic = CompetenceImportAdapter.parse(V1_FILE).topics().get(0);
        CompetenceContent.TestPayload test = topic.test();

        assertEquals(List.of("q1", "q2"),
                test.questions().stream().map(CompetenceContent.Question::id).toList());
        assertEquals("Hvad kendetegner et stærkt kodeord?", test.questions().get(0).text());

        // Ids are derived from position, so re-importing the same file produces the same
        // ids — the submission protocol sends option ids, so they have to survive a
        // re-import or every stored attempt stops meaning anything.
        assertEquals(List.of("q1o1", "q1o2", "q1o3"),
                test.byId("q1").options().stream().map(CompetenceContent.Option::id).toList());
        assertEquals(List.of("q2o1", "q2o2"),
                test.byId("q2").options().stream().map(CompetenceContent.Option::id).toList());

        for (CompetenceContent.Question question : test.questions()) {
            assertEquals(1, question.options().stream()
                            .filter(CompetenceContent.Option::correct).count(),
                    question.id() + " must have exactly one correct option");
        }
        // The tuple's second element is the truth flag; position 0 is the text.
        assertEquals("q1o1", test.byId("q1").correctOption().id());
        assertEquals("Længde over 12 tegn", test.byId("q1").correctOption().text());
        assertEquals("Ingen", test.byId("q2").correctOption().text());
    }

    @Test
    @DisplayName("the up-converted payload is the stored shape, byte-for-byte round-trippable")
    void v1UpConversionProducesTheStoredShape() {
        ParsedTopic topic = CompetenceImportAdapter.parse(V1_FILE).topics().get(0);

        // Up-conversion is only worth anything if the result is what publish would store:
        // the same strict codec, and the same validator that gates publish.
        assertEquals(List.of(), CompetenceContentValidator.validateCourse(topic.course()));
        assertEquals(List.of(), CompetenceContentValidator.validateTest(topic.test()));
        assertEquals(topic.course(),
                CompetencePayloadCodec.readCourse(CompetencePayloadCodec.write(topic.course())));
        assertEquals(topic.test(),
                CompetencePayloadCodec.readTest(CompetencePayloadCodec.write(topic.test())));
    }

    // -----------------------------------------------------------------------
    // formatVersion 2 pass-through
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("v2 questions keep the ids their author wrote")
    void v2QuestionsPassThroughUnchanged() {
        Result result = CompetenceImportAdapter.parse(V2_FILE);
        assertFalse(result.hasProblems(), "unexpected problems: " + result.problems());

        CompetenceContent.TestPayload test = result.topics().get(0).test();
        assertEquals(List.of("kode-1"),
                test.questions().stream().map(CompetenceContent.Question::id).toList());
        assertEquals(List.of("kode-1-a", "kode-1-b"),
                test.byId("kode-1").options().stream().map(CompetenceContent.Option::id).toList());
        assertEquals("kode-1-a", test.byId("kode-1").correctOption().id());
        assertEquals(180, result.topics().get(0).cadenceDaysOverride().intValue());
        assertEquals(List.of(), CompetenceContentValidator.validateTest(test));
    }

    @Test
    @DisplayName("a v2 question or option without an id falls back to its position")
    void v2MissingIdsFallBackToPosition() {
        String file = """
                {
                  "kind": "kompetencemodul-full-export",
                  "formatVersion": 2,
                  "topics": [
                    {
                      "compId": "7.b.3",
                      "kref": "K3",
                      "name": "Logning",
                      "course": {
                        "version": "2026-09",
                        "screens": [
                          { "role": "content", "title": "Hvad logges",
                            "blocks": [ { "type": "paragraph", "text": "Hændelser, ikke indhold." } ] }
                        ]
                      },
                      "quiz": {
                        "version": "2026-09",
                        "questions": [
                          {
                            "id": "log-1",
                            "text": "Spørgsmål med forfatter-id",
                            "options": [
                              { "id": "log-1-a", "text": "Rigtigt", "correct": true },
                              { "text": "Forkert", "correct": false }
                            ]
                          },
                          {
                            "text": "Spørgsmål uden id",
                            "options": [
                              { "text": "Rigtigt", "correct": true },
                              { "text": "Forkert", "correct": false }
                            ]
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        Result result = CompetenceImportAdapter.parse(file);
        assertFalse(result.hasProblems(), "unexpected problems: " + result.problems());
        CompetenceContent.TestPayload test = result.topics().get(0).test();

        // The fallback counts over the whole list, not over the id-less members: the second
        // question is q2 even though it is the first one missing an id. Anything else would
        // let an author's own "q1" collide with a generated one.
        assertEquals(List.of("log-1", "q2"),
                test.questions().stream().map(CompetenceContent.Question::id).toList());

        // A generated option id hangs off whichever question id won, author-supplied or not,
        // and keeps its own position — so the second option of "log-1" is "log-1o2".
        assertEquals(List.of("log-1-a", "log-1o2"),
                test.byId("log-1").options().stream().map(CompetenceContent.Option::id).toList());
        assertEquals(List.of("q2o1", "q2o2"),
                test.byId("q2").options().stream().map(CompetenceContent.Option::id).toList());
        assertEquals(List.of(), CompetenceContentValidator.validateTest(test));
    }

    // -----------------------------------------------------------------------
    // Targeting
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("absent targeting stays null and [] stays empty — the asymmetry is the point")
    void targetingRoundTripsWithoutCollapsing() {
        ParsedTopic v2 = CompetenceImportAdapter.parse(V2_FILE).topics().get(0);

        assertEquals(List.of("3f7a0e10-tech"), v2.targetPracticeUuids());

        // null means "this dimension does not constrain the audience"; [] means "match
        // nobody on this dimension". Collapsing [] to null would silently widen a parked
        // requirement to the whole company, which is the failure CompetenceAudienceMatcher
        // exists to keep impossible.
        assertNotNull(v2.targetTeams(), "an explicit [] must not become null");
        assertTrue(v2.targetTeams().isEmpty());
        assertNull(v2.targetUseruuids(), "an explicit JSON null stays null");

        ParsedTopic v1 = CompetenceImportAdapter.parse(V1_FILE).topics().get(0);
        assertNull(v1.targetPracticeUuids(), "an absent key is untargeted, not empty");
        assertNull(v1.targetTeams());
        assertNull(v1.targetUseruuids());
    }

    // -----------------------------------------------------------------------
    // Per-topic errors
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("one broken topic is named; the healthy one is still parsed but unusable")
    void onlyTheBrokenTopicIsBlamed() {
        String file = """
                {
                  "kind": "kompetencemodul-full-export",
                  "formatVersion": 2,
                  "topics": [
                    {
                      "compId": "7.b.1",
                      "kref": "K1",
                      "name": "Informationssikkerhed",
                      "quiz": {
                        "version": "2026-09",
                        "questions": [
                          { "id": "ok-1", "text": "Et gyldigt spørgsmål?", "options": [
                              { "id": "ok-1-a", "text": "Ja", "correct": true },
                              { "id": "ok-1-b", "text": "Nej", "correct": false } ] }
                        ]
                      }
                    },
                    {
                      "compId": "7.b.4",
                      "kref": "K4",
                      "name": "Leverandørstyring",
                      "course": {
                        "version": "2026-09",
                        "screens": [
                          { "role": "content", "blocks": [ { "type": "paragraph", "text": "Uden titel." } ] }
                        ]
                      },
                      "quiz": {
                        "version": "2026-09",
                        "questions": [
                          { "id": "bad-1", "text": "To rigtige svar?", "options": [
                              { "id": "bad-1-a", "text": "Ja", "correct": true },
                              { "id": "bad-1-b", "text": "Også ja", "correct": true } ] }
                        ]
                      }
                    }
                  ]
                }
                """;

        Result result = CompetenceImportAdapter.parse(file);

        assertTrue(result.hasProblems());
        assertEquals(1, result.problems().size(), "only the second topic is broken");

        TopicErrors problem = problemFor(result, "7.b.4");
        assertTrue(problem.errors().size() >= 2,
                "every problem in the topic is reported at once, not just the first: "
                        + problem.errors());
        assertTrue(problem.errors().stream().anyMatch(e -> e.contains("mangler en titel")),
                problem.errors().toString());
        assertTrue(problem.errors().stream().anyMatch(e -> e.contains("præcis ét korrekt svar")),
                problem.errors().toString());

        // The healthy topic still converts — it just never gets written, because
        // CompetenceContentService refuses the whole file when any topic fails. A half
        // imported four-topic file is a state nobody can reason about.
        assertEquals(List.of("7.b.1"), result.topics().stream().map(ParsedTopic::compId).toList());
    }

    @Test
    @DisplayName("a typo'd content field is rejected against its topic, not silently dropped")
    void unknownContentFieldIsReportedPerTopic() {
        String file = """
                {
                  "kind": "kompetencemodul-full-export",
                  "formatVersion": 2,
                  "topics": [
                    {
                      "compId": "7.b.3",
                      "kref": "K3",
                      "name": "Logning",
                      "course": {
                        "version": "2026-09",
                        "screens": [
                          { "role": "content", "title": "Hvad logges",
                            "blocks": [ { "type": "paragraph", "txt": "Stavet forkert." } ] }
                        ]
                      }
                    }
                  ]
                }
                """;

        Result result = CompetenceImportAdapter.parse(file);

        // The strict codec is what makes a hand-edited file safe: "txt" would otherwise be
        // dropped and the block published empty.
        TopicErrors problem = problemFor(result, "7.b.3");
        assertTrue(problem.errors().stream()
                        .anyMatch(e -> e.startsWith("Kursusindholdet kunne ikke læses:")),
                problem.errors().toString());
        assertTrue(problem.errors().stream().anyMatch(e -> e.contains("txt")),
                "the message must name the offending field: " + problem.errors());
        assertTrue(result.topics().isEmpty());
    }

    @Test
    @DisplayName("a topic with neither course nor test is rejected rather than imported empty")
    void topicWithNoContentIsRejected() {
        String file = """
                {
                  "kind": "kompetencemodul-full-export",
                  "formatVersion": 2,
                  "topics": [ { "compId": "7.b.9", "kref": "K9", "name": "Tomt emne" } ]
                }
                """;

        Result result = CompetenceImportAdapter.parse(file);
        assertEquals(List.of("Emnet indeholder hverken kursus eller test."),
                problemFor(result, "7.b.9").errors());
    }

    @Test
    @DisplayName("a topic without compId is reported, with no id to blame it on")
    void topicWithoutCompIdIsReported() {
        String file = """
                {
                  "kind": "kompetencemodul-full-export",
                  "formatVersion": 2,
                  "topics": [
                    { "kref": "K5", "name": "Emne uden compId" },
                    { "compId": "7.b.1", "kref": "K1", "name": "Informationssikkerhed",
                      "quiz": { "version": "2026-09", "questions": [
                        { "id": "ok-1", "text": "Et gyldigt spørgsmål?", "options": [
                            { "id": "ok-1-a", "text": "Ja", "correct": true },
                            { "id": "ok-1-b", "text": "Nej", "correct": false } ] } ] } }
                  ]
                }
                """;

        Result result = CompetenceImportAdapter.parse(file);

        assertEquals(1, result.problems().size());
        TopicErrors problem = result.problems().get(0);
        // compId is the only handle the UI has on a topic, so a topic missing it is
        // reported at envelope level — there is nothing to name it by. The parse still
        // continues to the next topic instead of stopping on the first bad one.
        assertNull(problem.compId());
        assertEquals(List.of("Emnet mangler compId."), problem.errors());
        assertEquals(List.of("7.b.1"), result.topics().stream().map(ParsedTopic::compId).toList());
    }

    // -----------------------------------------------------------------------
    // Envelope-level rejections
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a file of the wrong kind is refused before any topic is read")
    void unknownKindIsAnEnvelopeProblem() {
        String file = """
                {
                  "kind": "questionnaire-export",
                  "formatVersion": 2,
                  "topics": [ { "compId": "7.b.1", "kref": "K1", "name": "Informationssikkerhed" } ]
                }
                """;
        assertEnvelopeProblem(CompetenceImportAdapter.parse(file), "Ukendt filtype");
    }

    @Test
    @DisplayName("a formatVersion this build cannot read is refused, in both directions")
    void unsupportedFormatVersionIsAnEnvelopeProblem() {
        String template = """
                {
                  "kind": "kompetencemodul-full-export",
                  "formatVersion": %s,
                  "topics": [ { "compId": "7.b.1", "kref": "K1", "name": "Informationssikkerhed" } ]
                }
                """;
        // A file from a newer build carries content this one would misread; version 0 is a
        // hand-edit. Neither is guessed at.
        assertEnvelopeProblem(CompetenceImportAdapter.parse(template.formatted("3")),
                "Ikke-understøttet formatVersion: 3");
        assertEnvelopeProblem(CompetenceImportAdapter.parse(template.formatted("0")),
                "Ikke-understøttet formatVersion: 0");
    }

    @Test
    @DisplayName("input that is not JSON, or not a JSON object, is refused whole")
    void nonJsonIsAnEnvelopeProblem() {
        assertEnvelopeProblem(CompetenceImportAdapter.parse("dette er slet ikke JSON"),
                "ikke gyldig JSON");
        assertEnvelopeProblem(CompetenceImportAdapter.parse("{ \"kind\": "),
                "ikke gyldig JSON");
        // A bare array is valid JSON and still not an export file.
        assertEnvelopeProblem(CompetenceImportAdapter.parse("[]"), "JSON-objekt");
    }

    @Test
    @DisplayName("a file with no topics is refused rather than reported as a no-op import")
    void emptyTopicsIsAnEnvelopeProblem() {
        assertEnvelopeProblem(CompetenceImportAdapter.parse("""
                { "kind": "kompetencemodul-full-export", "formatVersion": 2, "topics": [] }
                """), "ingen emner");
        assertEnvelopeProblem(CompetenceImportAdapter.parse("""
                { "kind": "kompetencemodul-full-export", "formatVersion": 2 }
                """), "ingen emner");
    }

    // -----------------------------------------------------------------------

    private static TopicErrors problemFor(Result result, String compId) {
        assertTrue(result.hasProblems(), "expected " + compId + " to be rejected");
        return result.problems().stream()
                .filter(p -> compId.equals(p.compId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no problems reported for " + compId + ": " + result.problems()));
    }

    private static void assertEnvelopeProblem(Result result, String fragment) {
        assertTrue(result.hasProblems(), "expected the envelope to be rejected");
        assertEquals(1, result.problems().size(),
                "an envelope-level failure is reported once, not once per topic: "
                        + result.problems());
        TopicErrors problem = result.problems().get(0);
        assertNull(problem.compId(), "an envelope-level problem belongs to no topic");
        assertTrue(problem.errors().stream().anyMatch(e -> e.contains(fragment)),
                "expected an error mentioning \"" + fragment + "\", got " + problem.errors());
        assertTrue(result.topics().isEmpty(), "nothing may survive a rejected envelope");
    }
}
