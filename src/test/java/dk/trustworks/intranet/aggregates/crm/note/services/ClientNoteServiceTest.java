package dk.trustworks.intranet.aggregates.crm.note.services;

import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The input handling and the one authorization rule in {@link ClientNoteService}.
 *
 * <p>Plain JUnit, no {@code @QuarkusTest}: the deploy gate runs
 * {@code ./mvnw test -DexcludedGroups=io.quarkus.test.junit.QuarkusTest}, so a container
 * test would simply never run. Both facts locked here need it — the cap is the privacy
 * control the whole design leans on, and the removal rule has no UI exercising it at all,
 * which is exactly why it cannot be left to be discovered in production.
 *
 * <p>{@code create} and {@code delete} themselves need an entity and are exercised against
 * the local database during verification. What is locked here is what ever reaches the
 * column, and who ever gets past the door.
 */
class ClientNoteServiceTest {

    private static final String HANS = "6b4a2b4f-0a6a-4a5d-9f4f-0d7b2a0f1c31";
    private static final String LARS = "c0a1d2e3-4f56-4789-8abc-de0123456789";

    @Test
    void aBlankNoteIsRefusedRatherThanStoredAsAnEmptyLine() {
        for (String nothing : new String[]{null, "", "   "}) {
            WebApplicationException refused = assertThrows(WebApplicationException.class,
                    () -> ClientNoteService.requireNoteText(nothing));
            assertEquals(400, refused.getResponse().getStatus());
            assertEquals("Nothing was typed", refused.getMessage());
        }
    }

    @Test
    void aNoteIsTrimmedNotPadded() {
        assertEquals("Called Lars — they re-tender in Q1",
                ClientNoteService.requireNoteText("  Called Lars — they re-tender in Q1  "));
    }

    /**
     * Refused, not truncated — unlike {@code BidService.trimTo}, which cuts an extracted
     * field to its column. The author is looking at the box, and publishing half their
     * sentence on the most-read surface of the account page is worse than asking them to
     * shorten it. The number is spelled out rather than read from the constant on purpose:
     * the frontend counter holds the same 280, and this is the assertion that fails if one
     * side moves alone.
     */
    @Test
    void anOverlongNoteIsRefusedAndNamesTheCap() {
        WebApplicationException refused = assertThrows(WebApplicationException.class,
                () -> ClientNoteService.requireNoteText("x".repeat(281)));
        assertEquals(400, refused.getResponse().getStatus());
        assertEquals("A note is one line — keep it under 280 characters", refused.getMessage());
    }

    @Test
    void aNoteExactlyAtTheCapIsAccepted() {
        String atTheCap = "x".repeat(ClientNoteService.MAX_NOTE_CHARS);
        assertEquals(atTheCap, ClientNoteService.requireNoteText(atTheCap));
    }

    /**
     * The rule nothing else exercises. Notes are removable but not editable, and no screen
     * offers removal yet, so this test is the only thing standing between the rule and a
     * future caller that quietly deletes somebody else's words.
     */
    @Test
    void onlyTheAuthorRemovesTheirOwnLine() {
        assertDoesNotThrow(() -> ClientNoteService.requireAuthorOrManagement(HANS, HANS, false));

        WebApplicationException refused = assertThrows(WebApplicationException.class,
                () -> ClientNoteService.requireAuthorOrManagement(HANS, LARS, false));
        assertEquals(403, refused.getResponse().getStatus());
        assertEquals("A note is somebody's own words — only its author can remove it",
                refused.getMessage());
    }

    /**
     * Management reaches every line. That override is not a convenience: it is the erasure
     * path for the case the author cannot act — they have left the firm — and a note that
     * named the wrong person still has to come down.
     */
    @Test
    void managementCanRemoveALineTheyDidNotWrite() {
        assertDoesNotThrow(() -> ClientNoteService.requireAuthorOrManagement(HANS, LARS, true));
        assertDoesNotThrow(() -> ClientNoteService.requireAuthorOrManagement(null, LARS, true));
    }

    /** A row with no author on it belongs to nobody, so nobody but management may take it down. */
    @Test
    void aNoteWithNoAuthorIsNotSilentlyEverybodys() {
        assertThrows(WebApplicationException.class,
                () -> ClientNoteService.requireAuthorOrManagement(null, LARS, false));
    }
}
