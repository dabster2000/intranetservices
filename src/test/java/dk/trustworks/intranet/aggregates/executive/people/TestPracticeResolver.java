package dk.trustworks.intranet.aggregates.executive.people;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * DB-free stand-in for {@code PracticeService.resolveFilterToken} in unit tests
 * (Phase 3): resolves the staging-shaped registry universe — the five practice
 * storage codes plus the {@code UD} sentinel row — by code (case-insensitive)
 * or by a deterministic fake uuid ({@code uuid-<code>} lowercased). Anything
 * else (including the retired {@code JK}) does not resolve, mirroring the
 * registry-backed behavior.
 */
public final class TestPracticeResolver {

    /** code → fake registry uuid, in registry sort_order. */
    public static final Map<String, String> REGISTRY_UUIDS;

    static {
        Map<String, String> uuids = new LinkedHashMap<>();
        for (String code : new String[]{"PM", "SA", "BA", "DEV", "CYB", "UD"}) {
            uuids.put(code, "uuid-" + code.toLowerCase(Locale.ROOT));
        }
        REGISTRY_UUIDS = Map.copyOf(uuids);
    }

    public static final PeopleFilterParams.PracticeTokenResolver RESOLVER = token -> {
        if (token == null || token.isBlank()) return Optional.empty();
        String upper = token.trim().toUpperCase(Locale.ROOT);
        if (REGISTRY_UUIDS.containsKey(upper)) return Optional.of(upper);
        String lower = token.trim().toLowerCase(Locale.ROOT);
        return REGISTRY_UUIDS.entrySet().stream()
                .filter(e -> e.getValue().equals(lower))
                .map(Map.Entry::getKey)
                .findFirst();
    };

    private TestPracticeResolver() {
    }
}
