package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.dto.PhantomClientSuggestion;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;

/**
 * Proposes a real client for a phantom clientname label (an e-conomic account
 * label). Used ONLY to populate the review queue; an admin confirms before any
 * mapping is written. Never auto-applies.
 */
@ApplicationScoped
public class PhantomClientResolver {

    /** Known revenue-account prefixes stripped before matching (case-insensitive). */
    static final List<String> KNOWN_PREFIXES = List.of("Konsulenthonorar ", "Salg ");

    /** Strip a known revenue-account prefix and trim. Null/blank -> "". Pure. */
    static String stripKnownPrefixes(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        for (String prefix : KNOWN_PREFIXES) {
            if (s.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return s.substring(prefix.length()).trim();
            }
        }
        return s;
    }
}
