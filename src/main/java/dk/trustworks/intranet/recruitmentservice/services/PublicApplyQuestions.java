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
 * Wording and required-ness mirror the Airtable form this system
 * replaces: the questions substitute a classic motivated application, so
 * the first three are required; STRENGTHS stays optional. The resource
 * enforces required answers server-side ({@code ANSWER_REQUIRED}).
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
                    "Hvad har fået dig til at søge netop Trustworks?",
                    "Fortæl kort, hvad der gjorde dig nysgerrig på os — og hvorfor det skal være "
                            + "Trustworks og ikke et andet konsulenthus.",
                    true),
            new Question(
                    "DNA_MATCH",
                    "Livet som konsulent — fordele og udfordringer",
                    "Vi sætter pris på, at du har gjort dig tanker om at være konsulent. Hvor ser du "
                            + "dine fordele, og hvad bliver dine største udfordringer i netop dette job?",
                    true),
            new Question(
                    "BEST_TASKS",
                    "Hvilke typer opgaver trives du bedst med?",
                    "Nævn gerne mindst to — de opgaver, der giver dig energi og får dig til at yde "
                            + "dit bedste.",
                    true),
            new Question(
                    "STRENGTHS",
                    "Hvor kan dine erfaringer og styrker komme i spil hos os?",
                    "Valgfrit — men et konkret eksempel på, hvor du gør en forskel, er altid "
                            + "velkomment.",
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
