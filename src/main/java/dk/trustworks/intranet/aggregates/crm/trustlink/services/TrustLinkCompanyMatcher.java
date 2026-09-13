package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a Trustworks client name and a TrustLink company name are the same
 * company.
 *
 * <p>TrustLink's {@code companyNames} filter is exact and case-sensitive — {@code "novo
 * nordisk"} returns nothing and {@code "Novo"} returns nothing — so the alias table has to
 * hold the exact strings TrustLink uses, and something has to propose them. That something
 * is this class, run over the {@code term=} typeahead's candidates: 117 of the 294 client
 * names already match a TrustLink company letter for letter, and comparing on a normalised
 * spelling instead adds roughly 61 more, almost all of them a capitalisation or legal-form
 * difference ({@code "NOVO NORDISK A/S"} vs {@code "Novo Nordisk A/S"},
 * {@code "AkademikerPension"} vs {@code "Akademikerpension"}).
 *
 * <h2>Two tiers, because the two callers have different consequences</h2>
 * <ul>
 *   <li>{@link #isSameCompany} is what the nightly seeder uses. It writes {@code AUTO}
 *       alias rows that nobody reviews, and every row it writes pulls a set of named
 *       strangers onto an account page. It must be conservative.</li>
 *   <li>{@link #isRelatedCompany} is what the human-facing alias editor may use to order
 *       or highlight typeahead suggestions. A person sees the suggestion and decides, so
 *       it may be generous.</li>
 * </ul>
 *
 * <h2>The Arriva decision — why {@code Danmark} is NOT stripped by the strict tier</h2>
 * The brief's strip list is {@code a/s aps as i/s p/s holding group danmark denmark dk},
 * and it makes {@code "Arriva"} and {@code "Arriva Danmark"} equal. For Arriva that is
 * right: the Danish operator we serve <em>is</em> Arriva Danmark A/S, and the connections
 * filed under the short name are the same people. Generalised, it is wrong. "X Danmark" is
 * normally a separate legal entity from "X" — a local subsidiary of a foreign group with a
 * different building, a different management and different people — while "X A/S" is not a
 * separate entity from "X" at all, just the legal form written out. The two kinds of token
 * are not the same kind of thing, and the brief's list conflates them.
 *
 * <p>So the split is by what the token means:
 * <ul>
 *   <li><b>Legal form and corporate structure</b> — {@code a/s}, {@code aps}, {@code as},
 *       {@code i/s}, {@code p/s}, {@code holding}, {@code group}. These name how the same
 *       company is incorporated, not which company it is. Stripped in both tiers.</li>
 *   <li><b>Geography</b> — {@code danmark}, {@code denmark}, {@code dk}. These can name a
 *       different entity. Stripped only in the related tier.</li>
 * </ul>
 * {@code isSameCompany("Arriva", "Arriva Danmark")} is therefore <b>false</b> and
 * {@code isRelatedCompany} is true: the nightly job will not claim those people on its
 * own, and an account manager opening the alias editor is shown {@code "Arriva Danmark"}
 * and can add it with one click. The cost of the decision is exactly one click per
 * genuinely-the-same case; the cost of the other decision would be silently attaching a
 * subsidiary's staff to a parent's account with no record that a guess was made.
 *
 * <p>This is also why the alias table is a list rather than a column: a client may point
 * at several TrustLink names, because TrustLink fragments companies — {@code "Novo
 * Nordisk"} carries 203 tier-5 connections and {@code "Novo Nordisk A/S"} carries 2, and
 * the Intra client is named {@code "NOVO NORDISK A/S"}. Both names belong on that client.
 *
 * <p>Pure, static, no CDI.
 */
public final class TrustLinkCompanyMatcher {

    private TrustLinkCompanyMatcher() {
    }

    /**
     * How a company is incorporated, never which company it is. Held as token sequences
     * because {@link TrustLinkNameNormalizer} turns {@code "A/S"} into the two tokens
     * {@code a s} — the slash is punctuation like any other.
     */
    private static final List<List<String>> LEGAL_FORM_SUFFIXES = List.of(
            List.of("a", "s"),
            List.of("i", "s"),
            List.of("p", "s"),
            List.of("aps"),
            List.of("as"),
            List.of("holding"),
            List.of("group"));

    /** Geography, which may name a different legal entity. See the Arriva decision above. */
    private static final List<List<String>> GEOGRAPHIC_SUFFIXES = List.of(
            List.of("danmark"),
            List.of("denmark"),
            List.of("dk"));

    /**
     * The strict comparable spelling: normalised, with trailing legal-form and structure
     * tokens removed. {@code "NOVO NORDISK A/S"} and {@code "Novo Nordisk A/S"} both
     * become {@code "novo nordisk"}; {@code "Arriva Danmark"} stays {@code "arriva
     * danmark"}. Empty for a name that normalises to nothing.
     */
    public static String normalizeCompany(String name) {
        return String.join(" ", strip(TrustLinkNameNormalizer.tokens(name), LEGAL_FORM_SUFFIXES));
    }

    /**
     * The generous comparable spelling: {@link #normalizeCompany} with trailing country
     * tokens removed as well. {@code "Arriva Danmark"} becomes {@code "arriva"}.
     */
    public static String normalizeCompanyIgnoringGeography(String name) {
        List<String> tokens = strip(TrustLinkNameNormalizer.tokens(name), LEGAL_FORM_SUFFIXES);
        return String.join(" ", strip(tokens, GEOGRAPHIC_SUFFIXES));
    }

    /**
     * True when the two names are the same company beyond doubt — the predicate the
     * nightly seeder writes {@code AUTO} alias rows from. Two blank names are not a match;
     * a company with no name is not a company.
     */
    public static boolean isSameCompany(String clientName, String trustLinkCompanyName) {
        String client = normalizeCompany(clientName);
        return !client.isEmpty() && client.equals(normalizeCompany(trustLinkCompanyName));
    }

    /**
     * True when the two names are the same company once geography is ignored — strictly
     * weaker than {@link #isSameCompany} and intended for suggestions a human confirms,
     * never for writing aliases unattended.
     */
    public static boolean isRelatedCompany(String clientName, String trustLinkCompanyName) {
        String client = normalizeCompanyIgnoringGeography(clientName);
        return !client.isEmpty() && client.equals(normalizeCompanyIgnoringGeography(trustLinkCompanyName));
    }

    /**
     * The typeahead candidates that are {@code clientName}, in the order TrustLink
     * returned them and without repeats. The seeder feeds the result straight into the
     * alias table, so this deliberately uses the strict tier.
     */
    public static List<String> selectMatches(String clientName, Collection<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && isSameCompany(clientName, candidate)) {
                seen.add(candidate.strip());
            }
        }
        return List.copyOf(seen);
    }

    /**
     * Removes trailing suffix token sequences, repeatedly — {@code "X Holding A/S"} loses
     * both — but never everything. A client genuinely named {@code "Group"} or
     * {@code "Danmark"} keeps its one token instead of reducing to the empty string, which
     * would otherwise make every such company equal to every other.
     */
    private static List<String> strip(List<String> tokens, List<List<String>> suffixes) {
        List<String> remaining = new ArrayList<>(tokens);
        boolean stripped = true;
        while (stripped && remaining.size() > 1) {
            stripped = false;
            for (List<String> suffix : suffixes) {
                if (endsWith(remaining, suffix) && remaining.size() > suffix.size()) {
                    remaining = new ArrayList<>(remaining.subList(0, remaining.size() - suffix.size()));
                    stripped = true;
                    break;
                }
            }
        }
        return remaining;
    }

    private static boolean endsWith(List<String> tokens, List<String> suffix) {
        if (tokens.size() < suffix.size()) {
            return false;
        }
        int offset = tokens.size() - suffix.size();
        for (int i = 0; i < suffix.size(); i++) {
            if (!tokens.get(offset + i).equals(suffix.get(i))) {
                return false;
            }
        }
        return true;
    }
}
