package dk.trustworks.intranet.aggregates.crm.account.services;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The summary composition in {@link AccountActivityService}.
 *
 * <p>The SQL itself needs a database and is exercised during verification; what is locked
 * here is the text that reaches the reader — in particular that a meeting is described by
 * WHO was in it, because there is no subject to describe it with and never will be.
 */
class AccountActivityServiceTest {

    @Test
    void aMeetingIsNamedByItsAttendees() {
        assertEquals("Mette Kjær, Søren Bjerre",
                AccountActivityService.joinNames(List.of("Mette Kjær", "Søren Bjerre")));
    }

    /** Long invitation lists must not push the rest of the row off the line. */
    @Test
    void longAttendeeListsAreCutWithACount() {
        assertEquals("A, B, C +2 more",
                AccountActivityService.joinNames(List.of("A", "B", "C", "D", "E")));
    }

    /**
     * An attendee list can be empty: the meeting matched a client domain on an attendee
     * that has since been removed from the invitation. "Meeting with the client" is still
     * a true sentence; "Meeting with " is not.
     */
    @Test
    void noAttendeesStillReadsAsASentence() {
        assertEquals("the client", AccountActivityService.joinNames(List.of()));
        assertEquals("the client", AccountActivityService.joinNames(null));
    }

    @Test
    void enumNamesAreTitledForReading() {
        assertEquals("Strategic", AccountActivityService.titled("STRATEGIC"));
        assertEquals("Coming project", AccountActivityService.titled("COMING_PROJECT"));
        assertEquals("—", AccountActivityService.titled(null));
        assertEquals("—", AccountActivityService.titled(""));
    }

    @Test
    void signalTypesReadAsEnglishInsideTheSummary() {
        assertEquals("an organisational change", AccountActivityService.typeLabel("ORG_CHANGE"));
        assertEquals("a coming project", AccountActivityService.typeLabel("COMING_PROJECT"));
        assertEquals("something", AccountActivityService.typeLabel("NOT_A_TYPE"));
        assertEquals("something", AccountActivityService.typeLabel(null));
    }

    @Test
    void decisionsReadAsWhatWasDecided() {
        assertEquals("turned into a lead", AccountActivityService.decisionLabel("LEAD_CREATED"));
        assertEquals("parked", AccountActivityService.decisionLabel("PARKED"));
        assertEquals("closed as not relevant", AccountActivityService.decisionLabel("NOT_RELEVANT"));
        assertEquals("decided", AccountActivityService.decisionLabel("ANYTHING_ELSE"));
    }

    /**
     * The feed unions six tables whose date columns are datetime, datetime(3), datetime(6)
     * and timestamp. A native query hands those back as different Java types depending on
     * the driver, and a row whose date failed to convert would either sort to the top of
     * the feed or vanish from it.
     */
    @Test
    void everyShapeADateArrivesInConvertsToTheSameDay() {
        LocalDate expected = LocalDate.of(2026, 9, 12);
        assertEquals(expected,
                AccountActivityService.toLocalDate(Timestamp.valueOf(LocalDateTime.of(2026, 9, 12, 14, 30))));
        assertEquals(expected, AccountActivityService.toLocalDate(java.sql.Date.valueOf(expected)));
        assertEquals(expected, AccountActivityService.toLocalDate(LocalDateTime.of(2026, 9, 12, 0, 1)));
        assertEquals(expected, AccountActivityService.toLocalDate(expected));
        assertNull(AccountActivityService.toLocalDate(null));
        assertNull(AccountActivityService.toLocalDate("not a date"));
    }
}
