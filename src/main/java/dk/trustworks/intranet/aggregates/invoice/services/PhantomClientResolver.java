package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.dto.PhantomClientSuggestion;
import dk.trustworks.intranet.aggregates.invoice.model.enums.SuggestionMethod;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Proposes a real client for a phantom clientname label (an e-conomic account
 * label). Used ONLY to populate the review queue; an admin confirms before any
 * mapping is written. Never auto-applies.
 */
@ApplicationScoped
public class PhantomClientResolver {

    /** Known revenue-account prefixes stripped before matching (case-insensitive). */
    static final List<String> KNOWN_PREFIXES = List.of("Konsulenthonorar ", "Salg ");

    private final ClientService clientService;

    @Inject
    public PhantomClientResolver(ClientService clientService) {
        this.clientService = clientService;
    }

    /**
     * Suggest a client for a phantom label. Order: prefix-strip, then
     * exact (case-insensitive), then Jaro-Winkler fuzzy, then substring/contains.
     */
    public PhantomClientSuggestion suggest(String clientname) {
        String stripped = stripKnownPrefixes(clientname);
        if (stripped.isEmpty()) {
            return PhantomClientSuggestion.none();
        }

        // 1. Exact (case-insensitive) on the stripped remainder.
        Client exact = clientService.findByExactNameIgnoreCase(stripped);
        if (exact != null) {
            return new PhantomClientSuggestion(exact.getUuid(), exact.getName(), 1.0, SuggestionMethod.EXACT);
        }

        // 2. Fuzzy (Jaro-Winkler) via ClientService (>= 0.90 threshold).
        Optional<Client> fuzzy = clientService.findFuzzyMatch(stripped);
        if (fuzzy.isPresent()) {
            Client c = fuzzy.get();
            // ClientService already enforces the 0.90 threshold; report a representative confidence.
            return new PhantomClientSuggestion(c.getUuid(), c.getName(), 0.90, SuggestionMethod.FUZZY);
        }

        // 3. Last-resort substring/contains over all clients (low confidence).
        //    Returns the first active client (DB order) whose name contains the
        //    label. A "found" suggestion must carry a real uuid, so a name match
        //    with a null uuid is skipped rather than returned as a candidate.
        String needle = stripped.toLowerCase(Locale.ROOT);
        for (Client c : clientService.findByActiveTrue()) {
            String name = c.getName();
            if (name != null && c.getUuid() != null && name.toLowerCase(Locale.ROOT).contains(needle)) {
                return new PhantomClientSuggestion(c.getUuid(), c.getName(), 0.5, SuggestionMethod.CONTAINS);
            }
        }

        return PhantomClientSuggestion.none();
    }

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
