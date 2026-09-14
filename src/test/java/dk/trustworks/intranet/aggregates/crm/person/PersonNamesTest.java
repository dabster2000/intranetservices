package dk.trustworks.intranet.aggregates.crm.person;

import dk.trustworks.intranet.aggregates.crm.person.PersonNames.Parsed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The name rules behind the person registry (spec §4.3).
 *
 * <p>Every example here is a production row read on 2026-09-14, not an invention: the two
 * parenthesised mailbox formats are 13 and 109 attendee rows respectively, the address-as-a-
 * display-name shape is 22 more, and "Sif S. Broby Madsen" against "Sif Broby Madsen" is
 * defect D8 — the same human drawn as two people because a calendar and TrustLink spell her
 * differently.
 *
 * <p>Two ways the key can be wrong, and they fail in opposite directions. Too strict and one
 * person becomes several rows, each with its own claims and its own star, and the tab answers
 * "who has the warmest path" from a third of the evidence. Too loose and two humans merge
 * into one row with nothing on the page saying it happened, which is why the token rules are
 * whole-token and why {@link #anAttendeeWhoseNameMerelyContainsAColleaguesNameIsNotThem()}
 * exists.
 *
 * <p>Fast tier — no Quarkus boot, no database.
 */
class PersonNamesTest {

    // ------------------------------------------------------------------------
    // The five worked examples of spec §4.3
    // ------------------------------------------------------------------------

    /** Novo Nordisk's Exchange writes the mailbox alias first, the person in brackets. */
    @Test
    void theAliasFirstFormatYieldsTheNameAndTheShorthand() {
        Parsed parsed = PersonNames.parse("STMJ (Stephan Mosko Jensen)", "stmj@novonordisk.com");

        assertEquals("Stephan Mosko Jensen", parsed.name());
        assertEquals("STMJ", parsed.initials(), "the shorthand is how a colleague hears them referred to on site");
        assertEquals("stephan|jensen", parsed.key());
    }

    /** Banedanmark and KDS write the person first, their shorthand in brackets. */
    @Test
    void theAliasLastFormatYieldsTheNameAndTheShorthand() {
        Parsed parsed = PersonNames.parse("Sara Louise Vest (XSVES)", "xsves@bane.dk");

        assertEquals("Sara Louise Vest", parsed.name());
        assertEquals("XSVES", parsed.initials());
        assertEquals("sara|vest", parsed.key());
    }

    /**
     * A display name that is only the address, with a local part that is plainly a person.
     * Arba Security's 77 meetings hang off this one attendee.
     */
    @Test
    void anAddressWhoseLocalPartIsANameBecomesThatName() {
        Parsed parsed = PersonNames.parse("Mickie.Storm@ArbaSecurity.com", "Mickie.Storm@ArbaSecurity.com");

        assertEquals("Mickie Storm", parsed.name());
        assertNull(parsed.initials(), "an address carries no shorthand");
        assertEquals("mickie|storm", parsed.key());
    }

    /**
     * A mailbox alias is not a name and must not be turned into one — inventing "Xhvig" would
     * put a human on the account page who does not exist.
     */
    @Test
    void anAddressWhoseLocalPartIsNotANameStaysTheAddress() {
        Parsed parsed = PersonNames.parse(null, "xhvig@bane.dk");

        assertEquals("xhvig@bane.dk", parsed.name());
        assertNull(parsed.initials());
    }

    /**
     * The other half of the same rule, and the one that merges strangers if it is missing.
     *
     * <p>When the local part is not plainly a person, {@code parse} hands the raw address back
     * as the name — and first-token/last-token over an ADDRESS ends on the top-level domain,
     * which every mailbox at the client shares. {@code ext|dk} would be the key of every
     * external at Dagrofa: two different humans on one row, carrying one another's meetings
     * and one another's claims, with nothing on the page saying so. A mailbox is its own key.
     */
    @Test
    void anAddressThatIsNotANameKeysToTheWholeAddressAndNotToItsDomain() {
        assertEquals("xhvig@bane.dk", PersonNames.parse(null, "xhvig@bane.dk").key());
        assertEquals("ext.anne.hansen@dagrofa.dk", PersonNames.key("ext.anne.hansen@dagrofa.dk"));
        assertFalse(PersonNames.key("ext.anne.hansen@dagrofa.dk")
                        .equals(PersonNames.key("ext.lars.nielsen@dagrofa.dk")),
                "two externals at one client are two people, however alike their mailboxes look");
        assertEquals("xhvig@bane.dk", PersonNames.key("  XHVIG@Bane.DK  "),
                "still folded with Locale.ROOT, so two casings of one mailbox stay one person");
    }

    /** A name that is already a name is left exactly as it is. */
    @Test
    void aPlainNameIsLeftAlone() {
        Parsed parsed = PersonNames.parse("Sif S. Broby Madsen", "sif.broby.madsen@example.dk");

        assertEquals("Sif S. Broby Madsen", parsed.name());
        assertNull(parsed.initials());
        assertEquals("sif|madsen", parsed.key());
    }

    // ------------------------------------------------------------------------
    // The merge key
    // ------------------------------------------------------------------------

    /** Defect D8: the calendar spelling and the TrustLink spelling are one person. */
    @Test
    void twoSpellingsOfOnePersonShareAKey() {
        assertEquals(PersonNames.key("Sif Broby Madsen"), PersonNames.key("Sif S. Broby Madsen"));
        assertEquals("sif|madsen", PersonNames.key("Sif Broby Madsen"));
    }

    /** The middle is what differs between sources, so the middle is what the key drops. */
    @Test
    void theKeyIgnoresMiddleNamesAndInitialsButNotTheEnds() {
        assertEquals("stephan|jensen", PersonNames.key("Stephan Mosko Jensen"));
        assertEquals("stephan|jensen", PersonNames.key("Stephan Jensen"));
        assertFalse(PersonNames.key("Stephan Jensen").equals(PersonNames.key("Stephan Mosko")),
                "a different surname is a different person");
    }

    @Test
    void aSingleTokenNameKeysToItself() {
        assertEquals("madonna", PersonNames.key("Madonna"));
    }

    @Test
    void aNullNameKeysToTheEmptyStringSoTheNotNullColumnAlwaysHasSomething() {
        assertEquals("", PersonNames.key(null));
    }

    @Test
    void parseReturnsNullWhenNeitherInputCarriesAnything() {
        assertNull(PersonNames.parse(null, null));
        assertNull(PersonNames.parse("   ", null), "a blank display name is no name at all");
    }

    /** One trailing space must not become a second person behind the UNIQUE index. */
    @Test
    void whitespaceIsTrimmedAndCollapsed() {
        assertEquals("Sara Louise Vest", PersonNames.parse("  Sara   Louise  Vest  ", null).name());
    }

    /** Two casings of one mailbox are one person, not two. */
    @Test
    void anAddressUsedAsANameIsLowerCased() {
        assertEquals("xhvig@bane.dk", PersonNames.parse("XHVIG@Bane.DK", null).name());
    }

    /** Real shorthands carry digits and hyphens: KDS issues {@code KEFM-KDS}. */
    @Test
    void aShorthandMayCarryDigitsAndHyphens() {
        Parsed parsed = PersonNames.parse("Sebastian Bennett Frandsen (KEFM-KDS)", "kefm@kds.dk");

        assertEquals("Sebastian Bennett Frandsen", parsed.name());
        assertEquals("KEFM-KDS", parsed.initials());
    }

    // ------------------------------------------------------------------------
    // The tokeniser
    // ------------------------------------------------------------------------

    /**
     * Danish letters and hyphens are part of names, not separators between them. Splitting on
     * {@code [A-Za-z]+} would shred "Bjørn" into "bj" and "rn" and match almost nothing.
     */
    @Test
    void danishLettersAndHyphensSurviveAsTokens() {
        assertEquals(List.of("ellen", "marie", "bjørnholt"), PersonNames.tokens("Ellen-Marie Bjørnholt"));
        assertEquals("bjørn|østergård", PersonNames.key("Bjørn Østergård"));
    }

    @Test
    void bracketsCommasAndAtSignsAreAllSeparators() {
        assertEquals(List.of("qnte", "nicolas", "de", "teilmann"),
                PersonNames.tokens("QNTE (Nicolas De Teilmann)"));
        assertEquals(List.of("mygx", "novonordisk", "com"), PersonNames.tokens("mygx@novonordisk.com"));
    }

    @Test
    void aNullNameTokenisesToNothingRatherThanThrowing() {
        assertTrue(PersonNames.tokens(null).isEmpty());
    }

    /**
     * The default locale is not ours to choose — surefire reuses one JVM across test classes
     * and a machine may boot anywhere. Under a Turkish locale {@code "I".toLowerCase()} is a
     * dotless {@code ı}, which would silently split one person into two.
     */
    @Test
    void caseFoldingDoesNotFollowTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            assertEquals("isabella|iversen", PersonNames.key("ISABELLA IVERSEN"));
            assertEquals("Mickie Storm", PersonNames.parse("MICKIE.STORM@ARBASECURITY.COM", null).name());
        } finally {
            Locale.setDefault(original);
        }
    }

    // ------------------------------------------------------------------------
    // containsSequence — the colleague name rule, both directions
    // ------------------------------------------------------------------------

    /**
     * The call the colleague filter makes: the attendee's tokens are the haystack, the
     * employee's are the needle.
     */
    @Test
    void anEmployeesNameInsideAnAttendeesNameMatches() {
        assertTrue(PersonNames.containsSequence(
                PersonNames.tokens("QNTE (Nicolas De Teilmann)"),
                PersonNames.tokens("Nicolas de Teilmann")));
    }

    /**
     * The bug a substring match would have shipped. "Anne Hansen" sits inside "Marianne
     * Hansen", and a filter that ate Marianne would delete a real client contact from the
     * account with no trace anywhere that it had.
     */
    @Test
    void anAttendeeWhoseNameMerelyContainsAColleaguesNameIsNotThem() {
        List<String> employee = PersonNames.tokens("Anne Hansen");

        assertFalse(PersonNames.containsSequence(PersonNames.tokens("Marianne Hansen"), employee));
        assertTrue(PersonNames.containsSequence(PersonNames.tokens("Anne Hansen"), employee),
                "the real Anne still matches");
    }

    @Test
    void theTokensHaveToBeAdjacentAndInOrder() {
        List<String> employee = PersonNames.tokens("Nicolas Teilmann");

        assertFalse(PersonNames.containsSequence(PersonNames.tokens("Nicolas Hansen, Peter Teilmann"), employee));
        assertFalse(PersonNames.containsSequence(PersonNames.tokens("Teilmann Nicolas"), employee));
    }

    /**
     * The argument order is load-bearing and swapping it compiles. The colleague filter calls
     * this as {@code containsSequence(attendeeTokens, employeeTokens)}; the other way round the
     * same two names stop matching, and our own consultants reappear as client contacts.
     */
    @Test
    void theArgumentOrderIsHaystackThenNeedle() {
        List<String> attendee = PersonNames.tokens("QNTE (Nicolas De Teilmann)");
        List<String> employee = PersonNames.tokens("Nicolas de Teilmann");

        assertTrue(PersonNames.containsSequence(attendee, employee), "attendee is the haystack");
        assertFalse(PersonNames.containsSequence(employee, attendee), "swapped, the same two names miss");
    }

    /**
     * The hole this rule cannot cover on its own, and the reason {@link PersonNames#isReductionOf}
     * exists: a needle longer than the haystack can never match, so a client that writes LESS
     * than our user row holds is invisible here.
     */
    @Test
    void anEmployeeNameLongerThanTheAttendeesNeverMatchesAsASequence() {
        assertFalse(PersonNames.containsSequence(
                PersonNames.tokens("Nichlas Madsen"),
                PersonNames.tokens("Nichlas Halberg Madsen")));
    }

    @Test
    void anEmptyNeedleNeverMatches() {
        assertFalse(PersonNames.containsSequence(PersonNames.tokens("Anne Hansen"), List.of()));
    }

    // ------------------------------------------------------------------------
    // isReductionOf — the client that writes LESS than our user row holds
    // ------------------------------------------------------------------------

    /**
     * Dagrofa issued Nichlas Halberg Madsen the mailbox {@code extnim@dagrofa.dk} and put
     * "Nichlas Madsen" on it. Ten meetings with our own consultant stayed on the Dagrofa
     * account as a client contact until this rule existed.
     */
    @Test
    void anAttendeeNameThatDropsAMiddleNameIsTheSamePerson() {
        assertTrue(PersonNames.isReductionOf(
                PersonNames.tokens("Nichlas Madsen"),
                PersonNames.tokens("Nichlas Halberg Madsen")));
    }

    /** A window rather than the whole name, so a client's mailbox prefix survives. */
    @Test
    void aClientPrefixInFrontOfTheShortenedNameStillMatches() {
        assertTrue(PersonNames.isReductionOf(
                PersonNames.tokens("NIM (Nichlas Madsen)"),
                PersonNames.tokens("Nichlas Halberg Madsen")));
    }

    /**
     * The reduction rule must not re-open the substring hole: it fails twice over here,
     * once on the three-token guard and once because no window can start on {@code anne}.
     */
    @Test
    void theReductionRuleCannotMatchMarianneAgainstAnneHansen() {
        assertFalse(PersonNames.isReductionOf(
                PersonNames.tokens("Marianne Hansen"),
                PersonNames.tokens("Anne Hansen")));
        assertFalse(PersonNames.isReductionOf(
                PersonNames.tokens("Marianne Bodil Hansen"),
                PersonNames.tokens("Anne Bodil Hansen")),
                "a middle name on both sides does not help: the window still has to start on anne");
    }

    /** A two-token employee name has no middle to drop. */
    @Test
    void anEmployeeWithFewerThanThreeTokensIsNeverReduced() {
        assertFalse(PersonNames.isReductionOf(
                PersonNames.tokens("Anne Hansen"),
                PersonNames.tokens("Anne Hansen")));
    }

    /** A one-token window is the single-name match these rules refuse everywhere else. */
    @Test
    void anAttendeeWithASingleTokenIsNeverReduced() {
        assertFalse(PersonNames.isReductionOf(
                PersonNames.tokens("Nichlas"),
                PersonNames.tokens("Nichlas Halberg Madsen")));
    }

    /** The window has to be strictly shorter — the same name in full is {@code containsSequence}'s job. */
    @Test
    void aWindowAsLongAsTheEmployeesNameIsNotAReduction() {
        assertFalse(PersonNames.isReductionOf(
                PersonNames.tokens("Nichlas Halberg Madsen"),
                PersonNames.tokens("Nichlas Halberg Madsen")));
    }

    /** The window must be anchored on both ends: sharing only the surname is not the same person. */
    @Test
    void aDifferentFirstNameIsNotAReduction() {
        assertFalse(PersonNames.isReductionOf(
                PersonNames.tokens("Peter Madsen"),
                PersonNames.tokens("Nichlas Halberg Madsen")));
    }
}
