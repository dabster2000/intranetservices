package dk.trustworks.intranet.recruitmentservice.airtable;

import dk.trustworks.intranet.recruitmentservice.airtable.AirtableReferrerMatcher.DirectoryUser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dk.trustworks.intranet.recruitmentservice.airtable.AirtableReferrerMatcher.deterministicMatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tiers 1–2 of the referrer matcher, tested with the REAL failure shapes
 * found when reconciling every Airtable reference name against the
 * production directory (2026-08-10): middle names, directory double
 * spaces, ambiguity, and too-weak single tokens.
 */
class AirtableReferrerMatcherTest {

    private static final List<DirectoryUser> DIRECTORY = List.of(
            new DirectoryUser("u-elvi", "Elvi Rohde", "Nissen"),
            new DirectoryUser("u-caroline", "Caroline ", " Tachieda"),
            new DirectoryUser("u-stephan", "Stephan", "Jensen"),
            new DirectoryUser("u-jeppe", "Jeppe", "Cramon"),
            new DirectoryUser("u-lars-o", "Lars", "Østergaard"),
            new DirectoryUser("u-lars-t", "Lars Albert", "Beck Thomsen"),
            new DirectoryUser("u-anna1", "Anna", "Hansen"),
            new DirectoryUser("u-anna2", "Anna Mette", "Hansen"),
            new DirectoryUser("u-mette1", "Mette Louise", "Sørensen"),
            new DirectoryUser("u-mette2", "Mette Kirstine", "Sørensen"));

    @Test
    void exactMatch_wins() {
        assertEquals("u-jeppe", deterministicMatch("Jeppe Cramon", DIRECTORY));
    }

    @Test
    void middleNameInDirectory_matchesByFirstAndLastToken() {
        // The real Elvi case: Airtable says "Elvi Nissen", directory says "Elvi Rohde Nissen".
        assertEquals("u-elvi", deterministicMatch("Elvi Nissen", DIRECTORY));
    }

    @Test
    void directoryDoubleSpaces_doNotBreakExactMatch() {
        // The directory literally contains "Caroline  Tachieda" (double space).
        assertEquals("u-caroline", deterministicMatch("Caroline Tachieda", DIRECTORY));
    }

    @Test
    void middleNameInReference_matchesShorterDirectoryName() {
        // Airtable "Stephan Mosko Jensen" vs directory "Stephan Jensen".
        assertEquals("u-stephan", deterministicMatch("Stephan Mosko Jensen", DIRECTORY));
    }

    @Test
    void multiTokenLastName_matchesOnItsLastToken() {
        assertEquals("u-lars-t", deterministicMatch("Lars Thomsen", DIRECTORY));
    }

    @Test
    void exactFullNameMatch_beatsTokenAmbiguity() {
        // "Anna Hansen" token-matches two people, but one is literally
        // named "Anna Hansen" — the exact hit is the stronger signal.
        assertEquals("u-anna1", deterministicMatch("Anna Hansen", DIRECTORY));
    }

    @Test
    void trueTokenAmbiguity_neverGuesses() {
        // No exact "Mette Sørensen" exists; two people token-match — refuse.
        assertNull(deterministicMatch("Mette Sørensen", DIRECTORY));
    }

    @Test
    void singleToken_isTooWeak() {
        // "Lars A" normalizes to tokens where first+last are 'lars'/'a' —
        // and a lone "Lars" must never link to anyone.
        assertNull(deterministicMatch("Lars", DIRECTORY));
    }

    @Test
    void proseAndMultiPersonText_doesNotMatchDeterministically() {
        assertNull(deterministicMatch("Morten Lund og Charlotte Ellesøe", DIRECTORY));
        assertNull(deterministicMatch(
                "Ikke en trustworks kollega, men en konsulent der har arbejdet sammen med jer før.",
                DIRECTORY));
    }

    @Test
    void blankAndNull_areNull() {
        assertNull(deterministicMatch("", DIRECTORY));
        assertNull(deterministicMatch("   ", DIRECTORY));
    }
}
