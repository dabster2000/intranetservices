package dk.trustworks.intranet.aggregates.crm.account.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a person at the client is called, and — the point of the whole rule — that they are
 * called it exactly once (CRM spec §3.7).
 *
 * <p>The identity of an external person is their E-MAIL ADDRESS. It was their display name
 * until this, and on production data that counted people twice. The cause is structural
 * rather than a one-off: {@code account_meeting_attendee} is keyed
 * {@code UNIQUE(meeting_uuid, email)} and every consented mailbox writes its OWN
 * {@code account_meeting} row for the same real-world event, so one meeting between two of
 * us and one person at the client stores that person twice — and Microsoft Graph does not
 * answer the two mailboxes identically. It handed {@code "MYGX (Malthe Yde Andreasen)"} to
 * one mailbox and no display name at all to the other, so the relationship graph drew two
 * external nodes for one man, "Who knows them" offered two chips for him, and the
 * twelve-person display cap spent two slots saying the same thing twice.
 *
 * <p>Plain JUnit against the pure resolver, so the DB-free tier that gates every deploy
 * holds it. Nothing about this failure is visible to a compiler or to an integration test:
 * the query runs, the page renders, one human is simply drawn as two.
 */
class AccountRelationshipExternalNameTest {

    private static final String MALTHE_MAILBOX = "mygx@novonordisk.com";
    private static final String MALTHE = "MYGX (Malthe Yde Andreasen)";
    private static final String NICOLAS_MAILBOX = "qnte@novonordisk.com";
    private static final String NICOLAS = "QNTE (Nicolas de Teilmann)";

    /** The reported case, end to end: two mailboxes, one man, one chip. */
    @Test
    @DisplayName("a named row and a bare address for the same mailbox are one person, under the real name")
    void theSameAddressNamedInOneMailboxAndNotTheOtherIsOnePerson() {
        Map<String, String> names = AccountRelationshipService.bestExternalNames(rows(
                MALTHE_MAILBOX, null,
                MALTHE_MAILBOX, MALTHE));

        assertEquals(1, names.size(), "one address is one person, whatever Graph called them");
        assertEquals(MALTHE, AccountRelationshipService.externalNameOf(MALTHE_MAILBOX, names));
    }

    /**
     * A person the account has never seen a name for keeps their address as their name.
     *
     * <p>This is a real answer and not a degraded one. politi.dk sends no display names at
     * all, so every person the firm knows at Rigspolitiet is known by address; dropping
     * them would empty that account's relationship graph entirely, and it was decided
     * explicitly that they are drawn.
     */
    @Test
    @DisplayName("an address nobody ever named keeps the address as its name")
    void anAddressWithNoNameAnywhereIsDrawnAsItsAddress() {
        Map<String, String> names = AccountRelationshipService.bestExternalNames(rows(
                MALTHE_MAILBOX, MALTHE));

        assertEquals("jens.hansen@politi.dk",
                AccountRelationshipService.externalNameOf("jens.hansen@politi.dk", names));
    }

    /**
     * The rule collapses one address, never two. Merging on anything looser — a shared
     * display name, a surname, a domain — would invent a relationship nobody has, which is
     * a far worse error than the doubled chip it is fixing.
     */
    @Test
    @DisplayName("two different addresses are never merged, not even by the same surname")
    void twoAddressesAreTwoPeople() {
        Map<String, String> names = AccountRelationshipService.bestExternalNames(rows(
                MALTHE_MAILBOX, MALTHE,
                NICOLAS_MAILBOX, NICOLAS));

        assertEquals(2, names.size());
        assertEquals(MALTHE, AccountRelationshipService.externalNameOf(MALTHE_MAILBOX, names));
        assertEquals(NICOLAS, AccountRelationshipService.externalNameOf(NICOLAS_MAILBOX, names));
    }

    /**
     * Graph echoes the address back as the display name for a mailbox it cannot resolve.
     * That is not a name — it says nothing a reader did not already have — and letting it
     * through would put it in the running for the deterministic pick below, where a
     * lexicographic accident, not a human judgement, would decide whether the account calls
     * the man "MYGX (Malthe Yde Andreasen)" or his own address back at him.
     */
    @Test
    @DisplayName("a display name that only repeats the address is not a name")
    void anEchoedAddressDoesNotBecomeTheName() {
        Map<String, String> names = AccountRelationshipService.bestExternalNames(rows(
                MALTHE_MAILBOX, MALTHE_MAILBOX,
                MALTHE_MAILBOX, "MYGX@NOVONORDISK.COM",
                MALTHE_MAILBOX, MALTHE));

        assertEquals(MALTHE, AccountRelationshipService.externalNameOf(MALTHE_MAILBOX, names));
    }

    /**
     * An address whose ONLY display name is the address echoed back still resolves — to the
     * address. It must not end up named the empty string, and it must not disappear.
     */
    @Test
    @DisplayName("an address only ever echoed back still resolves to the address")
    void anAddressOnlyEchoedBackIsStillDrawn() {
        Map<String, String> names = AccountRelationshipService.bestExternalNames(rows(
                MALTHE_MAILBOX, MALTHE_MAILBOX));

        assertTrue(names.isEmpty(), "an echo is not a name, so nothing is recorded for it");
        assertEquals(MALTHE_MAILBOX, AccountRelationshipService.externalNameOf(MALTHE_MAILBOX, names));
    }

