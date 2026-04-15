package dk.trustworks.intranet.utils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ISO 3166-1 alpha-2 country code → full English country name suitable for the
 * e-conomic Customers API v3.1.0 `country` field (max 50 chars).
 *
 * Strategy: a small override map handles cases where e-conomic expects a
 * non-standard spelling; everything else falls back to {@link Locale} so the
 * mapping covers all ISO codes. Unknown codes throw — finalization should fail
 * loudly rather than silently send garbage to e-conomic.
 */
public final class CountryCodeMapper {

    // Verified spellings expected by e-conomic. Add to this table when a
    // sandbox roundtrip surfaces a mismatch with the JDK Locale default.
    private static final Map<String, String> OVERRIDES = Map.of(
            "DK", "Denmark",
            "NO", "Norway",
            "GB", "United Kingdom",
            "SE", "Sweden",
            "US", "United States",
            "DE", "Germany"
    );

    // Canonical set of ISO 3166-1 alpha-2 codes registered with the JDK.
    private static final Set<String> VALID_ISO_CODES =
            Arrays.stream(Locale.getISOCountries()).collect(Collectors.toUnmodifiableSet());

    private CountryCodeMapper() {}

    public static String toEconomicsName(String iso2) {
        if (iso2 == null || iso2.isBlank()) {
            throw new IllegalArgumentException("Country code is required");
        }
        String upper = iso2.trim().toUpperCase(Locale.ROOT);
        if (!VALID_ISO_CODES.contains(upper)) {
            throw new IllegalArgumentException("Unknown ISO 3166-1 alpha-2 country code: " + iso2);
        }
        String override = OVERRIDES.get(upper);
        if (override != null) return override;

        String name = new Locale("", upper).getDisplayCountry(Locale.ENGLISH);
        if (name.isBlank() || name.equalsIgnoreCase(upper)) {
            throw new IllegalArgumentException("Cannot resolve country name for ISO code: " + iso2);
        }
        return name;
    }
}
