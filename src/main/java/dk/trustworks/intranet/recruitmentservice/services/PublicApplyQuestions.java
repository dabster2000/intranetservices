package dk.trustworks.intranet.recruitmentservice.services;

import java.util.List;

/**
 * The code-defined default question set for the P5 public application
 * forms (position forms and the unsolicited form share it). Deliberately
 * NOT configurable per position in P5 — per-position question
 * configuration arrives with {@code /recruitment/settings} in a later
 * phase; until then this class is the single source of truth for keys,
 * Danish wording and ordering.
 * <p>
 * The {@code key} values are the stable question codes persisted on
 * {@code recruitment_application_answers.question_key} — display and
 * reporting never interpret wording, so labels/help texts may be reworded
 * freely; keys must never change once answers exist.
 * <p>
 * All four questions are optional (spec decision: the CV is the only
 * required artifact; the cover letter carries the motivation).
 */
public final class PublicApplyQuestions {

    /** Hard cap per answer — matched by the resource-level validation. */
    public static final int MAX_ANSWER_LENGTH = 10_000;

    /** One question of the public form: stable key + Danish wording. */
    public record Question(String key, String label, String helpText, boolean required) {
    }

    private static final List<Question> QUESTIONS = List.of(
            new Question(
                    "WHY_TRUSTWORKS",
                    "Hvorfor Trustworks?",
                    "Fortæl kort, hvorfor Trustworks er interessant for dig. Fx: 'Jeg vil arbejde med "
                            + "digitalisering i den offentlige sektor sammen med erfarne kolleger.'",
                    false),
            new Question(
                    "BEST_TASKS",
                    "Hvilke opgaver trives du bedst med?",
                    "Beskriv de typer opgaver, der giver dig energi. Fx: 'Workshops med kunder og design "
                            + "af løsninger.'",
                    false),
            new Question(
                    "DNA_MATCH",
                    "Hvad matcher dig bedst ved Trustworks?",
                    "Hvad i vores måde at arbejde på passer godt til dig? Det er fint at nævne konkrete "
                            + "eksempler.",
                    false),
            new Question(
                    "STRENGTHS",
                    "Erfaringer og styrker",
                    "Dine vigtigste erfaringer og styrker — gerne med et konkret eksempel.",
                    false));

    private PublicApplyQuestions() {
    }

    /** The ordered question set, immutable. */
    public static List<Question> all() {
        return QUESTIONS;
    }

    /** The stable question keys, in display order. */
    public static List<String> keys() {
        return QUESTIONS.stream().map(Question::key).toList();
    }

    /** The multipart field name carrying the answer for a question key. */
    public static String formFieldName(String key) {
        return "answer_" + key;
    }
}
