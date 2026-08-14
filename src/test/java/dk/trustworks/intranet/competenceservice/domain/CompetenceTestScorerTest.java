package dk.trustworks.intranet.competenceservice.domain;

import dk.trustworks.intranet.competenceservice.content.CompetenceContent;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §6.4. Server-side scoring is what makes the evidence worth keeping, so every
 * malformed-submission path has to be a clean 400 with no partial score written.
 */
class CompetenceTestScorerTest {

    private static final BigDecimal EIGHTY = new BigDecimal("0.800");

    private static CompetenceContent.TestPayload payload(int questionCount) {
        List<CompetenceContent.Question> questions = new java.util.ArrayList<>();
        for (int i = 1; i <= questionCount; i++) {
            String q = "q" + i;
            questions.add(new CompetenceContent.Question(q, "Spørgsmål " + i, List.of(
                    new CompetenceContent.Option(q + "o1", "rigtigt", true),
                    new CompetenceContent.Option(q + "o2", "forkert", false),
                    new CompetenceContent.Option(q + "o3", "forkert", false),
                    new CompetenceContent.Option(q + "o4", "forkert", false))));
        }
        return new CompetenceContent.TestPayload(questions);
    }

    /** Answer the first {@code correct} questions right, the rest wrong. */
    private static Map<String, String> answers(int total, int correct) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 1; i <= total; i++) {
            map.put("q" + i, "q" + i + (i <= correct ? "o1" : "o2"));
        }
        return map;
    }

    @Test
    void allCorrectPasses() {
        var result = CompetenceTestScorer.score(payload(10), answers(10, 10), EIGHTY);
        assertEquals(10, result.correctCount());
        assertEquals(10, result.questionCount());
        assertEquals(0, result.score().compareTo(new BigDecimal("1.0000")));
        assertTrue(result.passed());
    }

    @Test
    void allWrongFails() {
        var result = CompetenceTestScorer.score(payload(10), answers(10, 0), EIGHTY);
        assertEquals(0, result.correctCount());
        assertFalse(result.passed());
    }

    @Test
    @DisplayName("exactly at the threshold passes")
    void thresholdBoundaryIsInclusive() {
        assertTrue(CompetenceTestScorer.score(payload(10), answers(10, 8), EIGHTY).passed());
        assertFalse(CompetenceTestScorer.score(payload(10), answers(10, 7), EIGHTY).passed());
    }

    @Test
    @DisplayName("the threshold on the attempt decides, not the current global setting")
    void frozenThresholdDecides() {
        // The same 8/10 submission, scored under the threshold each attempt was started
        // with. This is why threshold_snapshot is a column and not a lookup.
        var answers = answers(10, 8);
        assertTrue(CompetenceTestScorer.score(payload(10), answers, new BigDecimal("0.800")).passed());
        assertFalse(CompetenceTestScorer.score(payload(10), answers, new BigDecimal("0.900")).passed());
    }

    @Test
    void scoreIsAFractionToFourPlaces() {
        var result = CompetenceTestScorer.score(payload(3), answers(3, 1), EIGHTY);
        assertEquals(new BigDecimal("0.3333"), result.score());
    }

    // ---- malformed submissions ------------------------------------------

    @Test
    void unknownOptionIdIsRejected() {
        Map<String, String> bad = answers(10, 10);
        bad.put("q3", "q3o99");
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> CompetenceTestScorer.score(payload(10), bad, EIGHTY));
        assertEquals(400, e.getResponse().getStatus());
        assertTrue(e.getMessage().contains("q3o99"));
    }

    @Test
    @DisplayName("an option id belonging to a DIFFERENT question is rejected")
    void crossQuestionOptionIdIsRejected() {
        Map<String, String> bad = answers(10, 10);
        bad.put("q3", "q4o1");
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> CompetenceTestScorer.score(payload(10), bad, EIGHTY));
        assertEquals(400, e.getResponse().getStatus());
    }

    @Test
    void unknownQuestionIdIsRejected() {
        Map<String, String> bad = answers(10, 10);
        bad.put("q99", "q99o1");
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> CompetenceTestScorer.score(payload(10), bad, EIGHTY));
        assertEquals(400, e.getResponse().getStatus());
        assertTrue(e.getMessage().contains("q99"));
    }

    @Test
    @DisplayName("a missing answer is refused rather than scored as wrong")
    void missingAnswerIsRejected() {
        Map<String, String> partial = answers(10, 10);
        partial.remove("q7");
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> CompetenceTestScorer.score(payload(10), partial, EIGHTY));
        assertEquals(400, e.getResponse().getStatus());
    }

    @Test
    void blankAnswerIsRejected() {
        Map<String, String> blank = answers(10, 10);
        blank.put("q2", "  ");
        assertThrows(WebApplicationException.class,
                () -> CompetenceTestScorer.score(payload(10), blank, EIGHTY));
    }

    @Test
    void emptyPayloadIsRejected() {
        assertThrows(WebApplicationException.class, () -> CompetenceTestScorer.score(
                new CompetenceContent.TestPayload(List.of()), Map.of(), EIGHTY));
    }

    @Test
    void nullAnswersAreRejected() {
        assertThrows(WebApplicationException.class,
                () -> CompetenceTestScorer.score(payload(3), null, EIGHTY));
    }

    @Test
    @DisplayName("a frozen version with no correct option is a 500, never a silent zero")
    void malformedFrozenVersionIsRefused() {
        var broken = new CompetenceContent.TestPayload(List.of(
                new CompetenceContent.Question("q1", "?", List.of(
                        new CompetenceContent.Option("q1o1", "a", false),
                        new CompetenceContent.Option("q1o2", "b", false)))));
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> CompetenceTestScorer.score(broken, Map.of("q1", "q1o1"), EIGHTY));
        assertEquals(500, e.getResponse().getStatus());
    }
}
