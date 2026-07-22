package dk.trustworks.intranet.recruitmentservice.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalization for pasted LinkedIn profile URLs (plan §P3: paste-URL
 * import, no LinkedIn API). The canonical identity of a profile is its
 * {@code /in/<slug>} segment — the same person's URL is pasted with and
 * without {@code https://}, {@code www.}, locale subdomains
 * ({@code dk.linkedin.com}), trailing slashes, query strings and mixed
 * case. Dedupe therefore compares extracted slugs, never raw URLs.
 */
public final class LinkedInUrls {

    /** Matches the /in/&lt;slug&gt; segment of any linkedin.com profile URL. */
    private static final Pattern PROFILE_PATH = Pattern.compile(
            "linkedin\\.com/in/([^/?#]+)", Pattern.CASE_INSENSITIVE);

    /** A bare slug pasted without any URL scaffolding (e.g. "jane-doe-1b2c3d"). */
    private static final Pattern BARE_SLUG = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]*");

    private LinkedInUrls() {
    }

    /**
     * Extract the normalized (lowercase, URL-decoded, trailing-slash-free)
     * profile slug from a pasted LinkedIn URL or bare slug.
     *
     * @return the slug, or {@code null} when the input is blank or not
     *         recognizable as a LinkedIn profile reference
     */
    public static String extractSlug(String pastedUrlOrSlug) {
        if (pastedUrlOrSlug == null || pastedUrlOrSlug.isBlank()) {
            return null;
        }
        String input = pastedUrlOrSlug.trim();

        Matcher matcher = PROFILE_PATH.matcher(input);
        if (matcher.find()) {
            return normalize(matcher.group(1));
        }

        // Company pages, feed links etc. mention linkedin.com without /in/ —
        // those are not profile references.
        if (input.toLowerCase(Locale.ROOT).contains("linkedin.com")) {
            return null;
        }

        // No URL scaffolding at all: accept a bare slug (the create dialog
        // lets users paste just the handle).
        if (BARE_SLUG.matcher(input).matches()) {
            return normalize(input);
        }
        return null;
    }

    /** Whether two pasted references point at the same profile slug. */
    public static boolean sameProfile(String a, String b) {
        String slugA = extractSlug(a);
        String slugB = extractSlug(b);
        return slugA != null && slugA.equals(slugB);
    }

    private static String normalize(String rawSlug) {
        String slug = rawSlug;
        try {
            slug = URLDecoder.decode(slug, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Malformed percent-encoding — compare the raw form instead.
        }
        slug = slug.strip();
        while (slug.endsWith("/")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        return slug.isEmpty() ? null : slug.toLowerCase(Locale.ROOT);
    }
}
