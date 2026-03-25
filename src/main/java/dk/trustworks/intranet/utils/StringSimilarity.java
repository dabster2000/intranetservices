package dk.trustworks.intranet.utils;

/**
 * String similarity algorithms for fuzzy matching.
 * <p>
 * Contains a pure-Java Jaro-Winkler implementation with no external dependencies.
 * Used for deduplication of client names and other entity matching scenarios.
 */
public final class StringSimilarity {

    /** Default Winkler prefix bonus scaling factor. Standard value per the original paper. */
    private static final double WINKLER_SCALING_FACTOR = 0.1;

    /** Maximum prefix length considered by the Winkler adjustment. */
    private static final int MAX_PREFIX_LENGTH = 4;

    private StringSimilarity() {
        // Utility class — no instantiation
    }

    /**
     * Computes Jaro-Winkler similarity between two strings.
     * <p>
     * Returns a value between 0.0 (no similarity) and 1.0 (identical strings).
     * The Winkler adjustment boosts the score for strings that share a common prefix,
     * which is useful for catching typos and minor variations in names.
     *
     * @param s1 first string (null-safe — nulls are treated as empty)
     * @param s2 second string (null-safe — nulls are treated as empty)
     * @return similarity score in [0.0, 1.0]
     */
    public static double jaroWinkler(String s1, String s2) {
        if (s1 == null) s1 = "";
        if (s2 == null) s2 = "";

        if (s1.equals(s2)) {
            return 1.0;
        }

        double jaroScore = jaro(s1, s2);

        // Winkler adjustment: boost score for common prefix
        int prefixLength = commonPrefixLength(s1, s2);
        return jaroScore + (prefixLength * WINKLER_SCALING_FACTOR * (1.0 - jaroScore));
    }

    /**
     * Computes the Jaro similarity between two strings.
     *
     * @param s1 first string (must not be null)
     * @param s2 second string (must not be null)
     * @return Jaro similarity in [0.0, 1.0]
     */
    static double jaro(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();

        if (len1 == 0 && len2 == 0) {
            return 1.0;
        }
        if (len1 == 0 || len2 == 0) {
            return 0.0;
        }

        // Characters are considered matching if they are the same and within the match window
        int matchWindow = Math.max(0, Math.max(len1, len2) / 2 - 1);

        boolean[] s1Matched = new boolean[len1];
        boolean[] s2Matched = new boolean[len2];

        int matches = 0;
        int transpositions = 0;

        // Find matching characters
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchWindow);
            int end = Math.min(i + matchWindow + 1, len2);

            for (int j = start; j < end; j++) {
                if (s2Matched[j] || s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                s1Matched[i] = true;
                s2Matched[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) {
            return 0.0;
        }

        // Count transpositions (matched characters that appear in different order)
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matched[i]) continue;
            while (!s2Matched[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double m = matches;
        return (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0;
    }

    /**
     * Returns the length of the common prefix between two strings, up to {@link #MAX_PREFIX_LENGTH}.
     */
    private static int commonPrefixLength(String s1, String s2) {
        int limit = Math.min(Math.min(s1.length(), s2.length()), MAX_PREFIX_LENGTH);
        int i = 0;
        while (i < limit && s1.charAt(i) == s2.charAt(i)) {
            i++;
        }
        return i;
    }
}
