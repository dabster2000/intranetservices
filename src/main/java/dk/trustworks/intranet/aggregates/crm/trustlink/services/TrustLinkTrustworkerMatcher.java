package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import dk.trustworks.intranet.aggregates.crm.trustlink.model.enums.MatchMethod;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolves a TrustLink trustworker name to the Intra {@code User} it means — or to nothing.
 *
 * <p>TrustLink keeps its own list of the 61 Trustworks people who have registered
 * connections, typed by hand over years, with an e-mail address on only 40 of them. Intra
 * knows 246 users by payroll name. Nothing links the two lists but the text, and the whole
 * point of the feature — "who at Trustworks knows somebody at this client" — collapses the
 * moment a name resolves to the wrong colleague: the account team would call a person who
 * has never met the customer. So the rule here is not "find the most likely user"; it is
 * <b>be certain or say nothing</b>.
 *
 * <h2>The ladder</h2>
 * Four rungs, tried in order, each one requiring a single surviving candidate. Measured
 * against the real 61 trustworkers and the real 246 users on 2026-09-13 they resolve all
 * 61: 38 by e-mail, 17 by full name, 4 by first+last, 2 by prefix.
 * <ol>
 *   <li>{@link MatchMethod#EMAIL} — TrustLink's e-mail equals the user's, ignoring case.
 *       The only rung that is evidence rather than inference; everything below it is a
 *       guess we have decided is safe.</li>
 *   <li>{@link MatchMethod#FULLNAME} — the normalised names are equal. Catches
 *       {@code "Tanja  Bøggild Kaufmann"} vs {@code "Tanja Bøggild Kaufmann"} and every
 *       diacritic and double-space difference, since
 *       {@link TrustLinkNameNormalizer} has already flattened those.</li>
 *   <li>{@link MatchMethod#FIRST_LAST} — first token and last token both equal. This is
 *       the dropped-middle-name case: {@code "Ditte Hjorth"} → {@code "Ditte Marie Hjorth"},
 *       {@code "Sandra Andersen"} → {@code "Sandra Holm Andersen"},
 *       {@code "Michelle R. Cantor"} → {@code "Michelle Cantor"}.</li>
 *   <li>{@link MatchMethod#PREFIX} — TrustLink's tokens are a prefix of the user's tokens,
 *       in order. This is the dropped-SURNAME case, which rung 3 cannot see because the
 *       last tokens differ: {@code "Marie Dorthea"} → {@code "Marie Dorthea Sørensen"},
 *       {@code "André Engsbye"} → {@code "André Engsbye Rasmussen"}.</li>
 * </ol>
 *
 * <h2>Why "unique" is the load-bearing word</h2>
 * A rung that matches two different users produces NO match, never the first or the
 * closest. {@code "Marie Dorthea"} sits in a company that also employs Marie Daugaard,
 * Marie Myssing and Ida Marie Iversen; a rule that settled ties by any means would attach
 * 242 tier-5 connections to a colleague who has none of them. The unmatched name is not
 * lost — the sync still writes the edge with the raw name and a null user uuid, so the
 * Overview card keeps saying that somebody here knows these people, and a human can fix
 * the name once in {@code trustlink_trustworker_map}.
 *
 * <h2>Ambiguity does not stop the ladder</h2>
 * An ambiguous rung yields no candidate and the next rung is tried, because the rungs are
 * not nested: rung 4 is stricter than rung 3 in the dimension rung 3 is loose in (it
 * requires every one of TrustLink's tokens to line up, in order, from the start), so it
 * can resolve a pair that rung 3 could not tell apart. Only the bottom of the ladder means
 * "no match".
 *
 * <h2>Rungs 3 and 4 need two tokens</h2>
 * Both heuristics are refused for a single-token name. {@code "Peter"} matching the one
 * Peter in the company would be a "unique hit" by the letter of the rule and a coin flip
 * in fact; and today's TrustLink list contains no single-token names, so the rule costs
 * nothing and closes the hole for the day one appears.
 *
 * <p>Which rung fired is returned and stored in {@code match_method} so that a wrong
 * match is diagnosable afterwards without re-running anything: the two heuristic rungs are
 * the only places a wrong answer can come from, and they are 6 rows out of 61.
 *
 * <p>Pure, static, no CDI and no {@code EntityManager} — the caller loads the users. That
 * is what lets the DB-free fast tier hold the correctness core of this feature.
 */
public final class TrustLinkTrustworkerMatcher {

    private TrustLinkTrustworkerMatcher() {
    }

    /**
     * An Intra user, reduced to the three fields matching needs. The caller projects this
     * out of {@code User} so this class never touches Hibernate.
     */
    public record UserRef(String uuid, String fullname, String email) {
    }

    /**
     * The outcome. {@code userUuid == null} means unmatched; the {@code method} is still
     * set when the miss was deliberate ({@link MatchMethod#MANUAL} for an override that
     * maps a name to nothing) and null when the ladder simply ran out, so the two are
     * distinguishable in the database.
     */
    public record Match(String userUuid, MatchMethod method) {

        /** The ladder ran out: nobody, and no reason worth recording. */
        public static Match none() {
            return new Match(null, null);
        }

        public boolean isMatched() {
            return userUuid != null;
        }
    }

    /**
     * Resolves {@code trustworkerName} against {@code users}.
     *
     * @param trustworkerEmail TrustLink's e-mail for the trustworker; null or blank for
     *                         the 21 of 61 rows that have none, in which case rung 1 is
     *                         skipped rather than matched against blanks.
     * @param manualOverrides  normalised trustworker name → user uuid. Beats all four
     *                         rungs. A key present with a {@code null} value means
     *                         "deliberately unmapped — stop trying", which is how a human
     *                         silences a name that belongs to nobody here (a former
     *                         colleague, a duplicate profile) without the nightly job
     *                         re-guessing it every run. May be null.
     * @return never null; {@link Match#none()} when nothing is certain.
     */
    public static Match match(String trustworkerName,
                              String trustworkerEmail,
                              List<UserRef> users,
                              Map<String, String> manualOverrides) {
        String name = TrustLinkNameNormalizer.normalize(trustworkerName);
        if (manualOverrides != null && !name.isEmpty() && manualOverrides.containsKey(name)) {
            String overridden = manualOverrides.get(name);
            return new Match(blankToNull(overridden), MatchMethod.MANUAL);
        }
        if (users == null || users.isEmpty()) {
            return Match.none();
        }

        String email = trustworkerEmail == null ? "" : trustworkerEmail.strip();
        if (!email.isEmpty()) {
            String uuid = uniqueMatch(users, user -> user.email() != null
                    && !user.email().isBlank()
                    && user.email().strip().equalsIgnoreCase(email));
            if (uuid != null) {
                return new Match(uuid, MatchMethod.EMAIL);
            }
        }

        List<String> tokens = name.isEmpty() ? List.of() : List.of(name.split(" "));
        if (tokens.isEmpty()) {
            return Match.none();
        }

        String byFullname = uniqueMatch(users, user -> TrustLinkNameNormalizer.normalize(user.fullname()).equals(name));
        if (byFullname != null) {
            return new Match(byFullname, MatchMethod.FULLNAME);
        }
        if (tokens.size() < 2) {
            return Match.none();
        }

        String first = tokens.get(0);
        String last = tokens.get(tokens.size() - 1);
        String byFirstLast = uniqueMatch(users, user -> {
            List<String> userTokens = TrustLinkNameNormalizer.tokens(user.fullname());
            return userTokens.size() >= 2
                    && userTokens.get(0).equals(first)
                    && userTokens.get(userTokens.size() - 1).equals(last);
        });
        if (byFirstLast != null) {
            return new Match(byFirstLast, MatchMethod.FIRST_LAST);
        }

        String byPrefix = uniqueMatch(users, user -> isPrefix(tokens, TrustLinkNameNormalizer.tokens(user.fullname())));
        if (byPrefix != null) {
            return new Match(byPrefix, MatchMethod.PREFIX);
        }
        return Match.none();
    }

    /**
     * The uuid of the single user satisfying {@code rule}, or null when none or more than
     * one does. Users are collapsed by uuid first: the same person listed twice — the
     * caller's query joining, a duplicate row — is one candidate, not an ambiguity.
     */
    private static String uniqueMatch(List<UserRef> users, Predicate<UserRef> rule) {
        Set<String> hits = new LinkedHashSet<>();
        for (UserRef user : users) {
            if (user == null || user.uuid() == null || user.uuid().isBlank()) {
                continue;
            }
            if (rule.test(user)) {
                hits.add(user.uuid());
                if (hits.size() > 1) {
                    return null;
                }
            }
        }
        return hits.isEmpty() ? null : hits.iterator().next();
    }

    /**
     * True when every token of {@code shorter} equals the token at the same position in
     * {@code longer} and {@code longer} is at least as long — {@code [marie, dorthea]}
     * against {@code [marie, dorthea, soerensen]}. Position matters: an out-of-order
     * subsequence would make {@code "Marie Iversen"} a prefix of {@code "Ida Marie
     * Iversen"}, which is a different person.
     */
    private static boolean isPrefix(List<String> shorter, List<String> longer) {
        if (shorter.isEmpty() || longer.size() < shorter.size()) {
            return false;
        }
        for (int i = 0; i < shorter.size(); i++) {
            if (!shorter.get(i).equals(longer.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** An override stored as an empty string is the same instruction as a null one. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
