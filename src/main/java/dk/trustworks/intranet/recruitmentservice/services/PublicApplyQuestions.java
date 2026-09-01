package dk.trustworks.intranet.recruitmentservice.services;

import java.util.List;
import java.util.stream.Stream;

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
 *
 * <h3>Two views, on purpose</h3>
 * {@link #all()} is the LABEL catalogue — every key that has ever been
 * asked, so a stored answer always resolves to Danish wording on the
 * profile, in the triage queue and in the AI brief regardless of which
 * questions the form asks today. {@link #asked(boolean)} is the FORM
 * catalogue — what an applicant is actually shown right now. They differ
 * only for {@link #KNOWS_SOMEONE_KEY}, which is gated by
 * {@code recruitment.apply.referrer-claim.enabled} (change request (e),
 * 2026-09-01): the question asks for another person's name, and the
 * public privacy policy must be live before it may be published.
 */
public final class PublicApplyQuestions {

    /** Hard cap per answer — matched by the resource-level validation. */
    public static final int MAX_ANSWER_LENGTH = 10_000;

    /**
     * "Do you know anyone at Trustworks?" — the flag-gated fifth question
     * (change request (e), 2026-09-01). Unlike the other four this answer
     * is not only stored as prose: the submit path matches it against the
     * employee directory and, on a confident match, links the candidate
     * through {@code recruitment_candidates.referred_by_user_uuid}.
     */
    public static final String KNOWS_SOMEONE_KEY = "KNOWS_SOMEONE";

    /**
     * Tight cap for {@link #KNOWS_SOMEONE_KEY}: the unmatched text is
     * preserved verbatim in {@code recruitment_candidates
     * .external_referrer_name}, which is {@code VARCHAR(200)}, and the
     * value is fed to the name matcher (whose AI tier is an OpenAI call on
     * an unauthenticated endpoint). A name is not an essay.
     */
    public static final int KNOWS_SOMEONE_MAX_LENGTH = 200;

    /**
     * One question of the public form: stable key + Danish wording.
     *
     * @param maxLength per-question cap enforced by the resource; the four
     *                  original questions carry {@link #MAX_ANSWER_LENGTH},
     *                  KNOWS_SOMEONE is capped far tighter (see
     *                  {@link #KNOWS_SOMEONE_MAX_LENGTH}). Exposed on the
     *                  public form config so the input can bound itself.
     */
    public record Question(String key, String label, String helpText, boolean required,
                           int maxLength) {
    }

    /** The four questions every applicant has always been asked. */
    private static final List<Question> CORE = List.of(
            new Question(
                    "WHY_TRUSTWORKS",
                    "Hvad har fået dig til at søge netop Trustworks?",
                    "Fortæl kort, hvad der gjorde dig nysgerrig på os — og hvorfor det skal være "
                            + "Trustworks og ikke et andet konsulenthus.",
                    true, MAX_ANSWER_LENGTH),
            new Question(
                    "DNA_MATCH",
                    "Livet som konsulent — fordele og udfordringer",
                    "Vi sætter pris på, at du har gjort dig tanker om at være konsulent. Hvor ser du "
                            + "dine fordele, og hvad bliver dine største udfordringer i netop dette job?",
                    true, MAX_ANSWER_LENGTH),
            new Question(
                    "BEST_TASKS",
                    "Hvilke typer opgaver trives du bedst med?",
                    "Nævn gerne mindst to — de opgaver, der giver dig energi og får dig til at yde "
                            + "dit bedste.",
                    true, MAX_ANSWER_LENGTH),
            new Question(
                    "STRENGTHS",
                    "Hvor kan dine erfaringer og styrker komme i spil hos os?",
                    "Valgfrit — men et konkret eksempel på, hvor du gør en forskel, er altid "
                            + "velkomment.",
                    false, MAX_ANSWER_LENGTH));

    /**
     * The flag-gated fifth question. Optional on purpose: an applicant who
     * knows nobody must be able to submit, and an empty answer must never
     * be read as "no". The help text is the applicant's Art. 14 heads-up —
     * the named colleague is told, and told that the claim is unverified —
     * so it may be reworded but never shortened past that promise.
     */
    private static final Question KNOWS_SOMEONE = new Question(
            KNOWS_SOMEONE_KEY,
            "Kender du nogen hos Trustworks?",
            "Valgfrit. Skriv navnet på den, du kender hos os — vi bruger det udelukkende til at "
                    + "forstå, hvordan du er kommet i kontakt med Trustworks. Vi giver personen "
                    + "besked om, at du har nævnt dem, og at det er din oplysning, ikke deres "
                    + "anbefaling. Kender du ingen, springer du bare feltet over.",
            false, KNOWS_SOMEONE_MAX_LENGTH);

    /** Every key that has ever been asked — the label catalogue. */
    private static final List<Question> ALL =
            Stream.concat(CORE.stream(), Stream.of(KNOWS_SOMEONE)).toList();

    private static final List<String> ALL_KEYS = ALL.stream().map(Question::key).toList();

    private PublicApplyQuestions() {
    }

    /**
     * The ordered LABEL catalogue, immutable — every key including the
     * flag-gated ones. Display paths (candidate profile, triage queue, AI
     * brief) must use this so a stored answer never renders as a raw key
     * after a flag is turned back off.
     */
    public static List<Question> all() {
        return ALL;
    }

    /**
     * The ordered FORM catalogue — what the public form asks right now.
     *
     * @param referrerClaimEnabled value of
     *        {@code recruitment.apply.referrer-claim.enabled}; false ⇒ the
     *        KNOWS_SOMEONE question is not published and an
     *        {@code answer_KNOWS_SOMEONE} posted anyway is dropped by the
     *        resource.
     */
    public static List<Question> asked(boolean referrerClaimEnabled) {
        return referrerClaimEnabled ? ALL : CORE;
    }

    /** The stable question keys, in display order (label catalogue). */
    public static List<String> keys() {
        return ALL_KEYS;
    }

    /** The multipart field name carrying the answer for a question key. */
    public static String formFieldName(String key) {
        return "answer_" + key;
    }
}