    /**
     * Two mailboxes can carry two genuinely different spellings of one person. Which one
     * wins does not matter — nothing in the data says one rendering is better — but it has
     * to be the SAME one every time. The name is the key {@code externals} is stored under,
     * so a name that changed between page loads would rearrange the graph for no reason a
     * reader could see, and could put one person back into that map under two keys.
     *
     * <p>Lexicographically smallest, matching the {@code min(display_name)} the query uses
     * while it is already grouping. The two halves must agree or the answer would depend on
     * which path produced it.
     */
    @Test
    @DisplayName("two spellings of one person resolve to the same one every time")
    void thePickBetweenTwoRealSpellingsIsDeterministic() {
        Map<String, String> forwards = AccountRelationshipService.bestExternalNames(rows(
                MALTHE_MAILBOX, "Malthe Yde Andreasen",
                MALTHE_MAILBOX, "Andreasen, Malthe Yde"));
        Map<String, String> backwards = AccountRelationshipService.bestExternalNames(rows(
                MALTHE_MAILBOX, "Andreasen, Malthe Yde",
                MALTHE_MAILBOX, "Malthe Yde Andreasen"));

        assertEquals("Andreasen, Malthe Yde", forwards.get(MALTHE_MAILBOX));
        assertEquals(forwards, backwards, "row order must not decide what a person is called");
    }

    /**
     * The table is {@code utf8mb4_general_ci}, so the database already treats these as one
     * address inside a meeting. Java does not, and would split the person back apart on the
     * read side — the exact defect, arriving by a different door.
     */
    @Test
    @DisplayName("the address is matched case-insensitively, as the database stores it")
    void theAddressIsCaseInsensitive() {
        Map<String, String> names = AccountRelationshipService.bestExternalNames(rows(
                "MYGX@Novonordisk.com", MALTHE));

        assertEquals(1, names.size());
        assertEquals(MALTHE, AccountRelationshipService.externalNameOf(MALTHE_MAILBOX, names));
        assertEquals(MALTHE, AccountRelationshipService.externalNameOf("  MYGX@NOVONORDISK.COM ", names));
    }

    /** An unnamed address is drawn in one spelling too, or the two spellings become two chips. */
    @Test
    @DisplayName("an unnamed address is drawn lower-cased, so its two spellings are one chip")
    void anUnnamedAddressIsNormalisedBeforeItIsDrawn() {
        assertEquals("jens.hansen@politi.dk",
                AccountRelationshipService.externalNameOf("Jens.Hansen@Politi.DK", Map.of()));
    }

    /**
     * Whitespace and blanks come from a table nobody validates on write. A blank display
     * name is not a name; a blank address is not a person and has no name to give.
     */
    @Test
    @DisplayName("blank and absent values are tolerated rather than turned into names")
    void blanksAreNotNames() {
        Map<String, String> names = AccountRelationshipService.bestExternalNames(rows(
                MALTHE_MAILBOX, "   ",
                "   ", MALTHE,
                null, MALTHE,
                MALTHE_MAILBOX, null));

        assertTrue(names.isEmpty());
        assertNull(AccountRelationshipService.externalNameOf(null, names));
        assertNull(AccountRelationshipService.externalNameOf("   ", names));
    }

    /** No meetings at all, or none with a name on them, is an empty map and not a failure. */
    @Test
    @DisplayName("an account with no named attendees resolves to an empty map")
    void noRowsIsAnEmptyMap() {
        assertTrue(AccountRelationshipService.bestExternalNames(List.of()).isEmpty());
        assertTrue(AccountRelationshipService.bestExternalNames(null).isEmpty());
    }

    /**
     * The resolution is one map for the whole client, which is why it can be asked the same
     * question from anywhere. Were it built per {@code user_uuid} group — Kenn's meetings
     * resolving one way and Tobias's another — the same address would enter the name-keyed
     * externals map under two keys and the doubled chip would be straight back.
     */
    @Test
    @DisplayName("every caller asking about one address gets one answer")
    void oneAddressHasOneAnswerForTheWholeAccount() {
        Map<String, String> names = AccountRelationshipService.bestExternalNames(rows(
                MALTHE_MAILBOX, MALTHE,
                MALTHE_MAILBOX, null,
                NICOLAS_MAILBOX, null));

        // Kenn's group saw the bare address; Tobias's saw the name. Same chip either way.
        assertEquals(MALTHE, AccountRelationshipService.externalNameOf(MALTHE_MAILBOX, names));
        assertEquals(MALTHE, AccountRelationshipService.externalNameOf("MYGX@novonordisk.com", names));
        assertEquals(NICOLAS_MAILBOX, AccountRelationshipService.externalNameOf(NICOLAS_MAILBOX, names));
    }

    /** The raw table shape: (e-mail, display name) pairs, as many as you pass. */
    private static List<String[]> rows(String... emailThenName) {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < emailThenName.length; i += 2) {
            rows.add(new String[]{emailThenName[i], emailThenName[i + 1]});
        }
        return rows;
    }
}
