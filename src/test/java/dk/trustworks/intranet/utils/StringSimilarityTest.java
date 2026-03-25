package dk.trustworks.intranet.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Jaro-Winkler similarity implementation.
 * Verifies correctness against known reference values and edge cases.
 */
class StringSimilarityTest {

    private static final double DELTA = 0.001;

    // --- Exact matches ---

    @Test
    void jaroWinkler_identicalStrings_returns1() {
        assertEquals(1.0, StringSimilarity.jaroWinkler("Trustworks", "Trustworks"), DELTA);
    }

    @Test
    void jaroWinkler_bothEmpty_returns1() {
        assertEquals(1.0, StringSimilarity.jaroWinkler("", ""), DELTA);
    }

    @Test
    void jaroWinkler_bothNull_returns1() {
        assertEquals(1.0, StringSimilarity.jaroWinkler(null, null), DELTA);
    }

    // --- Completely different ---

    @Test
    void jaroWinkler_completelyDifferent_returnsLowScore() {
        double score = StringSimilarity.jaroWinkler("abc", "xyz");
        assertTrue(score < 0.5, "Completely different strings should score below 0.5, got " + score);
    }

    // --- One empty ---

    @Test
    void jaroWinkler_oneEmpty_returns0() {
        assertEquals(0.0, StringSimilarity.jaroWinkler("Trustworks", ""), DELTA);
        assertEquals(0.0, StringSimilarity.jaroWinkler("", "Trustworks"), DELTA);
    }

    @Test
    void jaroWinkler_oneNull_returns0() {
        assertEquals(0.0, StringSimilarity.jaroWinkler(null, "Trustworks"), DELTA);
        assertEquals(0.0, StringSimilarity.jaroWinkler("Trustworks", null), DELTA);
    }

    // --- Known reference values ---
    // These reference values are well-known from the Jaro-Winkler literature

    @Test
    void jaroWinkler_martha_marhta() {
        // Classic example: Jaro = 0.944, Winkler boost for 3-char prefix "mar"
        double score = StringSimilarity.jaroWinkler("MARTHA", "MARHTA");
        assertEquals(0.961, score, DELTA);
    }

    @Test
    void jaroWinkler_dwayne_duane() {
        double score = StringSimilarity.jaroWinkler("DWAYNE", "DUANE");
        assertEquals(0.84, score, 0.02);
    }

    @Test
    void jaroWinkler_dixon_dicksonx() {
        double score = StringSimilarity.jaroWinkler("DIXON", "DICKSONX");
        assertEquals(0.813, score, 0.02);
    }

    // --- Client name dedup scenarios ---

    @Test
    void jaroWinkler_trustworksWithSpace_aboveThreshold() {
        double score = StringSimilarity.jaroWinkler("trust works", "trustworks");
        assertTrue(score >= 0.90, "Trust Works vs Trustworks should be >= 0.90, got " + score);
    }

    @Test
    void jaroWinkler_minorTypo_aboveThreshold() {
        double score = StringSimilarity.jaroWinkler("trustwoks", "trustworks");
        assertTrue(score >= 0.90, "Minor typo should score >= 0.90, got " + score);
    }

    @Test
    void jaroWinkler_differentCompanies_belowThreshold() {
        double score = StringSimilarity.jaroWinkler("microsoft", "trustworks");
        assertTrue(score < 0.90, "Different companies should score < 0.90, got " + score);
    }

    @Test
    void jaroWinkler_samePrefix_differentCompany_belowThreshold() {
        double score = StringSimilarity.jaroWinkler("trustworks", "trustpilot");
        assertTrue(score < 0.90, "Different companies with same prefix should be < 0.90, got " + score);
    }

    // --- Symmetry ---

    @Test
    void jaroWinkler_isSymmetric() {
        double ab = StringSimilarity.jaroWinkler("Trustworks", "Trust Works");
        double ba = StringSimilarity.jaroWinkler("Trust Works", "Trustworks");
        assertEquals(ab, ba, DELTA, "Jaro-Winkler should be symmetric");
    }

    // --- Winkler prefix bonus ---

    @Test
    void jaroWinkler_sharedPrefix_scoresHigherThanSwapped() {
        // "abcxyz" vs "abcxzy" shares prefix "abcx" -- should score higher
        // than "xyzabc" vs "xzyabc" which shares no prefix
        double withPrefix = StringSimilarity.jaroWinkler("abcxyz", "abcxzy");
        double noPrefix = StringSimilarity.jaroWinkler("xyzabc", "xzyabc");
        assertTrue(withPrefix >= noPrefix,
                "Shared prefix should boost score: " + withPrefix + " vs " + noPrefix);
    }

    // --- Jaro (internal) edge cases ---

    @Test
    void jaro_singleChar_match() {
        assertEquals(1.0, StringSimilarity.jaro("a", "a"), DELTA);
    }

    @Test
    void jaro_singleChar_noMatch() {
        assertEquals(0.0, StringSimilarity.jaro("a", "b"), DELTA);
    }

    // --- Parameterized: names that SHOULD match (>= 0.90) ---

    @ParameterizedTest
    @CsvSource({
            "Trustworks, trustworks",
            "Trustworks A/S, Trustworks AS",
            "Novo Nordisk, Novo  Nordisk",
    })
    void jaroWinkler_similarNames_aboveThreshold(String a, String b) {
        double score = StringSimilarity.jaroWinkler(a.toLowerCase().trim(), b.toLowerCase().trim());
        assertTrue(score >= 0.90, a + " vs " + b + " should be >= 0.90, got " + score);
    }

    // --- Parameterized: names that should NOT match (< 0.90) ---

    @ParameterizedTest
    @CsvSource({
            "Trustworks, Microsoft",
            "Novo Nordisk, Novo Holdings",
            "LEGO, Vestas",
    })
    void jaroWinkler_differentNames_belowThreshold(String a, String b) {
        double score = StringSimilarity.jaroWinkler(a.toLowerCase().trim(), b.toLowerCase().trim());
        assertTrue(score < 0.90, a + " vs " + b + " should be < 0.90, got " + score);
    }
}
