package dk.trustworks.intranet.aggregates.crm.trustlink.services;

import dk.trustworks.intranet.aggregates.crm.trustlink.model.enums.MatchMethod;
import dk.trustworks.intranet.aggregates.crm.trustlink.services.TrustLinkTrustworkerMatcher.Match;
import dk.trustworks.intranet.aggregates.crm.trustlink.services.TrustLinkTrustworkerMatcher.UserRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ladder, locked against the real cases it was measured on.
 *
 * <p>The roster below is the production situation in miniature: colleagues whose Intra
 * name carries a middle name TrustLink never had, two names with the stray double space
 * that payroll produced, and — the reason every rung insists on a unique hit — four
 * different Maries. Every test here is either a name that must resolve or a name that must
 * be left alone; there is no case in between, because the feature tells an account team
 * whom to call.
 */
class TrustLinkTrustworkerMatcherTest {

    private static final String HANS = "u-hans";
    private static final String DITTE = "u-ditte";
    private static final String MICHELLE = "u-michelle";
    private static final String SANDRA = "u-sandra";
    private static final String TANJA = "u-tanja";
    private static final String MARIE_DORTHEA = "u-marie-dorthea";
    private static final String ANDRE = "u-andre";

    private final List<UserRef> users = List.of(
            new UserRef(HANS, "Hans Ernst Lassen", "hans.lassen@trustworks.dk"),
            new UserRef(DITTE, "Ditte Marie Hjorth", "ditte.hjorth@trustworks.dk"),
            new UserRef(MICHELLE, "Michelle Cantor", null),
            new UserRef(SANDRA, "Sandra Holm Andersen", ""),
            new UserRef(TANJA, "Tanja  Bøggild Kaufmann", null),
            new UserRef(MARIE_DORTHEA, "Marie Dorthea Sørensen", null),
            new UserRef(ANDRE, "André  Engsbye Rasmussen", null),
            new UserRef("u-marie-daugaard", "Marie Daugaard", null),
            new UserRef("u-marie-myssing", "Marie Myssing", null),
            new UserRef("u-ida-marie", "Ida Marie Iversen", null));

    @Test
    void anEmailIdentifiesTheColleagueBeforeAnyNameIsCompared() {
        Match match = match("Hans Lassen", "Hans.Lassen@trustworks.dk");

        assertEquals(HANS, match.userUuid());
        assertEquals(MatchMethod.EMAIL, match.method(),
                "the e-mail is evidence; the shortened name below it is only inference");
    }

    @Test
    void anEmailThatBelongsToNobodyFallsThroughToTheNameRungs() {
        Match match = match("Ditte Hjorth", "ditte@some-old-address.example");

        assertEquals(DITTE, match.userUuid());
        assertEquals(MatchMethod.FIRST_LAST, match.method());
    }

    @Test
    void theFullNameRungSeesPastDanishLettersAndPayrollsDoubleSpaces() {
        assertEquals(new Match(TANJA, MatchMethod.FULLNAME), match("Tanja Bøggild Kaufmann", null));
        assertEquals(new Match(TANJA, MatchMethod.FULLNAME), match("Tanja Boeggild Kaufmann", null));
        assertEquals(new Match(ANDRE, MatchMethod.FULLNAME), match("Andre Engsbye Rasmussen", null));
    }

    @Test
    void aDroppedMiddleNameResolvesOnTheFirstAndLastToken() {
        assertEquals(new Match(DITTE, MatchMethod.FIRST_LAST), match("Ditte Hjorth", null));
        assertEquals(new Match(SANDRA, MatchMethod.FIRST_LAST), match("Sandra Andersen", null));
        assertEquals(new Match(TANJA, MatchMethod.FIRST_LAST), match("Tanja Kaufmann", null));
        assertEquals(new Match(MICHELLE, MatchMethod.FIRST_LAST), match("Michelle R. Cantor", null),
                "the punctuated initial is a token TrustLink has and Intra does not");
    }

    @Test
    void aDroppedSurnameResolvesOnThePrefixRung() {
        assertEquals(new Match(MARIE_DORTHEA, MatchMethod.PREFIX), match("Marie Dorthea", null));
        assertEquals(new Match(ANDRE, MatchMethod.PREFIX), match("André Engsbye", null));
    }

    @Test
    void marieDortheaIsNoneOfTheOtherMaries() {
        assertTrue(users.stream().filter(u -> u.fullname().contains("Marie")).count() >= 4,
                "the decoys have to be in the roster for this test to mean anything");

        assertEquals(MARIE_DORTHEA, match("Marie Dorthea", null).userUuid());
        assertFalse(match("Marie", null).isMatched());
    }

    @Test
    void twoColleaguesSharingAFirstAndLastNameYieldNoMatchAtAll() {
        List<UserRef> roster = withExtra(
                new UserRef("u-anne-sofie", "Anne Sofie Hansen", null),
                new UserRef("u-anne-marie", "Anne Marie Hansen", null));

        Match match = TrustLinkTrustworkerMatcher.match("Anne Hansen", null, roster, Map.of());

        assertNull(match.userUuid(), "picking one of two Anne Hansens would be a coin flip");
        assertNull(match.method());
    }

