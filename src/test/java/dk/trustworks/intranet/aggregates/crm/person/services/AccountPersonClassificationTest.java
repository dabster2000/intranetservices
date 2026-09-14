package dk.trustworks.intranet.aggregates.crm.person.services;

import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonKind;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.Classification;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.ColleagueIndex;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.ColleagueRef;
import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService.StatusPoint;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Is this name one of ours, one of ours who left, or a person at the client — spec §3.2 and
 * §4.1. This is the decision the whole cut exists to get right, so every production row that
 * proved it wrong is pinned here by name.
 *
 * <p>The five it has to answer correctly:
 * <ul>
 *   <li>{@code Sara Louise Vest (XSVES)} at Banedanmark — 53 of the 441 attendee rows, drawn
 *       as a client contact while the same human sat on the other side of the picture as our
 *       contract consultant (defect D1). {@code COLLEAGUE}.</li>
 *   <li>{@code STMJ (Stephan Mosko Jensen)} at Novo Nordisk — the example Hans raised.
 *       {@code COLLEAGUE}.</li>
 *   <li>{@code Janni Høyer Thoft} at Ældre Sagen, who left in 2019 and stayed at the client —
 *       one of the warmest contacts the firm has, shown as a stranger (defect D4).
 *       {@code ALUMNI}.</li>
 *   <li>{@code Lars Peter Jensen} at a client where no Lars Jensen of ours ever worked — a
 *       client person, and spec §4.1 is explicit that he must stay one. {@code CONTACT}.</li>
 *   <li>{@code Marianne Hansen} against an employee {@code Anne Hansen} — the substring hole,
 *       which must stay shut. {@code CONTACT}.</li>
 * </ul>
 *
 * <p>Fast tier — no Quarkus boot, no database. {@code classify} takes the two indexes as
 * arguments for exactly this reason: the rule is decidable from a name, a directory and a set
 * of placements, and nothing else.
 */
class AccountPersonClassificationTest {

    private static final String SARA = "11111111-1111-1111-1111-111111111111";
    private static final String STEPHAN = "22222222-2222-2222-2222-222222222222";
    private static final String JANNI = "33333333-3333-3333-3333-333333333333";
    private static final String LARS = "44444444-4444-4444-4444-444444444444";
    private static final String ANNE = "55555555-5555-5555-5555-555555555555";
    private static final String NICHLAS = "66666666-6666-6666-6666-666666666666";

    // ------------------------------------------------------------------------
    // D1 — our own consultants must stop being client contacts
    // ------------------------------------------------------------------------

    /**
     * Banedanmark writes {@code Full Name (INITIALS)} and carries a middle name our
     * {@code user} row does not. The employee's tokens are therefore NOT a contiguous run in
     * the attendee's, and the attendee's name is not a reduction of a two-token employee name,
     * so the strict rule cannot reach her in either direction. The placement is what does.
     */
    @Test
    @DisplayName("Sara Louise Vest at Banedanmark is one of ours, by her placement there")
    void saraLouiseVestAtBanedanmarkIsOneOfOurs() {
        Classification decision = AccountPersonService.classify("Sara Louise Vest",
                directory(employed(SARA, "Sara Vest")), Set.of(SARA));

        assertEquals(AccountPersonKind.COLLEAGUE, decision.kind(),
                "53 attendee rows on Banedanmark are this one person, and none of them is a client contact");
        assertEquals(SARA, decision.userUuid());
    }

    /** Novo's Exchange writes {@code INITIALS (Full Name)}; the same rule, the other format. */
    @Test
    @DisplayName("Stephan Mosko Jensen at Novo Nordisk is one of ours, by his placement there")
    void stephanMoskoJensenAtNovoIsOneOfOurs() {
        Classification decision = AccountPersonService.classify("Stephan Mosko Jensen",
                directory(employed(STEPHAN, "Stephan Jensen")), Set.of(STEPHAN));

        assertEquals(AccountPersonKind.COLLEAGUE, decision.kind());
        assertEquals(STEPHAN, decision.userUuid());
    }

