package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P5 default question set is a wire contract: keys are persisted on
 * answers rows and the frontend renders labels/help texts verbatim —
 * this test pins the stable parts (keys, order, optionality) so a
 * reword never silently becomes a key change.
 */
class PublicApplyQuestionsTest {

    @Test
    void questionKeys_areStable_andOrdered() {
        assertEquals(List.of("WHY_TRUSTWORKS", "BEST_TASKS", "DNA_MATCH", "STRENGTHS"),
                PublicApplyQuestions.keys(),
                "question keys are persisted on answers rows — never rename or reorder silently");
    }

    @Test
    void allQuestions_areOptional_withDanishWordingPresent() {
        assertEquals(4, PublicApplyQuestions.all().size());
        for (PublicApplyQuestions.Question question : PublicApplyQuestions.all()) {
            assertFalse(question.required(),
                    "all four questions are optional — the CV is the only required artifact");
            assertFalse(question.label().isBlank(), "label missing for " + question.key());
            assertFalse(question.helpText().isBlank(), "helpText missing for " + question.key());
        }
    }

    @Test
    void formFieldNames_followTheAnswerPrefix() {
        assertEquals("answer_WHY_TRUSTWORKS", PublicApplyQuestions.formFieldName("WHY_TRUSTWORKS"));
    }

    @Test
    void maxAnswerLength_isTenThousand() {
        assertEquals(10_000, PublicApplyQuestions.MAX_ANSWER_LENGTH);
    }

    @Test
    void questionList_isImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> PublicApplyQuestions.all().clear());
    }

    @Test
    void questionKeys_fitTheAnswerColumn() {
        for (String key : PublicApplyQuestions.keys()) {
            assertTrue(key.length() <= 40,
                    "question_key column is VARCHAR(40); '" + key + "' would not fit");
        }
    }
}
