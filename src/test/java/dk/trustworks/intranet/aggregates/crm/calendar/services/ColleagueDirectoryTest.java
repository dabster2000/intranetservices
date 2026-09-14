package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.aggregates.crm.calendar.services.ColleagueDirectory.ColleagueRow;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The name match and the employment window behind the colleague-at-client filter
 * (decision D2).
 *
 * <p>Two ways this can be wrong, and they fail in opposite directions. Too narrow and our
 * own consultants keep appearing on the account as the firm's network into its own client.
 * Too wide — a {@code String.contains()}, say — and it silently deletes real client
 * contacts whose names happen to contain a colleague's, leaving no trace anywhere that it
 * did. The second is far worse, which is why the algorithm is whole-token and contiguous
 * and why {@link #anAttendeeWhoseNameMerelyContainsAColleaguesNameDoesNotMatch()} exists.
 *
 * <p>Fast tier — no Quarkus boot, no database.
 */
class ColleagueDirectoryTest {

    private static final String NICOLAS = "11111111-1111-1111-1111-111111111111";
    private static final String MALTHE = "22222222-2222-2222-2222-222222222222";
    private static final String SARA = "33333333-3333-3333-3333-333333333333";
    private static final String NINA = "44444444-4444-4444-4444-444444444444";
    private static final LocalDate MEETING_DAY = LocalDate.of(2026, 9, 12);
    private static final LocalDate LONG_AGO = LocalDate.of(2019, 1, 1);

    /** The client somebody is placed at, and one they are not. */
    private static final String BANE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_CLIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    // ------------------------------------------------------------------------
    // The name match
    // ------------------------------------------------------------------------

    /**
     * The real shape of the thing: Novo Nordisk's Exchange writes the mailbox alias, then
     * the person's name in brackets. Splitting on everything that is not a letter or digit
     * turns that into the same words the Intra user row holds.
     */
    @Test
    void aClientMailboxAliasWithTheNameInBracketsMatches() {
        ColleagueDirectory directory = directoryOf(
                row(NICOLAS, "Nicolas", "de Teilmann", StatusType.ACTIVE, LONG_AGO));

        assertTrue(directory.isColleagueOn("QNTE (Nicolas De Teilmann)", MEETING_DAY));
        assertEquals(NICOLAS, directory.colleagueUuidOn("QNTE (Nicolas De Teilmann)", MEETING_DAY),
                "the uuid is what teaches the learned-address table whose address it is");
    }

    @Test
    void theMatchIsCaseInsensitiveOnBothSides() {
        ColleagueDirectory directory = directoryOf(
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, LONG_AGO));

        assertTrue(directory.isColleagueOn("MALTHE YDE ANDREASEN", MEETING_DAY));
        assertTrue(directory.isColleagueOn("malthe yde andreasen", MEETING_DAY));
    }

    /**
     * Danish letters and hyphens are part of names, not separators between them. Splitting
     * on {@code [A-Za-z]+} would shred "Bjørn" into "bj" and "rn" and match almost nothing.
     */
    @Test
    void danishLettersAndHyphensSurviveAsTokens() {
        ColleagueDirectory directory = directoryOf(
                row(MALTHE, "Ellen-Marie", "Bjørnholt", StatusType.ACTIVE, LONG_AGO));

        assertEquals(List.of("ellen", "marie", "bjørnholt"),
                ColleagueDirectory.tokensOf("Ellen-Marie Bjørnholt"));
        assertTrue(directory.isColleagueOn("EMBJ (Ellen-Marie Bjørnholt)", MEETING_DAY));
    }

    /**
     * The bug a substring match would have shipped. "Anne Hansen" sits inside "Marianne
     * Hansen", and a colleague filter that ate Marianne would have deleted a real client
     * contact from the account's relationship graph.
     */
    @Test
    void anAttendeeWhoseNameMerelyContainsAColleaguesNameDoesNotMatch() {
        ColleagueDirectory directory = directoryOf(
                row(NICOLAS, "Anne", "Hansen", StatusType.ACTIVE, LONG_AGO));

        assertFalse(directory.isColleagueOn("Marianne Hansen", MEETING_DAY));
        assertTrue(directory.isColleagueOn("Anne Hansen", MEETING_DAY), "the real Anne still matches");
    }

    /**
     * Contiguous, so a display name that happens to carry both of a colleague's names in
     * different places — two people in one invitation line — is not that colleague.
     */
    @Test
    void theTokensHaveToBeAdjacentAndInOrder() {
        ColleagueDirectory directory = directoryOf(
                row(NICOLAS, "Nicolas", "Teilmann", StatusType.ACTIVE, LONG_AGO));

        assertFalse(directory.isColleagueOn("Nicolas Hansen, Peter Teilmann", MEETING_DAY));
        assertFalse(directory.isColleagueOn("Teilmann Nicolas", MEETING_DAY));
    }

    /**
     * One token is one common word. Matching on it would eventually eat a client contact
     * called Hansen, so a user carrying only one name token is never matched at all.
     */
    @Test
    void aColleagueWhoseNameIsASingleTokenIsNeverMatched() {
        ColleagueDirectory directory = directoryOf(
                row(NICOLAS, "Hansen", null, StatusType.ACTIVE, LONG_AGO));

        assertFalse(directory.isColleagueOn("Hansen", MEETING_DAY));
        assertEquals(0, directory.size(), "and they are not even carried");
    }

    @Test
    void anAttendeeWithNoDisplayNameCannotBeNameMatched() {
        ColleagueDirectory directory = directoryOf(
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, LONG_AGO));

        assertNull(directory.colleagueUuidOn(null, MEETING_DAY));
        assertNull(directory.colleagueUuidOn("   ", MEETING_DAY));
        assertNull(directory.colleagueUuidOn("mygx@novonordisk.com", MEETING_DAY),
                "which is exactly the hole crm_colleague_client_email exists to cover");
    }

    @Test
    void anEmptyDirectoryMatchesNobody() {
        assertFalse(ColleagueDirectory.empty().isColleagueOn("Malthe Yde Andreasen", MEETING_DAY));
        assertEquals(0, ColleagueDirectory.empty().size());
    }

    // ------------------------------------------------------------------------
    // Employment on the day of the meeting
    // ------------------------------------------------------------------------

    /**
     * The whole reason the status history is carried. Malthe left in January and works at
     * the client now; by September a meeting with him is a genuine client relationship and
     * one of the best the firm has.
     */
    @Test
    void somebodyWhoHadAlreadyLeftIsNotAColleagueOnThatDate() {
        ColleagueDirectory directory = directoryOf(
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, LONG_AGO),
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.TERMINATED, LocalDate.of(2026, 1, 31)));

        assertFalse(directory.isColleagueOn("Malthe Yde Andreasen", MEETING_DAY));
    }

    /** The same person, the same name, a meeting from before they left: still one of ours. */
    @Test
    void theSamePersonIsAColleagueForMeetingsHeldBeforeTheyLeft() {
        ColleagueDirectory directory = directoryOf(
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, LONG_AGO),
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.TERMINATED, LocalDate.of(2026, 1, 31)));

        assertTrue(directory.isColleagueOn("Malthe Yde Andreasen", LocalDate.of(2025, 11, 4)));
    }

    /** A rehire puts them back, and the meetings after it are internal again. */
    @Test
    void aRehireMakesThemAColleagueAgain() {
        ColleagueDirectory directory = directoryOf(
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, LONG_AGO),
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.TERMINATED, LocalDate.of(2026, 1, 31)),
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, LocalDate.of(2026, 6, 1)));

        assertTrue(directory.isColleagueOn("Malthe Yde Andreasen", MEETING_DAY));
    }

    /**
     * A termination and a rehire filed on the same day is routine. The non-terminated row
     * wins the tie, matching {@code User.getUserStatus(LocalDate)} exactly — reading it the
     * other way would mark somebody who came back as gone.
     */
    @Test
    void onATieTheNonTerminatedStatusWins() {
        ColleagueDirectory directory = directoryOf(
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.TERMINATED, LocalDate.of(2026, 6, 1)),
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, LocalDate.of(2026, 6, 1)));

        assertTrue(directory.isColleagueOn("Malthe Yde Andreasen", MEETING_DAY));
    }

    /**
     * A preboarder has an Intra user and a name but no Trustworks life yet. Somebody
     * writing to them at a client address is not writing to a colleague.
     */
    @Test
    void aPreboarderIsNotYetAColleague() {
        ColleagueDirectory directory = directoryOf(
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.PREBOARDING, LocalDate.of(2026, 8, 1)));

        assertFalse(directory.isColleagueOn("Malthe Yde Andreasen", MEETING_DAY));
    }

    /** Their first status row is in the future: on the day of the meeting they did not exist to us. */
    @Test
    void noStatusRowOnOrBeforeTheDateMeansNotEmployed() {
        ColleagueDirectory directory = directoryOf(
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, LocalDate.of(2026, 12, 1)));

        assertFalse(directory.isColleagueOn("Malthe Yde Andreasen", MEETING_DAY));
    }

    /** Leave is still employment — a person on parental leave is unambiguously one of ours. */
    @Test
    void somebodyOnLeaveIsStillAColleague() {
        ColleagueDirectory directory = directoryOf(
                row(MALTHE, "Malthe", "Yde Andreasen", StatusType.MATERNITY_LEAVE, LocalDate.of(2026, 3, 1)));

        assertTrue(directory.isColleagueOn("Malthe Yde Andreasen", MEETING_DAY));
    }

    @Test
    void rowsWithoutAStatusOrADateAreIgnored() {
        ColleagueDirectory directory = ColleagueDirectory.of(List.of(
                new ColleagueRow(MALTHE, "Malthe", "Yde Andreasen", null, LONG_AGO),
                new ColleagueRow(MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, null)));

        assertEquals(0, directory.size());
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static ColleagueRow row(String userUuid, String firstName, String lastName,
                                    StatusType status, LocalDate statusDate) {
        return new ColleagueRow(userUuid, firstName, lastName, status, statusDate);
    }

    private static ColleagueDirectory directoryOf(ColleagueRow... rows) {
        return ColleagueDirectory.of(List.of(rows));
    }

    // ------------------------------------------------------------------------
    // A name the client SHORTENED
    // ------------------------------------------------------------------------

    /**
     * Dagrofa's own case. It issued Nichlas Halberg Madsen the mailbox
     * {@code extnim@dagrofa.dk} and wrote {@code "Nichlas Madsen"} on it — two tokens against
     * our three, so the containment test could not reach it in that direction and ten
     * meetings with our own consultant sat on the Dagrofa account as client contact.
     */
    @Test
    void aClientThatDropsAMiddleNameIsStillOurColleague() {
        ColleagueDirectory directory = ColleagueDirectory.of(List.of(new ColleagueDirectory.ColleagueRow(
                "u-nichlas", "Nichlas", "Halberg Madsen", StatusType.ACTIVE, LocalDate.of(2020, 1, 1))));

        assertTrue(directory.isColleagueOn("Nichlas Madsen", LocalDate.of(2026, 5, 11)));
    }

    /** And with the client's own prefix in front of it, which is the commoner shape. */
    @Test
    void aShortenedNameIsFoundInsideAClientPrefix() {
        ColleagueDirectory directory = ColleagueDirectory.of(List.of(new ColleagueDirectory.ColleagueRow(
                "u-emma", "Emma", "Juul Sandberg Andersen", StatusType.ACTIVE, LocalDate.of(2020, 1, 1))));

        assertTrue(directory.isColleagueOn("EMAND (Emma Sandberg Andersen)", LocalDate.of(2026, 5, 11)),
                "one middle name dropped, a client prefix added");
    }

    /**
     * The shortening rule must not re-open the hole {@link #anAttendeeWhoseNameMerelyContainsAColleaguesNameDoesNotMatch()}
     * closed. Every candidate window has to START on the employee's first token, and
     * {@code marianne} is not {@code anne}.
     */
    @Test
    void theShorteningRuleStillWillNotMatchMarianneAgainstAnne() {
        ColleagueDirectory directory = ColleagueDirectory.of(List.of(new ColleagueDirectory.ColleagueRow(
                "u-anne", "Anne", "Berit Hansen", StatusType.ACTIVE, LocalDate.of(2020, 1, 1))));

        assertFalse(directory.isColleagueOn("Marianne Hansen", LocalDate.of(2026, 5, 11)));
        assertFalse(directory.isColleagueOn("Berit Hansen", LocalDate.of(2026, 5, 11)),
                "a window has to start on the FIRST name, not on a middle one");
    }

    /**
     * The reason the shortening rule is safe to widen at all: employment still decides.
     *
     * <p>Production's own instance. Sandra Holm Andersen left on 2026-03-01 and works at AP
     * Pension now; {@code sae@appension.dk} carries {@code "Sandra Andersen"} on a meeting
     * dated 2026-05-08. The name rule finds her — and must then hand her back, because an
     * ex-colleague inside a client is the single most valuable contact that account has.
     */
    @Test
    void aShortenedNameBelongingToSomebodyWhoHasLeftIsNotOurs() {
        ColleagueDirectory directory = ColleagueDirectory.of(List.of(
                new ColleagueDirectory.ColleagueRow(
                        "u-sandra", "Sandra", "Holm Andersen", StatusType.ACTIVE, LocalDate.of(2023, 1, 1)),
                new ColleagueDirectory.ColleagueRow(
                        "u-sandra", "Sandra", "Holm Andersen", StatusType.TERMINATED, LocalDate.of(2026, 3, 1))));

        assertTrue(directory.isColleagueOn("Sandra Andersen", LocalDate.of(2026, 1, 15)),
                "while she was ours");
        assertFalse(directory.isColleagueOn("Sandra Andersen", LocalDate.of(2026, 5, 8)),
                "after she left — she is AP Pension's now, and a client contact");
    }

    // ------------------------------------------------------------------------
    // Rule b — a name the client LENGTHENED, and only where we have been placed
    // ------------------------------------------------------------------------

    /**
     * The shape the strict rules cannot reach: the client writes tokens IN BETWEEN the two
     * our user row holds, so the run is not contiguous and the attendee name is longer
     * rather than shorter. Spec §1.2 D1 — 66 attendee rows across five people, 53 of them
     * Sara Vest's.
     */
    @Test
    void aPlacedConsultantIsOursHoweverManyNamesTheClientPutsInTheMiddle() {
        ColleagueDirectory directory = ColleagueDirectory.of(
                List.of(row(SARA, "Sara", "Vest", StatusType.ACTIVE, LONG_AGO)),
                List.of(new ColleagueDirectory.PlacementRow(SARA, BANE)));

        assertFalse(directory.isColleagueOn("Sara Louise Vest (XSVES)", MEETING_DAY),
                "the strict rules cannot see her — that is the whole defect");
        assertEquals(SARA, directory.colleagueUuidOn("Sara Louise Vest (XSVES)", MEETING_DAY, BANE));
        assertTrue(directory.colleagueOn("Sara Louise Vest (XSVES)", MEETING_DAY, BANE).byPlacement(),
                "and the run's log has to be able to say it was the widened rule that fired");
    }

    /**
     * The confinement that makes rule b safe at all. Without the placement gate this rule
     * matches any attendee who happens to share a first name and a surname with one of ours,
     * and deletes them from the account.
     */
    @Test
    void theSameNameAtAClientWeHaveNeverBeenPlacedAtIsNotOurs() {
        ColleagueDirectory directory = ColleagueDirectory.of(
                List.of(row(NICOLAS, "Lars", "Jensen", StatusType.ACTIVE, LONG_AGO)),
                List.of(new ColleagueDirectory.PlacementRow(NICOLAS, BANE)));

        assertNull(directory.colleagueUuidOn("Lars Peter Jensen", MEETING_DAY, OTHER_CLIENT),
                "our Lars has never worked here, so this is the client's own Lars Peter Jensen");
        assertEquals(NICOLAS, directory.colleagueUuidOn("Lars Peter Jensen", MEETING_DAY, BANE),
                "at the client he IS placed at, he is ours");
    }

    /** No client in hand means the question cannot be asked, so the widened rule is skipped. */
    @Test
    void theTwoArgumentFormNeverAppliesTheWidenedRule() {
        ColleagueDirectory directory = ColleagueDirectory.of(
                List.of(row(SARA, "Sara", "Vest", StatusType.ACTIVE, LONG_AGO)),
                List.of(new ColleagueDirectory.PlacementRow(SARA, BANE)));

        assertNull(directory.colleagueUuidOn("Sara Louise Vest (XSVES)", MEETING_DAY));
        assertNull(directory.colleagueUuidOn("Sara Louise Vest (XSVES)", MEETING_DAY, null));
    }

    /**
     * Nina Schrøder Jakobsen, matched on {@code nina … jakobsen} alone. Our {@code user} row
     * spells her surname Sch<b>ø</b>der and the client spells it Sch<b>r</b>øder; the rule
     * never looks at the middle, so the typo costs nothing and the one-line fix to the user
     * row (spec §12) is genuinely separate.
     */
    @Test
    void aTypoInTheMiddleOfOurOwnUserRowDoesNotStopRuleB() {
        ColleagueDirectory directory = ColleagueDirectory.of(
                List.of(row(NINA, "Nina", "Schøder Jakobsen", StatusType.ACTIVE, LONG_AGO)),
                List.of(new ColleagueDirectory.PlacementRow(NINA, BANE)));

        assertEquals(NINA, directory.colleagueUuidOn("Nina Schrøder Jakobsen (XNJAK)", MEETING_DAY, BANE));
    }

    /**
     * A placement is permission to use the looser name rule and nothing else. Employment on
     * the day of the meeting still decides, and a placed colleague who left and stayed at
     * the client is that account's warmest contact.
     */
    @Test
    void aPlacementDoesNotExemptAnybodyFromTheEmploymentTest() {
        ColleagueDirectory directory = ColleagueDirectory.of(
                List.of(row(SARA, "Sara", "Vest", StatusType.ACTIVE, LONG_AGO),
                        row(SARA, "Sara", "Vest", StatusType.TERMINATED, LocalDate.of(2026, 1, 31))),
                List.of(new ColleagueDirectory.PlacementRow(SARA, BANE)));

        assertEquals(SARA, directory.colleagueUuidOn("Sara Louise Vest (XSVES)", LocalDate.of(2025, 11, 4), BANE));
        assertNull(directory.colleagueUuidOn("Sara Louise Vest (XSVES)", MEETING_DAY, BANE),
                "she works at the client now");
    }

    /**
     * A strict match must never be taken away by the looser rule finding somebody else,
     * which is why rule a is run against everybody before rule b is run against anybody.
     *
     * <p>The collision is built to be decided by that ordering and by nothing else: the
     * placed near-match carries the longer name, so it is the FIRST candidate the
     * longest-name-first scan reaches. Interleaving the two rules per candidate would hand
     * the attendee to her instead of to the person whose name is actually written out.
     */
    @Test
    void theStrictRulesAreTriedAgainstEverybodyBeforeTheWidenedOneIsTriedAtAll() {
        ColleagueDirectory directory = ColleagueDirectory.of(
                List.of(row(SARA, "Anna", "Birgitte Kirstine Hansen", StatusType.ACTIVE, LONG_AGO),
                        row(NINA, "Mette", "Forbord", StatusType.ACTIVE, LONG_AGO)),
                List.of(new ColleagueDirectory.PlacementRow(SARA, BANE)));

        assertEquals(NINA, directory.colleagueUuidOn("Anna Mette Forbord Hansen", MEETING_DAY, BANE),
                "the contiguous strict match wins, though the placed candidate is scanned first");
        assertFalse(directory.colleagueOn("Anna Mette Forbord Hansen", MEETING_DAY, BANE).byPlacement());
    }

    @Test
    void theAnchorsHaveToBeTwoPositionsInOrder() {
        assertTrue(ColleagueDirectory.containsFirstAndLastOf(
                List.of("sara", "louise", "vest", "xsves"), List.of("sara", "vest")));
        assertTrue(ColleagueDirectory.containsFirstAndLastOf(
                List.of("stmj", "stephan", "mosko", "jensen"), List.of("stephan", "jensen")));
        assertFalse(ColleagueDirectory.containsFirstAndLastOf(
                List.of("vest", "sara"), List.of("sara", "vest")), "in that order, not either order");
        assertFalse(ColleagueDirectory.containsFirstAndLastOf(
                List.of("sara"), List.of("sara", "vest")), "one token cannot hold both anchors");
        assertFalse(ColleagueDirectory.containsFirstAndLastOf(
                List.of("sara", "louise", "holm"), List.of("sara", "vest")));
    }

    @Test
    void placementsAreCountedAndUnknownPeopleAreNotPlacedAnywhere() {
        ColleagueDirectory directory = ColleagueDirectory.of(
                List.of(row(SARA, "Sara", "Vest", StatusType.ACTIVE, LONG_AGO)),
                List.of(new ColleagueDirectory.PlacementRow(SARA, BANE),
                        new ColleagueDirectory.PlacementRow(SARA, BANE),
                        new ColleagueDirectory.PlacementRow(SARA, OTHER_CLIENT),
                        new ColleagueDirectory.PlacementRow(null, BANE)));

        assertEquals(2, directory.placementCount(), "two assignments to the same client are one placement");
        assertTrue(directory.isPlacedAt(SARA, BANE));
        assertFalse(directory.isPlacedAt(NINA, BANE));
        assertFalse(directory.isPlacedAt(SARA, null));
        assertEquals(0, ColleagueDirectory.empty().placementCount());
    }

    /** A directory built without placements answers exactly as it did before rule b existed. */
    @Test
    void aDirectoryBuiltWithoutPlacementsCannotFireTheWidenedRule() {
        ColleagueDirectory directory = directoryOf(row(SARA, "Sara", "Vest", StatusType.ACTIVE, LONG_AGO));

        assertEquals(0, directory.placementCount());
        assertNull(directory.colleagueUuidOn("Sara Louise Vest (XSVES)", MEETING_DAY, BANE));
    }
}