    /**
     * The same name at a client he was never placed at is a different human who happens to
     * share a first and a last name. This is the half of the rule that keeps it safe.
     */
    @Test
    @DisplayName("The same name at a client with no placement is not one of ours")
    void theSameNameAtAClientWithNoPlacementIsNotOneOfOurs() {
        Classification decision = AccountPersonService.classify("Stephan Mosko Jensen",
                directory(employed(STEPHAN, "Stephan Jensen")), Set.of());

        assertEquals(AccountPersonKind.CONTACT, decision.kind());
        assertNull(decision.userUuid());
    }

    // ------------------------------------------------------------------------
    // D4 — a former colleague at the client is the warmest contact there is
    // ------------------------------------------------------------------------

    /**
     * She left on 2019-11-01 and stayed at Ældre Sagen; 8 attendee rows are hers. The sync was
     * right to keep her — the employment question for what gets STORED is asked about the day
     * of the meeting — and the page was wrong to show her as a stranger. Employment for what
     * gets SHOWN is asked about today, which is what makes her {@code ALUMNI}.
     */
    @Test
    @DisplayName("Janni Hoeyer Thoft at Aeldre Sagen left in 2019 and is alumni, not a stranger")
    void janniHoeyerThoftAtAeldreSagenIsAlumni() {
        Classification decision = AccountPersonService.classify("Janni Høyer Thoft",
                directory(former(JANNI, "Janni Thoft")), Set.of(JANNI));

        assertEquals(AccountPersonKind.ALUMNI, decision.kind(),
                "a former colleague who stayed at the client is a door, not a stranger");
        assertEquals(JANNI, decision.userUuid(),
                "the user row is kept so the page can say when she left");
    }

    /**
     * A re-hire flips back at the next rebuild. {@code FMHZ (Anna Mette Forbord Hansen)} has
     * Novo rows from January 2026 and was re-hired on 2026-04-07; in September the page must
     * not still be offering a current employee as somebody we know at Novo (defect D2).
     */
    @Test
    @DisplayName("A rehire flips back to colleague at the next rebuild")
    void aRehireFlipsBackToColleagueAtTheNextRebuild() {
        assertEquals(AccountPersonKind.ALUMNI,
                AccountPersonService.classify("Anna Mette Forbord Hansen",
                        directory(former(ANNE, "Anna Hansen")), Set.of(ANNE)).kind());
        assertEquals(AccountPersonKind.COLLEAGUE,
                AccountPersonService.classify("Anna Mette Forbord Hansen",
                        directory(employed(ANNE, "Anna Hansen")), Set.of(ANNE)).kind(),
                "the only thing that changed is the answer to 'employed today'");
    }

    // ------------------------------------------------------------------------
    // §4.1 — a key match alone is not enough, and must never become enough
    // ------------------------------------------------------------------------

    /**
     * {@code Lars Peter Jensen} keys to {@code lars|jensen} exactly as our own Lars Jensen
     * does. Without a placement at THIS client he is a client person, and spec §4.1 says so in
     * as many words. Dropping the placement condition would turn every common Danish name at
     * every account into a colleague and quietly delete real contacts from the page.
     */
    @Test
    @DisplayName("Lars Peter Jensen with no placement is a client person and must stay one")
    void larsPeterJensenWithNoPlacementIsAClientPerson() {
        Classification decision = AccountPersonService.classify("Lars Peter Jensen",
                directory(employed(LARS, "Lars Jensen")), Set.of());

        assertEquals(AccountPersonKind.CONTACT, decision.kind());
        assertNull(decision.userUuid());
    }

