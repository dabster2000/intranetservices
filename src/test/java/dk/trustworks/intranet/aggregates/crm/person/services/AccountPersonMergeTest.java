package dk.trustworks.intranet.aggregates.crm.person.services;

import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonIdentityKind;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonSource;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.Identity;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.PersonDraft;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.Sighting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The identity merge, the name we show and the {@code sources} column — spec §3.1 and §4.3,
 * the rules that turn several sightings of one human into one {@code account_person} row.
 *
 * <p>The defect these exist for is D8. On production only 6 of the 183 calendar display names
 * equal a TrustLink {@code full_name} at the same client character for character, so keying
 * people on a display name drew {@code "Sif S. Broby Madsen"} and {@code "Sif Broby Madsen"}
 * as two different people on the same account page, each with half the evidence.
 *
 * <p>Fast tier — no Quarkus boot, no database. {@code merge}, {@code sighting} and
 * {@code preferredNaming} are pure statics precisely so the whole rule set is held by the
 * DB-free run that gates every deploy.
 */
class AccountPersonMergeTest {

    private static final String CLIENT_MAILBOX = "sif.broby@dsb.dk";

    // ------------------------------------------------------------------------
    // D8 — one human, one row
    // ------------------------------------------------------------------------

    /**
     * The production shape this whole table was built for: a calendar that writes a middle
     * initial and a TrustLink row that does not. The name key drops the middle, which is what
     * makes them the same person.
     */
    @Test
    @DisplayName("A calendar spelling and a TrustLink spelling of one person are one person")
    void aCalendarSpellingAndATrustLinkSpellingAreOnePerson() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                calendar("Sif S. Broby Madsen", CLIENT_MAILBOX),
                trustLink("Sif Broby Madsen", "tl-4711", "Head of Procurement", "https://example.test/in/sif")));

        assertEquals(1, drafts.size(), "two spellings of one person must not be two rows on the account page");
        PersonDraft person = drafts.get(0);
        assertEquals("Sif Broby Madsen", person.name(),
                "TrustLink carries the person's own spelling, so it wins over the client's mailbox");
        assertEquals("sif|madsen", person.nameKey());
        assertEquals("CALENDAR,TRUSTLINK", person.sources());
        assertEquals("Head of Procurement", person.title());
        assertEquals("https://example.test/in/sif", person.linkedinUrl());
        assertTrue(person.identities().contains(new Identity(AccountPersonIdentityKind.EMAIL, CLIENT_MAILBOX)));
        assertTrue(person.identities().contains(new Identity(AccountPersonIdentityKind.TRUSTLINK, "tl-4711")));
        assertTrue(person.identities().contains(new Identity(AccountPersonIdentityKind.NAME, "sif|madsen")));
    }

    /** One person, two mailboxes — the client issued a second address and kept the first. */
    @Test
    @DisplayName("Two addresses with one name key are one person with two identities")
    void twoAddressesWithOneNameKeyAreOnePerson() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                calendar("Mette Brandt", "mette.brandt@dsb.dk"),
                calendar("Mette Brandt", "mbra@dsb.dk")));

        assertEquals(1, drafts.size());
        assertEquals(3, drafts.get(0).identities().size(), "both mailboxes plus the name key");
    }

    /** TrustLink lists 26 people under more than one company; the name key is what rejoins them. */
    @Test
    @DisplayName("Two TrustLink ids with one name key are one person")
    void twoTrustLinkIdsWithOneNameKeyAreOnePerson() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                trustLink("Ole Brandt", "tl-a", "CIO", null),
                trustLink("Ole Brandt", "tl-b", null, null)));

        assertEquals(1, drafts.size());
        assertEquals("CIO", drafts.get(0).title(), "the row that carried a title is still the same person");
    }

    /**
     * The transitive case a pairwise comparison misses. Two spellings that share NO name key
     * are still one person when one address carried both — which is why the merge is a graph
     * and not a comparison.
     */
    @Test
    @DisplayName("An address bridges two spellings that share no name key")
    void anAddressBridgesTwoSpellingsThatShareNoNameKey() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                calendar("Kim Holm", "kim@dsb.dk"),
                calendar("Kim Holm-Jensen", "kim@dsb.dk")));

        assertEquals(1, drafts.size(), "one mailbox is one human however the invitation spelled them");
        PersonDraft person = drafts.get(0);
        assertEquals("Kim Holm-Jensen", person.name(), "the longer calendar spelling is the more complete one");
        assertEquals("kim|jensen", person.nameKey());
        assertEquals(3, person.identities().size(), "the address and BOTH name keys, so a later sighting of either merges");
    }

    /**
     * The merge must be conservative in the other direction too. Nothing links these two, and
     * a rule that joined them on a surname or a shared domain would put one person's claim on
     * another person's row.
     */
    @Test
    @DisplayName("Two people who share no identity stay two people")
    void twoPeopleWhoShareNoIdentityStayTwoPeople() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                calendar("Mette Brandt", "mette.brandt@dsb.dk"),
                calendar("Ole Brandt", "ole.brandt@dsb.dk")));

        assertEquals(2, drafts.size());
    }

    // ------------------------------------------------------------------------
    // The name we show (spec §4.3)
    // ------------------------------------------------------------------------

    /**
     * {@code "Sara Louise Vest (XSVES)"} against {@code "Sara Vest"} on the same mailbox. The
     * longer name wins, and the client's shorthand is kept: it is how a colleague will hear
     * her referred to on site.
     */
    @Test
    @DisplayName("The longest calendar spelling is the one we show, and the shorthand is kept")
    void theLongestCalendarSpellingIsTheOneWeShow() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                calendar("Sara Vest", "xsves@bane.dk"),
                calendar("Sara Louise Vest (XSVES)", "xsves@bane.dk")));

        assertEquals(1, drafts.size());
        assertEquals("Sara Louise Vest", drafts.get(0).name());
        assertEquals("XSVES", drafts.get(0).initials());
    }

    /** TrustLink outranks the calendar even when the calendar's spelling is longer. */
    @Test
    @DisplayName("TrustLink's spelling beats a longer calendar one")
    void trustLinkBeatsALongerCalendarSpelling() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                calendar("Nicolas De Teilmann (QNTE)", "qnte@bane.dk"),
                trustLink("Nicolas Teilmann", "tl-9", null, null)));

        assertEquals(1, drafts.size());
        assertEquals("Nicolas Teilmann", drafts.get(0).name());
        assertEquals("QNTE", drafts.get(0).initials(),
                "the shorthand survives even when the name came from a source that has none");
    }

    /** A person only a signal ever named still gets a row — the signal is the only source left. */
    @Test
    @DisplayName("A signal names somebody no other source saw")
    void aSignalNamesSomebodyNoOtherSourceSaw() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                signal("Dorthe Krag", "Indkøbschef")));

        assertEquals(1, drafts.size());
        assertEquals("Dorthe Krag", drafts.get(0).name());
        assertEquals("SIGNAL", drafts.get(0).sources());
        assertEquals("Indkøbschef", drafts.get(0).title());
    }

    /** TrustLink's own title beats what a colleague typed into a capture. */
    @Test
    @DisplayName("A job title prefers TrustLink over a signal")
    void aJobTitlePrefersTrustLinkOverASignal() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                signal("Dorthe Krag", "Indkøbschef"),
                trustLink("Dorthe Krag", "tl-3", "Head of Procurement", null)));

        assertEquals(1, drafts.size());
        assertEquals("Head of Procurement", drafts.get(0).title());
    }

    // ------------------------------------------------------------------------
    // Addresses and empty sightings
    // ------------------------------------------------------------------------

    /**
     * Graph hands the same mailbox back in two casings on two events. Two casings must not
     * become two people, so the address is folded with {@link java.util.Locale#ROOT} before it
     * is ever used as an identity.
     */
    @Test
    @DisplayName("Two casings of one address are one person")
    void twoCasingsOfOneAddressAreOnePerson() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                AccountPersonService.sighting(AccountPersonSource.CALENDAR, null,
                        "Mickie.Storm@ArbaSecurity.com", null, null, null),
                AccountPersonService.sighting(AccountPersonSource.CALENDAR, null,
                        "mickie.storm@arbasecurity.com", null, null, null)));

        assertEquals(1, drafts.size());
        assertEquals("Mickie Storm", drafts.get(0).name(),
                "a local part that is plainly two names reads as a name, not as a mailbox alias");
    }

    /**
     * Two externals at one client whose mailboxes look alike are still two people. Keying the
     * ADDRESS on first-token/last-token ends on the top-level domain — {@code ext|dk} for
     * everybody at Dagrofa — and merges strangers onto one row with one another's meetings and
     * one another's claims, which nothing on the page would ever show.
     */
    @Test
    @DisplayName("Two mailbox aliases on one client domain are two people")
    void twoMailboxAliasesOnOneClientDomainAreTwoPeople() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                AccountPersonService.sighting(AccountPersonSource.CALENDAR, null,
                        "ext.anne.hansen@dagrofa.dk", null, null, null),
                AccountPersonService.sighting(AccountPersonSource.CALENDAR, null,
                        "ext.lars.nielsen@dagrofa.dk", null, null, null)));

        assertEquals(2, drafts.size(), "a shared domain is not a shared identity");
        assertEquals("ext.anne.hansen@dagrofa.dk", drafts.get(0).nameKey());
    }

    /**
     * Defect D5, in the shape production produces it. {@code display_name} is nullable and the
     * calendar read is {@code distinct (email, display_name)}, so one mailbox yields two
     * sightings — one named, one bare — that join on the same {@code EMAIL} identity. The
     * address is longer than the name; longest-wins alone therefore labels the person with
     * their own mailbox on the tab built to name them.
     */
    @Test
    @DisplayName("A real name beats the bare address on the same mailbox, however long the address")
    void aRealNameBeatsTheBareAddressOnTheSameMailbox() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                calendar(null, "xhvig@bane.dk"),
                calendar("Hanne Vig", "xhvig@bane.dk")));

        assertEquals(1, drafts.size(), "one mailbox is one human");
        assertEquals("Hanne Vig", drafts.get(0).name(),
                "'xhvig@bane.dk' is 13 characters and 'Hanne Vig' is 9 — an address is never the better name");
        assertEquals("hanne|vig", drafts.get(0).nameKey());
    }

    /** A value with no {@code @} is not an address and must not be stored as one. */
    @Test
    @DisplayName("Only a real address becomes an EMAIL identity")
    void onlyARealAddressBecomesAnEmailIdentity() {
        assertNull(AccountPersonService.normaliseEmail("not-an-address"));
        assertNull(AccountPersonService.normaliseEmail("   "));
        assertEquals("xhvig@bane.dk", AccountPersonService.normaliseEmail("  XHVIG@Bane.DK "));
    }

    /**
     * A source that handed us neither a name nor an address has not seen anybody. Making a row
     * out of that would put a nameless person on the account page that no reader could act on
     * and no later rebuild could merge away.
     */
    @Test
    @DisplayName("A sighting with nothing in it is not a person")
    void aSightingWithNothingInItIsNotAPerson() {
        assertNull(AccountPersonService.sighting(AccountPersonSource.SIGNAL, null, null, null, null, null));
        assertNull(AccountPersonService.sighting(AccountPersonSource.SIGNAL, "  ", "", null, null, null));

        List<Sighting> withHoles = new ArrayList<>();
        withHoles.add(null);
        withHoles.add(calendar("Mette Brandt", "mette.brandt@dsb.dk"));
        withHoles.add(null);
        assertEquals(1, AccountPersonService.merge(withHoles).size(), "a null in the list is skipped, not thrown on");
        assertEquals(0, AccountPersonService.merge(null).size());
    }

    // ------------------------------------------------------------------------
    // The sources column
    // ------------------------------------------------------------------------

    /** Provenance, recomputed from scratch on every rebuild — not accumulated. */
    @Test
    @DisplayName("The sources column names every feed behind the row, in declaration order")
    void theSourcesColumnNamesEveryFeedBehindTheRow() {
        List<PersonDraft> drafts = AccountPersonService.merge(List.of(
                slack("Dorthe Krag", "Indkøbschef"),
                signal("Dorthe Krag", null),
                trustLink("Dorthe Krag", "tl-3", null, null)));

        assertEquals(1, drafts.size());
        assertEquals("TRUSTLINK,SIGNAL,SLACK", drafts.get(0).sources(),
                "declaration order, so two rebuilds that saw the same feeds write the same characters");
    }

    /** The encoding is this column's business and nobody else's. */
    @Test
    @DisplayName("Sources round-trip through the column, and an unknown member is ignored")
    void sourcesRoundTripThroughTheColumn() {
        assertEquals("CALENDAR,SLACK",
                AccountPersonSource.join(List.of(AccountPersonSource.SLACK, AccountPersonSource.CALENDAR)));
        assertEquals("", AccountPersonSource.join(List.of()));
        assertEquals("", AccountPersonSource.join(null));

        assertEquals(List.of(AccountPersonSource.CALENDAR, AccountPersonSource.SLACK),
                AccountPersonSource.split("CALENDAR,SLACK"));
        assertEquals(List.of(AccountPersonSource.CALENDAR),
                AccountPersonSource.split(" calendar , , CALENDAR "),
                "case is folded with Locale.ROOT and a repeat is not a second member");
        assertEquals(List.of(AccountPersonSource.CALENDAR),
                AccountPersonSource.split("CALENDAR,TELEPATHY"),
                "a source a newer deployment wrote must not take a nightly rebuild down");
        assertEquals(List.of(), AccountPersonSource.split(null));
    }

    // ------------------------------------------------------------------------
    // Failure codes
    // ------------------------------------------------------------------------

    /**
     * {@code failure_code} is a {@code VARCHAR(40)} anybody can read. A duplicate-key message
     * quotes the values it rejected, and the values here are a person's name and address, so
     * the code is a class name and nothing else.
     */
    @Test
    @DisplayName("A failure code is a class name, never a message")
    void aFailureCodeIsAClassNameNeverAMessage() {
        String code = AccountPersonService.failureCodeOf(
                new IllegalStateException("Duplicate entry 'sif.broby@dsb.dk' for key 'uq_account_person_identity'"));
        assertEquals("IllegalStateException", code);
        assertTrue(code.length() <= AccountPersonService.MAX_FAILURE_CODE_CHARS);
        assertEquals(AccountPersonService.FAILURE_UNEXPECTED, AccountPersonService.failureCodeOf(null));
    }

    /** Blank is null, and a value longer than its column is cut rather than rejected. */
    @Test
    @DisplayName("Values are trimmed to their columns")
    void valuesAreTrimmedToTheirColumns() {
        assertNull(AccountPersonService.trimTo("   ", 10));
        assertNull(AccountPersonService.trimTo(null, 10));
        assertEquals("abc", AccountPersonService.trimTo("  abc  ", 10));
        assertEquals("abcde", AccountPersonService.trimTo("abcdefgh", 5));
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static Sighting calendar(String displayName, String email) {
        Sighting sighting = AccountPersonService.sighting(
                AccountPersonSource.CALENDAR, displayName, email, null, null, null);
        assertNotNull(sighting, "the fixture must produce a sighting");
        return sighting;
    }

    private static Sighting trustLink(String fullName, String personId, String position, String linkedinUrl) {
        Sighting sighting = AccountPersonService.sighting(
                AccountPersonSource.TRUSTLINK, fullName, null, personId, position, linkedinUrl);
        assertNotNull(sighting, "the fixture must produce a sighting");
        return sighting;
    }

    private static Sighting signal(String personName, String personRole) {
        Sighting sighting = AccountPersonService.sighting(
                AccountPersonSource.SIGNAL, personName, null, null, personRole, null);
        assertNotNull(sighting, "the fixture must produce a sighting");
        return sighting;
    }

    private static Sighting slack(String personName, String role) {
        Sighting sighting = AccountPersonService.sighting(
                AccountPersonSource.SLACK, personName, null, null, role, null);
        assertNotNull(sighting, "the fixture must produce a sighting");
        return sighting;
    }
}
