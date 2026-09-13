package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * The one spelling of a name that TrustLink and Intra can be compared on.
 *
 * <p>TrustLink is populated from LinkedIn profiles and from whatever a colleague typed
 * into it over the last decade; Intra's {@code user.fullname} is populated from payroll.
 * The same person is therefore {@code "André Engsbye"} in one system and
 * {@code "André  Engsbye Rasmussen"} in the other — different middle names, a stray double
 * space, {@code "Michelle R. Cantor"} with a punctuated initial, and Danish letters that
 * arrive precomposed from one source and decomposed from the other. Comparing those
 * strings directly matches almost nobody, which is why every rung of
 * {@link TrustLinkTrustworkerMatcher}'s ladder and every company comparison in
 * {@link TrustLinkCompanyMatcher} goes through here first.
 *
 * <h2>What it does, in order, and why the order matters</h2>
 * <ol>
 *   <li><b>NFC compose.</b> {@code "å"} may arrive as U+00E5 or as {@code a} + U+030A.
 *       Composing first means step 3 sees one form, not two.</li>
 *   <li><b>Lower-case</b> (root locale — Turkish dotless-i would otherwise depend on the
 *       server's default locale, which no test would ever catch).</li>
 *   <li><b>Scandinavian and German letters spelled out</b>: {@code æ→ae}, {@code ø→oe},
 *       {@code å→aa}, {@code ä→ae}, {@code ö→oe}, {@code ü→ue}. This has to happen BEFORE
 *       the accent strip: NFKD would turn {@code ö} into {@code o} and {@code å} into
 *       {@code a}, so {@code "Bøggild"} and {@code "Boggild"} would collide while
 *       {@code "Bøggild"} and the Danish transliteration {@code "Boeggild"} would not —
 *       exactly backwards from how Danish names are actually re-typed.</li>
 *   <li><b>NFKD + combining-mark strip</b>, which folds the remaining accents
 *       ({@code é→e}, {@code ñ→n}) that carry no Danish spelling convention.</li>
 *   <li><b>Everything outside {@code [a-z0-9]} collapses to a single space</b>, then trim.
 *       This is what removes the double spaces, the dots in {@code "Michelle R. Cantor"},
 *       the parentheses in {@code "(JP Carvalho) Joao Paulo…"} and the comma before a
 *       trailing {@code ", MBA"}.</li>
 * </ol>
 *
 * <p>Note what it deliberately does NOT do: it does not drop honorifics or degree suffixes
 * such as {@code MBA}, and it does not split camel case. Dropping tokens is a matching
 * decision, not a spelling one, and it belongs to the caller that knows what the tokens
 * mean — the rungs in the trustworker matcher and the legal-form list in the company
 * matcher. Keeping this class free of such rules is what lets both callers share it.
 *
 * <p>Pure, static, no CDI. It is the correctness core of the TrustLink feature and is
 * covered by the DB-free fast test tier.
 */
public final class TrustLinkNameNormalizer {

    private TrustLinkNameNormalizer() {
    }

    /**
     * The comparable spelling of {@code raw}: lower case, Danish letters spelled out,
     * accents folded, every run of non-alphanumerics reduced to one space, trimmed.
     * Never returns null — a null or punctuation-only input normalises to {@code ""}.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String composed = Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder spelled = new StringBuilder(composed.length() + 4);
        for (int i = 0; i < composed.length(); i++) {
            char ch = composed.charAt(i);
            switch (ch) {
                case 'æ', 'ä' -> spelled.append("ae");
                case 'ø', 'ö' -> spelled.append("oe");
                case 'å' -> spelled.append("aa");
                case 'ü' -> spelled.append("ue");
                case 'ß' -> spelled.append("ss");
                default -> spelled.append(ch);
            }
        }
        String folded = Normalizer.normalize(spelled, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        return folded.replaceAll("[^a-z0-9]+", " ").strip();
    }

    /**
     * The normalised name split into its words — the unit rungs 3 and 4 of the trustworker
     * ladder and the suffix stripping in the company matcher both reason about. Empty for
     * a name that normalises to nothing.
     */
    public static List<String> tokens(String raw) {
        String normalized = normalize(raw);
        return normalized.isEmpty() ? List.of() : List.of(normalized.split(" "));
    }
}