    /**
     * The substring hole, from the other direction. "Marianne Hansen" and "Anne Hansen" key to
     * {@code marianne|hansen} and {@code anne|hansen}, so the candidate list is empty before
     * any token test runs — a placement cannot re-open it either.
     */
    @Test
    @DisplayName("Marianne Hansen never reaches the employee Anne Hansen, placement or not")
    void marianneHansenNeverReachesTheEmployeeAnneHansen() {
        ColleagueIndex directory = directory(employed(ANNE, "Anne Hansen"));

        assertEquals(AccountPersonKind.CONTACT,
                AccountPersonService.classify("Marianne Hansen", directory, Set.of()).kind());
        assertEquals(AccountPersonKind.CONTACT,
                AccountPersonService.classify("Marianne Hansen", directory, Set.of(ANNE)).kind(),
                "the name key gate closes this before the placement rule is ever consulted");
    }

    // ------------------------------------------------------------------------
    // The strict rule, which needs no placement
    // ------------------------------------------------------------------------

    /**
     * Dagrofa issued Nichlas Halberg Madsen the mailbox {@code extnim@dagrofa.dk} and put
     * "Nichlas Madsen" on it. The attendee's name is a reduction of the employee's, which is
     * the strict rule, so no placement is needed for this one.
     */
    @Test
    @DisplayName("A shortened form of an employee's name is a strict match, with no placement")
    void aShortenedFormOfAnEmployeesNameIsAStrictMatch() {
        Classification decision = AccountPersonService.classify("Nichlas Madsen",
                directory(employed(NICHLAS, "Nichlas Halberg Madsen")), Set.of());

        assertEquals(AccountPersonKind.COLLEAGUE, decision.kind());
        assertEquals(NICHLAS, decision.userUuid());
    }

    /** The other strict direction: the employee's tokens appear whole and adjacent. */
    @Test
    @DisplayName("An attendee name that contains the employee's in full is a strict match")
    void anAttendeeNameThatContainsTheEmployeesInFullIsAStrictMatch() {
        Classification decision = AccountPersonService.classify("Nicolas De Teilmann",
                directory(employed(NICHLAS, "Nicolas De Teilmann")), Set.of());

        assertEquals(AccountPersonKind.COLLEAGUE, decision.kind());
    }

    // ------------------------------------------------------------------------
    // Nobody of ours
    // ------------------------------------------------------------------------

    /** The common case: most people at a client are people at the client. */
    @Test
    @DisplayName("A name that matches nobody is a client person")
    void aNameThatMatchesNobodyIsAClientPerson() {
        assertEquals(AccountPersonKind.CONTACT,
                AccountPersonService.classify("Sif Broby Madsen",
                        directory(employed(SARA, "Sara Vest")), Set.of(SARA)).kind());
    }

    /**
     * An empty or absent directory answers {@code CONTACT} for everybody. That is the right
     * failure: a rebuild whose directory read came back thin must leave people on the page as
     * client contacts rather than hide them as colleagues.
     */
    @Test
    @DisplayName("An empty directory classifies everybody as a client person")
    void anEmptyDirectoryClassifiesEverybodyAsAClientPerson() {
        assertEquals(AccountPersonKind.CONTACT,
                AccountPersonService.classify("Sara Louise Vest", ColleagueIndex.empty(), Set.of(SARA)).kind());
        assertEquals(AccountPersonKind.CONTACT,
                AccountPersonService.classify(null, directory(employed(SARA, "Sara Vest")), Set.of()).kind());
        assertEquals(AccountPersonKind.CONTACT,
                AccountPersonService.classify("Sara Louise Vest", null, Set.of()).kind());
        assertEquals(AccountPersonKind.CONTACT,
                AccountPersonService.classify("Sara Louise Vest",
                        directory(employed(SARA, "Sara Vest")), null).kind());
    }

    /** A {@code user} row with no name at all cannot be matched to anybody. */
    @Test
    @DisplayName("A user with no name is not in the directory")
    void aUserWithNoNameIsNotInTheDirectory() {
        assertEquals(0, directory(employed(SARA, "   ")).size());
        assertEquals(0, ColleagueIndex.of(null).size());
    }

    // ------------------------------------------------------------------------
    // Two of ours sharing one name key
    // ------------------------------------------------------------------------

