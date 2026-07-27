package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;

import java.util.Locale;

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
}
