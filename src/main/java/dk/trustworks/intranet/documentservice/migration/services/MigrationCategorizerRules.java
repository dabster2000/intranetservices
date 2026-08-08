package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic §9.5 fallback table — the sole categorization path
 * while the migration AI kill switch is OFF, and the landing spot for
 * every MEDIUM/LOW/inconclusive AI result. Pure functions, first match
 * wins top-to-bottom (ordering matters: DECLARATION is checked before
 * CONTRACT so "Tro og love-erklæring" doesn't hit "aftale").
 *
 * <p>Matching is case- and diacritic-insensitive: both the input and the
 * keywords are folded to the æ→ae / ø→oe / å→aa canonical form.</p>
 */
final class MigrationCategorizerRules {

    private MigrationCategorizerRules() { }

    record RuleResult(EmployeeDocumentCategory category, boolean archived, String label) { }

    /**
     * @param path     relative folder path under the personal folder ("" / null for root
     *                 files and the legacy re-home, which has filenames only)
     * @param filename the original filename
     */
    static RuleResult categorize(String path, String filename) {
        String p = fold(path);
        String n = fold(filename);
        boolean archived = p.contains("arkiv");

        EmployeeDocumentCategory category;
        String label = null;

        if (p.contains("onboarding")
                || containsAny(n, "pas", "passport", "sundhed", "health", "id_", "koerekort", "drivers")) {
            category = EmployeeDocumentCategory.IDENTITY;
        } else if (p.contains("sygdom")) {
            category = EmployeeDocumentCategory.SICKNESS;
        } else if (p.contains("opsigelse")
                || containsAny(n, "fratraedelse", "opsigelse", "termination")) {
            category = EmployeeDocumentCategory.TERMINATION;
        } else if (containsAny(n, "loenregulering", "salary", "loenseddel")) {
            category = EmployeeDocumentCategory.SALARY;
        } else if (containsAny(n, "tillaeg", "addendum", "klausul")) {
            category = EmployeeDocumentCategory.ADDENDUM;
        } else if (containsAny(n, "tro og love", "tro_og_love", "erklaering", "loyalitetsprogram", "din del af")) {
            category = EmployeeDocumentCategory.DECLARATION;
        } else if (containsAny(n, "ferie", "vacation")) {
            category = EmployeeDocumentCategory.VACATION;
        } else if (containsAny(n, "kontrakt", "ansaettelse", "aftale", "contract")) {
            category = EmployeeDocumentCategory.CONTRACT;
        } else if (n.endsWith(".eml") || n.endsWith(".msg")) {
            category = EmployeeDocumentCategory.OTHER;
            label = subjectishLabel(filename);
        } else {
            category = EmployeeDocumentCategory.OTHER;
        }
        return new RuleResult(category, archived, label);
    }

    /** Casefold + Danish diacritic folding, matching the keyword forms above. */
    static String fold(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace("æ", "ae").replace("ø", "oe").replace("å", "aa");
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }

    /** ".eml"/".msg" label: the filename without its extension reads like a subject. */
    static String subjectishLabel(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        return base.replace('_', ' ').trim();
    }

    // ── Display names (V476) ───────────────────────────────────────────────

    /**
     * The deterministic display-name builder — the AI-off path, and the
     * landing spot for every MEDIUM/LOW/inconclusive verdict, exactly
     * like {@link #categorize} is for categories.
     *
     * <pre>{@code {YYYY-MM-DD}_{CATEGORY}_{subject}.{ext}}</pre>
     *
     * <p>The date segment is <b>omitted entirely</b> unless a real date
     * is derivable from the filename (or handed in by the caller). It
     * deliberately never falls back to {@code created_at}: that column
     * carries SharePoint's {@code lastModifiedDateTime}, so a 2019
     * contract re-filed in 2021 would be stamped 2021. A year-only hit
     * renders as {@code 2021}.</p>
     *
     * <p>The extension always comes from the original file (enforced by
     * {@link EmployeeDocumentService#normalizeDisplayName}), never from
     * anything parsed here.</p>
     *
     * @param category         the category already decided for this document
     * @param originalFilename the immutable stored filename — subject + extension source
     * @param path             relative folder path; only a fallback subject source
     * @param dateOrNull       a date the caller already knows (e.g. from an excerpt);
     *                         null ⇒ parse the filename, and omit the segment if it has none
     * @return a safe display name, or null when nothing usable could be built
     */
    static String buildDisplayName(EmployeeDocumentCategory category, String originalFilename,
                                   String path, LocalDate dateOrNull) {
        if (category == null) return null;

        String dateSegment = dateOrNull != null
                ? dateOrNull.toString()
                : parseDateSegment(originalFilename);

        String subject = subjectFrom(originalFilename);
        if (subject.isBlank()) subject = subjectFrom(lastPathSegment(path));

        StringBuilder name = new StringBuilder();
        if (dateSegment != null) name.append(dateSegment).append('_');
        name.append(category.name());
        if (!subject.isBlank()) name.append('_').append(subject);

        return EmployeeDocumentService.normalizeDisplayName(name.toString(), originalFilename);
    }

    /** Tokens that carry no meaning in a filename and are dropped from the subject. */
    private static final Set<String> NOISE_TOKENS = Set.of(
            "final", "finalfinal", "ny", "nyt", "nye", "kopi", "copy", "version",
            "v1", "v2", "v3", "rev", "arkiv", "archive", "underskrevet2");

    private static final int SUBJECT_MAX = 60;

    /**
     * Subject from the sanitized filename stem: lowercased, parenthesised
     * groups and noise tokens dropped, whitespace/underscores/dots
     * collapsed to hyphens. Danish characters are preserved — this
     * corpus is Danish and {@code lønregulering} reads better than
     * {@code loenregulering}.
     */
    static String subjectFrom(String filename) {
        if (filename == null) return "";
        String stem = EmployeeDocumentService.stripExtension(
                EmployeeDocumentService.sanitizeFilename(filename)).toLowerCase(Locale.ROOT);
        // "(2)", "(kopi)" and friends never say anything about the document.
        stem = stem.replaceAll("\\([^)]*\\)", " ");

        List<String> tokens = List.of(stem.split("[^a-z0-9æøå]+"));
        StringBuilder subject = new StringBuilder();
        for (String rawToken : tokens) {
            if (rawToken.isBlank()) continue;
            if (NOISE_TOKENS.contains(rawToken)) continue;
            // Pure digit runs are dates, counters and scan ids — noise
            // here; a real date already went into its own segment.
            if (rawToken.chars().allMatch(Character::isDigit)) continue;
            // Trailing digit runs are the same noise glued to a word
            // ("scan0001", "kontrakt2").
            String token = rawToken.replaceAll("\\d+$", "");
            if (token.isBlank() || NOISE_TOKENS.contains(token)) continue;
            if (subject.length() > 0) subject.append('-');
            subject.append(token);
            if (subject.length() >= SUBJECT_MAX) break;
        }
        String result = subject.length() > SUBJECT_MAX
                ? subject.substring(0, SUBJECT_MAX) : subject.toString();
        return result.replaceAll("-+$", "");
    }

    /** Last segment of a relative folder path (fallback subject source). */
    static String lastPathSegment(String path) {
        if (path == null || path.isBlank()) return "";
        String trimmed = path.replaceAll("[/\\\\]+$", "");
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }

    // Ordered most-specific first: "20190304" must be read as a full
    // date, not as the year 2019 followed by junk.
    private static final Pattern ISO_DATE =
            Pattern.compile("(?<!\\d)((?:19|20)\\d{2})[-_.](\\d{1,2})[-_.](\\d{1,2})(?!\\d)");
    private static final Pattern COMPACT_DATE =
            Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(\\d{2})(\\d{2})(?!\\d)");
    private static final Pattern DANISH_DATE =
            Pattern.compile("(?<!\\d)(\\d{1,2})[-_.](\\d{1,2})[-_.]((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern YEAR_ONLY =
            Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");

    /**
     * A real document date out of the filename, or null. Recognizes
     * {@code YYYY-MM-DD}, {@code YYYYMMDD}, {@code DD-MM-YYYY} (all with
     * {@code -}, {@code _} or {@code .} separators) and a bare
     * {@code YYYY}, which renders as the year alone. Impossible dates
     * (2019-02-31) are rejected rather than coerced.
     */
    static String parseDateSegment(String filename) {
        if (filename == null || filename.isBlank()) return null;

        Matcher iso = ISO_DATE.matcher(filename);
        while (iso.find()) {
            String date = isoDate(iso.group(1), iso.group(2), iso.group(3));
            if (date != null) return date;
        }
        Matcher compact = COMPACT_DATE.matcher(filename);
        while (compact.find()) {
            String date = isoDate(compact.group(1), compact.group(2), compact.group(3));
            if (date != null) return date;
        }
        Matcher danish = DANISH_DATE.matcher(filename);
        while (danish.find()) {
            String date = isoDate(danish.group(3), danish.group(2), danish.group(1));
            if (date != null) return date;
        }
        Matcher year = YEAR_ONLY.matcher(filename);
        return year.find() ? year.group(1) : null;
    }

    /** ISO string for a real calendar date, or null when the parts don't form one. */
    private static String isoDate(String year, String month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month),
                    Integer.parseInt(day)).toString();
        } catch (NumberFormatException | DateTimeException e) {
            return null;
        }
    }
}
