package dk.trustworks.intranet.aggregates.crm.person;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The name a person at a client is shown under, the client's shorthand for them, and the key
 * that makes two sightings of them one row (spec §4.3, decisions 2026-09-14).
 *
 * <h2>The problem this exists to solve</h2>
 * The account page keys, sorts and labels people on whatever string a source handed it. On
 * production that string is four different things at once: 13 calendar rows are
 * {@code INITIALS (Full Name)} the way Novo Nordisk's Exchange writes them, 109 are
 * {@code Full Name (INITIALS)} the way Banedanmark and KDS do, 22 carry the address itself as
 * the display name, and TrustLink carries the person's own LinkedIn spelling. So
 * {@code "STMJ (Stephan Mosko Jensen)"} is what a reader sees instead of a name (defect D5),
 * and {@code "Sif S. Broby Madsen"} from a calendar and {@code "Sif Broby Madsen"} from
 * TrustLink are two different people on the same page (defect D8). Only 6 of the 183 calendar
 * names equal a TrustLink {@code full_name} at the same client character for character.
 *
 * <h2>Why a key rather than a fuzzy match</h2>
 * The merge key is deliberately crude — lower-cased first token, a pipe, lower-cased last
 * token — because it has to be reproducible by a human reading two rows and by a UNIQUE index
 * in MariaDB, and because the thing that actually differs between two spellings of one person
 * is the middle: a middle name, an initial, a client's mailbox alias. Dropping the middle is
 * the whole rule. Two different humans who share a first and a last name at one client will
 * land on one row; §3.1 accepts that on purpose, because the alternative — matching on
 * anything softer — merges people who are not the same person at all, and nothing on the page
 * would ever show it had happened.
 *
 * <h2>Why the tokeniser splits on {@link Character#isLetterOrDigit}</h2>
 * Ported verbatim from {@code ColleagueDirectory.tokensOf}, and it must stay that way: a
 * {@code [A-Za-z]+} split shreds "Bjørn" into "bj" and "rn" and would match almost nothing in
 * a Danish consultancy, while splitting on whitespace alone leaves "(XSVES)" and
 * "Ellen-Marie" as single tokens and breaks both of the parenthesised mailbox formats. Every
 * case fold here is {@link Character#toLowerCase(char)} or {@link Locale#ROOT}, never the
 * default locale — a machine that happens to boot in a Turkish locale would otherwise fold
 * {@code I} to a dotless {@code ı} and split one person into two.
 *
 * <h2>Shared with the colleague filter on purpose</h2>
 * {@link #tokens(String)}, {@link #containsSequence(List, List)} and
 * {@link #isReductionOf(List, List)} are the colleague-at-client name rules, lifted here so
 * the registry and {@code ColleagueDirectory} answer the same question the same way. Two
 * spellings of "is this the same name" would drift, and the drift shows up as our own
 * consultants reappearing on an account as its client contacts.
 *
 * <h2>Pure by construction</h2>
 * No CDI, no {@code EntityManager}, no clock. Strings in, answers out, so the DB-free tier
 * that gates every deploy holds the whole rule.
 */
public final class PersonNames {

    /**
     * {@code STMJ (Stephan Mosko Jensen)} — the alias first, the person in brackets, the way
     * Novo Nordisk's Exchange writes it. Spelled exactly as spec §4.3 with the leading alias
     * additionally captured, because the registry keeps the shorthand: it is how a colleague
     * will hear the person referred to on site.
     *
     * <p>Upper-case only, and Danish letters are listed explicitly rather than relying on a
     * Unicode class — "AA" and "ÆØÅ" are initials, "Sara" is not, and a case-insensitive
     * pattern would swallow every "Name (something)" on the page.
     */
    private static final Pattern ALIAS_THEN_NAME = Pattern.compile("^([A-ZÆØÅ]{2,6}) \\((.+)\\)$");

    /**
     * {@code Sara Louise Vest (XSVES)} — the person first, the client's shorthand in brackets,
     * the way Banedanmark and KDS write it. Digits and hyphens occur in real aliases
     * ({@code KEFM-KDS}), which is why the class is wider than the one above.
     */
    private static final Pattern NAME_THEN_ALIAS = Pattern.compile("^(.+) \\(([A-ZÆØÅ0-9-]{2,8})\\)$");

    /**
     * A local part that is plainly a person: {@code Mickie.Storm}. Exactly two runs of
     * letters, because {@code xhvig} and {@code extnim} are mailbox aliases and inventing a
     * name out of them would put a made-up human on the account page.
     */
    private static final Pattern ADDRESS_IS_A_NAME = Pattern.compile("^(\\p{L}+)\\.(\\p{L}+)$");

    /** Runs of whitespace, collapsed so that one trailing space does not make a second person. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /** The separator in a name key. Not a character that can occur in a token. */
    private static final String KEY_SEPARATOR = "|";

    /**
     * What a source's spelling of a person reduces to.
     *
     * @param name     the name we show — never null, never blank, whitespace collapsed
     * @param initials the client's shorthand ({@code XSVES}, {@code STMJ}) when the source
     *                 carried one, else null. Null rather than "" so the nullable column and
     *                 the rendering both read as "there is no shorthand" rather than "there is
     *                 an empty one"
     * @param key      {@link #key(String)} of {@code name} — the merge key, and the value of
     *                 the {@code NAME} identity row
     */
    public record Parsed(String name, String initials, String key) { }

    private PersonNames() {
    }

    /**
     * The name, shorthand and merge key for one sighting of a person.
     *
     * <p>The display name wins over the address whenever there is one, because the address is
     * what the client's IT department issued and the display name is what a human typed. When
     * the display name IS an address — 22 production rows — the local part is read as a name
     * only when it is unambiguously one ({@code Mickie.Storm@ArbaSecurity.com} →
     * {@code Mickie Storm}); otherwise the address itself is the name
     * ({@code xhvig@bane.dk}), which is honest about what we know and is still a stable key.
     *
     * @param displayName what the source called them; may be null or blank
     * @param email       the address the sighting came from; may be null
     * @return the parsed name, or <b>null</b> when neither input carries anything at all — the
     *         caller skips such a sighting rather than materialising a nameless person
     */
    public static Parsed parse(String displayName, String email) {
        String candidate = collapse(displayName);
        if (candidate == null) {
            candidate = collapse(email);
        }
        if (candidate == null) {
            return null;
        }

        Matcher aliasFirst = ALIAS_THEN_NAME.matcher(candidate);
        if (aliasFirst.matches()) {
            String name = collapse(aliasFirst.group(2));
            if (name != null) {
                return parsed(name, aliasFirst.group(1));
            }
        }

        Matcher aliasLast = NAME_THEN_ALIAS.matcher(candidate);
        if (aliasLast.matches()) {
            String name = collapse(aliasLast.group(1));
            if (name != null) {
                return parsed(name, aliasLast.group(2));
            }
        }

        // Addresses are lower-cased before anything else is decided: Graph hands the same
        // mailbox back as Mickie.Storm@ArbaSecurity.com and mickie.storm@arbasecurity.com on
        // different events, and two casings must not become two people. The @ is located in
        // the FOLDED string, not the original: a few Unicode upper-case letters fold to two
        // characters and the index would otherwise land one short of where it was measured.
        String address = candidate.toLowerCase(Locale.ROOT);
        int at = address.indexOf('@');
        if (at > 0) {
            Matcher localPart = ADDRESS_IS_A_NAME.matcher(address.substring(0, at));
            if (localPart.matches()) {
                return parsed(titleCase(localPart.group(1)) + " " + titleCase(localPart.group(2)), null);
            }
            return parsed(address, null);
        }

        return parsed(candidate, null);
    }

    /**
     * The merge key: lower-cased first token, a pipe, lower-cased last token — except for an
     * address, which is its own key.
     *
     * <p>The middle is dropped deliberately — it is where the spellings differ. "Sif S. Broby
     * Madsen" and "Sif Broby Madsen" both key to {@code sif|madsen}, which is what turns
     * defect D8's two nodes into one person. A name that tokenises to a single word keys to
     * that word, and one that tokenises to nothing at all keys to its own lower-cased self so
     * that the NOT NULL column always has something to hold.
     *
     * <p><b>An address keys to the WHOLE address, and that exception is load-bearing.</b>
     * When {@link #parse} can read no person out of a mailbox it hands the raw address back as
     * the name — {@code xhvig@bane.dk}, 22 production rows — and first-token/last-token over
     * an ADDRESS makes the last token the top-level domain, which every mailbox at the client
     * shares. {@code ext.anne.hansen@dagrofa.dk} and {@code ext.lars.nielsen@dagrofa.dk} would
     * both key to {@code ext|dk}: two different humans collapsed onto one row, carrying one
     * another's meetings and one another's claims, with nothing on the page saying it had
     * happened. A mailbox is already stable and unique, so it is its own key. A real name
     * never contains an {@code @} — {@code parse} has already turned {@code Mickie.Storm@…}
     * into {@code Mickie Storm} by the time this is asked — so this branch cannot swallow one.
     *
     * @param name a name, an address, or anything a source called a person
     * @return the key, never null; {@code ""} only for a null name
     */
    public static String key(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.indexOf('@') >= 0) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        List<String> tokens = tokens(name);
        if (tokens.isEmpty()) {
            return name.trim().toLowerCase(Locale.ROOT);
        }
        if (tokens.size() == 1) {
            return tokens.get(0);
        }
        return tokens.get(0) + KEY_SEPARATOR + tokens.get(tokens.size() - 1);
    }

    /**
     * Lower-cased runs of letters and digits. Everything else — spaces, parentheses, hyphens,
     * commas, dots, the {@code @} — is a separator, so {@code "QNTE (Nicolas De Teilmann)"}
     * and {@code "de Teilmann, Nicolas"} tokenise to the same words in different orders.
     *
     * <p>Identical to {@code ColleagueDirectory.tokensOf} by design; that class delegates here
     * so there is exactly one tokeniser. {@link Character#toLowerCase(char)} rather than
     * {@link String#toLowerCase()} because the latter is locale-sensitive and this is Danish
     * data on machines whose default locale nobody controls.
     */
    public static List<String> tokens(String name) {
        if (name == null) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (Character.isLetterOrDigit(character)) {
                current.append(Character.toLowerCase(character));
            } else if (!current.isEmpty()) {
                tokens.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /**
     * Does {@code needle} appear in {@code haystack} as a run of whole, adjacent tokens?
     *
     * <p>Whole tokens, so "anne" never matches inside "marianne". Adjacent, so "Nicolas de
     * Teilmann" does not match an attendee line that happens to contain a Nicolas and, further
     * along, a Teilmann.
     *
     * <p><b>The argument order is the one the colleague filter calls it with:</b>
     * {@code containsSequence(attendeeTokens, employeeTokens)} — the attendee is the haystack,
     * the employee is the needle. Swapping them silently inverts the rule instead of failing
     * to compile, and the inverted rule deletes real client contacts.
     */
    public static boolean containsSequence(List<String> haystack, List<String> needle) {
        if (needle.isEmpty() || needle.size() > haystack.size()) {
            return false;
        }
        outer:
        for (int start = 0; start + needle.size() <= haystack.size(); start++) {
            for (int offset = 0; offset < needle.size(); offset++) {
                if (!haystack.get(start + offset).equals(needle.get(offset))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Does the attendee's name read as a SHORTENED form of this employee's — the same first
     * name and the same final surname, with one or more of the employee's middle names left
     * out?
     *
     * <p>{@link #containsSequence} only recognises an attendee name that contains the
     * employee's in full, which covers the client that writes MORE than we hold
     * ({@code "QNTE (Nicolas De Teilmann)"}) but not the one that writes LESS. Clients do
     * both. Dagrofa issued Nichlas Halberg Madsen the mailbox {@code extnim@dagrofa.dk} and
     * put {@code "Nichlas Madsen"} on it; our user row says {@code Nichlas} +
     * {@code Halberg Madsen}, three tokens against the attendee's two, so the containment test
     * could not match in that direction and ten meetings with our own consultant stayed on the
     * Dagrofa account as client contact.
     *
     * <p>The rule: some CONTIGUOUS window of the attendee's tokens must start on the employee's
     * first token, end on their last, be strictly shorter than the employee's name, and be an
     * in-order subsequence of the employee's tokens. A window rather than the whole name, so a
     * client prefix survives ({@code "NIM (Nichlas Madsen)"}); anchored on both ends, so it is
     * a name shortened rather than a name that merely shares a word.
     *
     * <p><b>It cannot re-open the substring hole.</b> "Marianne Hansen" against an employee
     * "Anne Hansen" still fails twice over: a two-token employee name has no middle to drop and
     * is rejected by the {@code < 3} guard, and every candidate window would have had to START
     * on {@code anne}, which is a different token from {@code marianne}. Both directions are
     * pinned by tests, here and in {@code ColleagueDirectoryTest}.
     *
     * <p>Note the argument order is the REVERSE of {@link #containsSequence}: the attendee is
     * named first here, and inside, the attendee window is the needle searched for inside the
     * employee's tokens.
     */
    public static boolean isReductionOf(List<String> attendeeTokens, List<String> employeeTokens) {
        if (employeeTokens.size() < 3 || attendeeTokens.size() < 2) {
            // Nothing to shorten: a two-token name has no middle to drop, and a one-token
            // window is the single-name match this rule refuses everywhere else.
            return false;
        }
        String first = employeeTokens.get(0);
        String last = employeeTokens.get(employeeTokens.size() - 1);
        for (int start = 0; start < attendeeTokens.size(); start++) {
            if (!attendeeTokens.get(start).equals(first)) {
                continue;
            }
            for (int end = start + 1; end < attendeeTokens.size(); end++) {
                if (!attendeeTokens.get(end).equals(last)) {
                    continue;
                }
                if (end - start + 1 < employeeTokens.size()
                        && isOrderedSubsequence(attendeeTokens.subList(start, end + 1), employeeTokens)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every token of {@code needle}, in order, somewhere in {@code haystack}. */
    private static boolean isOrderedSubsequence(List<String> needle, List<String> haystack) {
        int i = 0;
        for (String token : haystack) {
            if (i < needle.size() && needle.get(i).equals(token)) {
                i++;
            }
        }
        return i == needle.size();
    }

    private static Parsed parsed(String name, String initials) {
        return new Parsed(name, initials, key(name));
    }

    /**
     * Trimmed, with internal runs of whitespace collapsed to one space; null when there is
     * nothing left. A name that differs from another only by a double space is the same name,
     * and the UNIQUE index would happily hold both.
     */
    private static String collapse(String value) {
        if (value == null) {
            return null;
        }
        String collapsed = WHITESPACE_RUN.matcher(value.trim()).replaceAll(" ");
        return collapsed.isEmpty() ? null : collapsed;
    }

    /**
     * {@code mickie} → {@code Mickie}, with {@link Locale#ROOT} on both halves. Only ever
     * applied to a local part we have already lower-cased, so the result is a name a reader
     * recognises rather than the mailbox's own casing.
     */
    private static String titleCase(String token) {
        if (token.isEmpty()) {
            return token;
        }
        return token.substring(0, 1).toUpperCase(Locale.ROOT) + token.substring(1).toLowerCase(Locale.ROOT);
    }
}
