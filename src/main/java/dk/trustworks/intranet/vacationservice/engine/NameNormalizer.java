package dk.trustworks.intranet.vacationservice.engine;

import java.util.Locale;

/**
 * Normalizes Danløn free-text names for matching: trim, collapse internal
 * whitespace, lowercase. Danish letters are kept as-is — "Sørensen" and
 * "Sorensen" are different names and must not be conflated.
 */
public final class NameNormalizer {

    private NameNormalizer() {
    }

    public static String normalize(String name) {
        if (name == null) return "";
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