    @Test
    void anAmbiguousPrefixYieldsNoMatchAtAll() {
        List<UserRef> roster = withExtra(
                new UserRef("u-lars-holm", "Lars Peter Holm", null),
                new UserRef("u-lars-vig", "Lars Peter Vig", null));

        assertFalse(TrustLinkTrustworkerMatcher.match("Lars Peter", null, roster, Map.of()).isMatched());
    }

    @Test
    void anAmbiguousRungFallsThroughToTheStricterRungBelowIt() {
        List<UserRef> roster = withExtra(
                new UserRef("u-sofie-anne", "Sofie Anne Berg", null),
                new UserRef("u-sofie-marie", "Sofie Marie Berg", null),
                new UserRef("u-sofie-larsen", "Sofie Berg Larsen", null));

        Match match = TrustLinkTrustworkerMatcher.match("Sofie Berg", null, roster, Map.of());

        assertEquals("u-sofie-larsen", match.userUuid(),
                "two people are first Sofie and last Berg, but only one is literally Sofie Berg something");
        assertEquals(MatchMethod.PREFIX, match.method());
    }

    @Test
    void anOutOfOrderTokenIsNotAPrefix() {
        assertFalse(match("Marie Iversen", null).isMatched(),
                "Ida Marie Iversen contains both tokens but does not begin with them");
    }

    @Test
    void aSingleTokenNameIsNeverGuessedFromTheHeuristicRungs() {
        List<UserRef> onlyOneMichelle = List.of(new UserRef(MICHELLE, "Michelle Cantor", null));

        assertFalse(TrustLinkTrustworkerMatcher.match("Michelle", null, onlyOneMichelle, Map.of()).isMatched(),
                "being the only Michelle is not the same as being the Michelle TrustLink meant");
    }

    @Test
    void aNameThatFitsNobodyIsLeftUnmatchedRatherThanGuessed() {
        Match match = match("Peter Faber", null);

        assertFalse(match.isMatched());
        assertNull(match.method(), "no rung fired, so there is no rung to record");
    }

    @Test
    void aColleagueListedTwiceIsOneCandidateAndNotAnAmbiguity() {
        List<UserRef> roster = withExtra(new UserRef(DITTE, "Ditte Marie Hjorth", "ditte.hjorth@trustworks.dk"));

        assertEquals(DITTE, TrustLinkTrustworkerMatcher.match("Ditte Hjorth", null, roster, Map.of()).userUuid());
    }

    @Test
    void aManualOverrideBeatsEveryRungIncludingTheEmail() {
        Map<String, String> overrides = Map.of("hans lassen", "u-somebody-else");

        Match match = TrustLinkTrustworkerMatcher.match("Hans Lassen", "hans.lassen@trustworks.dk", users, overrides);

        assertEquals(new Match("u-somebody-else", MatchMethod.MANUAL), match);
    }

    @Test
    void aManualOverrideIsLookedUpOnTheNormalisedName() {
        Map<String, String> overrides = Map.of("marie dorthea", "u-corrected");

        assertEquals("u-corrected", TrustLinkTrustworkerMatcher.match("  Marie   Dorthea ", null, users, overrides).userUuid());
    }

    @Test
    void anOverrideMappingToNothingMeansNeverMatchThisName() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("marie dorthea", null);

        assertEquals(MARIE_DORTHEA, match("Marie Dorthea", null).userUuid(),
                "without the override the prefix rung would resolve this name");

        Match silenced = TrustLinkTrustworkerMatcher.match("Marie Dorthea", null, users, overrides);
        assertFalse(silenced.isMatched());
        assertEquals(MatchMethod.MANUAL, silenced.method(),
                "a deliberate non-match must be distinguishable from a ladder that ran out");
    }

    @Test
    void anEmptyRosterOrAnEmptyNameResolvesToNothingWithoutBlowingUp() {
        assertFalse(TrustLinkTrustworkerMatcher.match("Hans Lassen", null, List.of(), Map.of()).isMatched());
        assertFalse(TrustLinkTrustworkerMatcher.match("Hans Lassen", null, null, null).isMatched());
        assertFalse(match(null, null).isMatched());
        assertFalse(match("   ", "   ").isMatched());
        assertFalse(match("", null).isMatched());
    }

    @Test
    void aUserRowWithoutAUuidIsNeverReturned() {
        List<UserRef> roster = List.of(new UserRef(null, "Hans Ernst Lassen", "hans.lassen@trustworks.dk"));

        assertFalse(TrustLinkTrustworkerMatcher.match("Hans Lassen", "hans.lassen@trustworks.dk", roster, Map.of())
                .isMatched());
    }

    private Match match(String name, String email) {
        return TrustLinkTrustworkerMatcher.match(name, email, users, Map.of());
    }

    private List<UserRef> withExtra(UserRef... extra) {
        List<UserRef> roster = new ArrayList<>(users);
        roster.addAll(List.of(extra));
        return roster;
    }
}