    /**
     * Genuinely ambiguous, and rare. A strict token match is evidence about WHICH of them this
     * is, so it wins over a placement-only one.
     */
    @Test
    @DisplayName("A strict match beats a placement-only one when two of ours share a name key")
    void aStrictMatchBeatsAPlacementOnlyOne() {
        Classification decision = AccountPersonService.classify("Lars Peter Jensen",
                ColleagueIndex.of(List.of(
                        employed(LARS, "Lars Jensen"),
                        employed(STEPHAN, "Lars Peter Jensen"))),
                Set.of(LARS));

        assertEquals(STEPHAN, decision.userUuid(), "the one whose tokens actually match is the one it is");
    }

    /**
     * On otherwise equal evidence the employed user wins, because showing our own consultant
     * as a client contact is the failure this code exists to prevent.
     */
    @Test
    @DisplayName("An employed colleague wins over a former one on equal evidence")
    void anEmployedColleagueWinsOverAFormerOneOnEqualEvidence() {
        Classification decision = AccountPersonService.classify("Lars Peter Jensen",
                ColleagueIndex.of(List.of(
                        former(JANNI, "Lars Jensen"),
                        employed(LARS, "Lars Jensen"))),
                Set.of(JANNI, LARS));

        assertEquals(AccountPersonKind.COLLEAGUE, decision.kind());
        assertEquals(LARS, decision.userUuid());
    }

    // ------------------------------------------------------------------------
    // "Employed today"
    // ------------------------------------------------------------------------

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    /**
     * A rehire and a termination filed on the same day. Reading the tie the other way marks
     * somebody who came back as gone — and then shows them on an account as a stranger.
     */
    @Test
    @DisplayName("On one statusdate the non-terminated row wins")
    void onOneStatusdateTheNonTerminatedRowWins() {
        assertTrue(AccountPersonService.employedOn(List.of(
                new StatusPoint(StatusType.TERMINATED, LocalDate.of(2026, 4, 7)),
                new StatusPoint(StatusType.ACTIVE, LocalDate.of(2026, 4, 7))), TODAY));
    }

    @Test
    @DisplayName("Terminated, preboarding and no status at all all mean not one of ours")
    void terminatedPreboardingAndNoStatusMeanNotOneOfOurs() {
        assertFalse(AccountPersonService.employedOn(List.of(
                new StatusPoint(StatusType.ACTIVE, LocalDate.of(2015, 1, 1)),
                new StatusPoint(StatusType.TERMINATED, LocalDate.of(2019, 11, 1))), TODAY));
        assertFalse(AccountPersonService.employedOn(List.of(
                new StatusPoint(StatusType.PREBOARDING, LocalDate.of(2026, 1, 1))), TODAY));
        assertFalse(AccountPersonService.employedOn(List.of(
                new StatusPoint(StatusType.ACTIVE, LocalDate.of(2026, 12, 1))), TODAY),
                "a status that starts after the date has not started");
        assertFalse(AccountPersonService.employedOn(List.of(), TODAY));
        assertFalse(AccountPersonService.employedOn(null, TODAY));
    }

    /** Paid and unpaid leave are still employment — a person on leave is still one of ours. */
    @Test
    @DisplayName("Leave is still employment")
    void leaveIsStillEmployment() {
        assertTrue(AccountPersonService.employedOn(List.of(
                new StatusPoint(StatusType.MATERNITY_LEAVE, LocalDate.of(2026, 5, 1))), TODAY));
        assertTrue(AccountPersonService.employedOn(List.of(
                new StatusPoint(StatusType.NON_PAY_LEAVE, LocalDate.of(2026, 5, 1))), TODAY));
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static ColleagueIndex directory(ColleagueRef... refs) {
        return ColleagueIndex.of(List.of(refs));
    }

    private static ColleagueRef employed(String userUuid, String fullName) {
        return new ColleagueRef(userUuid, fullName, true);
    }

    private static ColleagueRef former(String userUuid, String fullName) {
        return new ColleagueRef(userUuid, fullName, false);
    }
}
