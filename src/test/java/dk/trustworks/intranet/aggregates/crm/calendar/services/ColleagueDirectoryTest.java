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
    private static final LocalDate MEETING_DAY = LocalDate.of(2026, 9, 12);
    private static final LocalDate LONG_AGO = LocalDate.of(2019, 1, 1);

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
}
