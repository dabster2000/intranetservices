package dk.trustworks.intranet.recruitmentservice.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interviewer coaching is Danish and the subject names are not — the two
 * halves of {@link ScorecardGuidanceCatalog} that must not drift.
 *
 * <p>The coaching is what an interviewer reads mid sitting, in a conversation
 * held in Danish, and it is also the fallback shown when the AI flag is off or
 * the model call fails — the anchors are never AI-generated at all. So it is
 * authored Danish, guarded here rather than left to whoever edits the file
 * next.</p>
 *
 * <p>The {@code label}, by contrast, is snapshotted per position into
 * {@code recruitment_positions.scorecard_template}, and the web scorecard
 * dialog and the Slack modal render that stored copy while the interview room
 * renders the catalogue's. Translating the label here alone makes one subject
 * read Danish on one surface and English on another with nothing failing
 * anywhere — so the names are pinned below, and changing one is a data
 * migration decision, not an edit.</p>
 *
 * <p>DB-free by design: runs in the fast tier that gates deploys.</p>
 */
class ScorecardGuidanceCatalogTest {

    /**
     * Whole English words that no Danish sentence contains. Deliberately short
     * and unambiguous — "at", "for" and "under" are Danish words too and are
     * not on the list.
     */
    private static final List<String> ENGLISH_TELLS = List.of(
            "the", "and", "with", "you", "your", "their", "they",
            "what", "when", "how", "about", "would", "not", "is", "are");

    /** The names, exactly. Changing one of these is a migration, not a rename. */
    @Test
    @DisplayName("the six subject names are untouched — the room and the stored template agree")
    void subjectNamesAreNotTranslated() {
        assertEquals(
                List.of("Why consulting",
                        "Culture — Good People, learning & sharing",
                        "Self-leadership & structure",
                        "Handling uncertainty",
                        "Faglighed & formidling",
                        "Commercial drive"),
                ScorecardGuidanceCatalog.standard().stream()
                        .map(ScorecardGuidance::label).toList());
        assertEquals("Culture fit", ScorecardGuidanceCatalog
                .forCode(ScorecardGuidanceCatalog.CULTURE_FIT_LEGACY_CODE)
                .orElseThrow().label());
    }

    @Test
    @DisplayName("every subject's coaching is Danish")
    void coachingIsDanish() {
        for (ScorecardGuidance guidance : allGuidance()) {
            // Per-string this would be too strict — "Hvordan holder du en
            // relation i live?" is Danish without an æ, ø or å in it. Per
            // subject, a whole page of coaching without one is English.
            assertTrue(hasDanishLetters(String.join(" ", coachingOf(guidance))),
                    guidance.code() + ": the coaching reads as English");
            for (String text : coachingOf(guidance)) {
                assertEnglishFree(guidance.code(), text);
            }
        }
        assertTrue(hasDanishLetters(ScorecardGuidanceCatalog.USAGE_NOTE));
        assertEnglishFree("USAGE_NOTE", ScorecardGuidanceCatalog.USAGE_NOTE);
    }

    /**
     * The translation had to preserve the shape the surfaces render, not just
     * the words: four anchors is the 1–4 scale, and a subject with no probes
     * leaves the guide rail and the Slack modal blank.
     */
    @Test
    @DisplayName("translating the copy left the coaching shape intact")
    void coachingShapeSurvivedTheTranslation() {
        for (ScorecardGuidance guidance : allGuidance()) {
            assertEquals(ScorecardGuidance.ANCHOR_COUNT, guidance.anchors().size(),
                    guidance.code());
            assertFalse(guidance.probes().isEmpty(), guidance.code());
            for (String text : coachingOf(guidance)) {
                assertFalse(text.isBlank(), guidance.code() + " has a blank coaching string");
            }
        }
    }

    /** The catalogue's own label is what a new position is seeded with. */
    @Test
    @DisplayName("a new position is still seeded with the catalogue's names")
    void standardTemplateCarriesTheCatalogueNames() {
        assertEquals(
                ScorecardGuidanceCatalog.standard().stream()
                        .map(ScorecardGuidance::label).toList(),
                ScorecardGuidanceCatalog.standardTemplate().stream()
                        .map(ScorecardAttribute::label).toList());
    }

    // ---- helpers ------------------------------------------------------------

    private static List<ScorecardGuidance> allGuidance() {
        List<ScorecardGuidance> all = new ArrayList<>(ScorecardGuidanceCatalog.standard());
        all.add(ScorecardGuidanceCatalog.forCode(ScorecardGuidanceCatalog.CULTURE_FIT_LEGACY_CODE)
                .orElseThrow());
        return all;
    }

    /** Everything a human reads under the name — the name itself excluded. */
    private static List<String> coachingOf(ScorecardGuidance guidance) {
        List<String> texts = new ArrayList<>();
        texts.add(guidance.shortHint());
        texts.add(guidance.whatYouAreScoring());
        texts.addAll(guidance.probes());
        texts.addAll(guidance.anchors());
        return texts;
    }

    private static boolean hasDanishLetters(String text) {
        return text.toLowerCase(Locale.ROOT).chars()
                .anyMatch(c -> c == 'æ' || c == 'ø' || c == 'å');
    }

    private static void assertEnglishFree(String code, String text) {
        for (String word : ENGLISH_TELLS) {
            assertFalse(Pattern.compile("\\b" + word + "\\b", Pattern.CASE_INSENSITIVE)
                            .matcher(text).find(),
                    code + ": untranslated English (\"" + word + "\") in — " + text);
        }
    }
}
