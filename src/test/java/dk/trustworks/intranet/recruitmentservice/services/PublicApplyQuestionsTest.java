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
        // Display order mirrors the Airtable form the system replaces:
        // motivation → consultant reflection → tasks → strengths, then the
        // flag-gated referrer question last.
        assertEquals(List.of("WHY_TRUSTWORKS", "DNA_MATCH", "BEST_TASKS", "STRENGTHS",
                        "KNOWS_SOMEONE"),
                PublicApplyQuestions.keys(),
                "question keys are persisted on answers rows — never rename or reorder silently");
    }

    @Test
    void requiredFlags_matchAirtableParity_withDanishWordingPresent() {
        assertEquals(5, PublicApplyQuestions.all().size());
        for (PublicApplyQuestions.Question question : PublicApplyQuestions.all()) {
            boolean optional = question.key().equals("STRENGTHS")
                    || question.key().equals(PublicApplyQuestions.KNOWS_SOMEONE_KEY);
            assertEquals(!optional, question.required(),
                    "the questions replace the motivated application — only STRENGTHS and "
                            + "KNOWS_SOMEONE are optional");
            assertFalse(question.label().isBlank(), "label missing for " + question.key());
            assertFalse(question.helpText().isBlank(), "helpText missing for " + question.key());
            assertTrue(question.maxLength() > 0, "maxLength missing for " + question.key());
        }
    }

    @Test
    void knowsSomeone_isOptional_soAnApplicantWhoKnowsNobodyCanSubmit() {
        PublicApplyQuestions.Question knowsSomeone = PublicApplyQuestions.all().stream()
                .filter(q -> q.key().equals(PublicApplyQuestions.KNOWS_SOMEONE_KEY))
                .findFirst()
                .orElseThrow();
        assertFalse(knowsSomeone.required(),
                "an applicant who knows nobody must be able to submit — a blank answer is "
                        + "not a validation failure and must never be read as 'no'");
    }

    @Test
    void knowsSomeone_helpText_tellsTheApplicantTheNamedPersonIsToldAndTheClaimIsUnverified() {
        // Art. 14 heads-up: the applicant is naming a third party, so the
        // microcopy must promise both the notice and its unverified status.
        // Reword freely, but never past these two promises.
        String help = PublicApplyQuestions.all().stream()
                .filter(q -> q.key().equals(PublicApplyQuestions.KNOWS_SOMEONE_KEY))
                .findFirst()
                .orElseThrow()
                .helpText()
                .toLowerCase();
        assertTrue(help.contains("besked"), "help text must say the named person is told");
        assertTrue(help.contains("anbefaling"),
                "help text must say the claim is not a recommendation from that person");
    }

    @Test
    void knowsSomeone_isCappedToTheExternalReferrerColumn() {
        assertEquals(200, PublicApplyQuestions.KNOWS_SOMEONE_MAX_LENGTH,
                "external_referrer_name is VARCHAR(200); a longer answer could not be stored, "
                        + "and the value is also fed to an OpenAI-backed matcher on a public "
                        + "endpoint");
        assertEquals(PublicApplyQuestions.KNOWS_SOMEONE_MAX_LENGTH,
                PublicApplyQuestions.all().stream()
                        .filter(q -> q.key().equals(PublicApplyQuestions.KNOWS_SOMEONE_KEY))
                        .findFirst().orElseThrow().maxLength());
    }

    @Test
    void asked_publishesTheReferrerQuestionOnlyWhenTheFlagIsOn() {
        // The launch gate: the question asks for a colleague's name, and the
        // public privacy policy must be live before it may be published.
        assertEquals(List.of("WHY_TRUSTWORKS", "DNA_MATCH", "BEST_TASKS", "STRENGTHS"),
                PublicApplyQuestions.asked(false).stream()
                        .map(PublicApplyQuestions.Question::key).toList());
        assertEquals(PublicApplyQuestions.keys(),
                PublicApplyQuestions.asked(true).stream()
                        .map(PublicApplyQuestions.Question::key).toList());
    }

    @Test
    void all_keepsEveryKey_soStoredAnswersNeverRenderAsRawKeys() {
        // all() is the LABEL catalogue: turning the flag back off must not
        // strip the Danish wording off answers already collected.
        assertTrue(PublicApplyQuestions.all().stream()
                        .anyMatch(q -> q.key().equals(PublicApplyQuestions.KNOWS_SOMEONE_KEY)),
                "all() must carry every key ever asked, independent of the flag");
    }

    @Test
    void formFieldNames_followTheAnswerPrefix() {
        assertEquals("answer_WHY_TRUSTWORKS", PublicApplyQuestions.formFieldName("WHY_TRUSTWORKS"));
        assertEquals("answer_KNOWS_SOMEONE",
                PublicApplyQuestions.formFieldName(PublicApplyQuestions.KNOWS_SOMEONE_KEY));
    }

    @Test
    void maxAnswerLength_isTenThousand() {
        assertEquals(10_000, PublicApplyQuestions.MAX_ANSWER_LENGTH);
    }

    @Test
    void questionList_isImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> PublicApplyQuestions.all().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> PublicApplyQuestions.asked(false).clear());
    }

    @Test
    void questionKeys_fitTheAnswerColumn() {
        for (String key : PublicApplyQuestions.keys()) {
            assertTrue(key.length() <= 40,
                    "question_key column is VARCHAR(40); '" + key + "' would not fit");
        }
    }
}
