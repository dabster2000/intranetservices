package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.graph.GraphCalendarClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link AccountCalendarSyncService} keeps, what it drops, and what it never asks for.
 *
 * <p>This is the privacy boundary of the whole feature, so it is tested rather than
 * trusted: the {@code $select} must never name a subject or a body, and an event with
 * nobody from a known client in it must produce no row at all.
 *
 * <p>Fast tier — no Quarkus boot, no Graph, no database.
 */
class AccountCalendarSyncServiceTest {

    private static final String ME = "11111111-1111-1111-1111-111111111111";
    private static final String CLIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_CLIENT = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final Map<String, String> DOMAINS =
            Map.of("acme.dk", CLIENT, "beta.dk", OTHER_CLIENT);

    private final AccountCalendarSyncService service = new AccountCalendarSyncService();

    // ------------------------------------------------------------------------
    // The privacy boundary
    // ------------------------------------------------------------------------

    /**
     * The single most important assertion in this class. Spec §3.7 permits attendees, date
     * and duration and forbids the subject; if this select ever grows a {@code subject},
     * meeting titles start leaving Microsoft's tenant on every nightly run.
     */
    @Test
    void theGraphSelectNeverAsksForASubjectOrABody() {
        assertFalse(AccountCalendarSyncService.SELECT.contains("subject"),
                "The subject must never be requested — spec §3.7 and there is no column for it");
        assertFalse(AccountCalendarSyncService.SELECT.contains("body"));
        assertEquals("id,start,end,isCancelled,attendees", AccountCalendarSyncService.SELECT);
    }

    // ------------------------------------------------------------------------
    // Which events become meetings
    // ------------------------------------------------------------------------

    @Test
    void anEventWithAClientAttendeeBecomesAMeetingOnThatAccount() {
        var meeting = service.toMeeting(ME, event("evt-1", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mette@acme.dk", "Mette Kjær"),
                attendee("hans@trustworks.dk", "Hans Lassen")), DOMAINS);

        assertNotNull(meeting);
        assertEquals(CLIENT, meeting.clientUuid());
        assertEquals(60, meeting.durationMinutes());
        assertEquals(2, meeting.attendeeCount(), "the size of the room, internal people included");
        assertEquals(1, meeting.attendees().size(), "only the external attendee is stored");
        assertEquals("mette@acme.dk", meeting.attendees().get(0).email());
        assertEquals("acme.dk", meeting.attendees().get(0).domain());
    }

    /** An internal meeting is not account activity and must leave no trace. */
    @Test
    void anEventWithNobodyFromAKnownClientIsDropped() {
        assertNull(service.toMeeting(ME, event("evt-2", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("hans@trustworks.dk", "Hans"),
                attendee("someone@unknown.dk", "Someone")), DOMAINS));
    }

    /** A cancelled meeting did not happen; counting it would overstate the relationship. */
    @Test
    void cancelledEventsAreDropped() {
        assertNull(service.toMeeting(ME, event("evt-3", true, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mette@acme.dk", "Mette")), DOMAINS));
    }

    @Test
    void anEventWithNoStartIsDropped() {
        assertNull(service.toMeeting(ME, event("evt-4", false, null, "2026-09-12T10:00:00",
                attendee("mette@acme.dk", "Mette")), DOMAINS));
    }

    @Test
    void anEventWithNoAttendeesAtAllIsDropped() {
        assertNull(service.toMeeting(ME,
                event("evt-5", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00"), DOMAINS));
    }

    /**
     * Two clients in one room. Splitting the meeting would double-count it on both
     * accounts; dropping it would lose it. The bigger delegation wins.
     */
    @Test
    void aMeetingWithTwoClientsGoesToTheOneWithMorePeopleInIt() {
        var meeting = service.toMeeting(ME, event("evt-6", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("a@acme.dk", "A"),
                attendee("b@acme.dk", "B"),
                attendee("c@beta.dk", "C")), DOMAINS);

        assertNotNull(meeting);
        assertEquals(CLIENT, meeting.clientUuid());
        assertEquals(2, meeting.attendees().size());
    }

    @Test
    void addressesAreLowerCasedSoTheDomainJoinCannotMissOnCase() {
        var meeting = service.toMeeting(ME, event("evt-7", false, "2026-09-12T09:00:00", "2026-09-12T09:30:00",
                attendee("Mette.Kjaer@ACME.DK", "Mette Kjær")), DOMAINS);

        assertNotNull(meeting);
        assertEquals("mette.kjaer@acme.dk", meeting.attendees().get(0).email());
        assertEquals("acme.dk", meeting.attendees().get(0).domain());
    }

    /** An open-ended event still happened; a negative duration would be worse than zero. */
    @Test
    void anEventWithNoEndGetsZeroMinutesRatherThanANegativeDuration() {
        var meeting = service.toMeeting(ME, event("evt-8", false, "2026-09-12T09:00:00", null,
                attendee("mette@acme.dk", "Mette")), DOMAINS);

        assertNotNull(meeting);
        assertEquals(0, meeting.durationMinutes());
    }

    // ------------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------------

    /**
     * The uuid must be a function of (event, mailbox). A random one would make every
     * nightly run insert a fresh copy of every meeting it has already seen.
     */
    @Test
    void theSameEventAndMailboxAlwaysGetTheSameUuid() {
        String first = AccountCalendarSyncService.deterministicUuid("AAMkAGI2", ME);
        String second = AccountCalendarSyncService.deterministicUuid("AAMkAGI2", ME);
        assertEquals(first, second);
        assertEquals(36, first.length(), "the column is CHAR(36)");
        assertTrue(first.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    /** Two colleagues in one meeting are two rows — the graph needs both edges. */
    @Test
    void theSameEventInTwoMailboxesGetsTwoDifferentUuids() {
        assertNotEquals(
                AccountCalendarSyncService.deterministicUuid("AAMkAGI2", ME),
                AccountCalendarSyncService.deterministicUuid("AAMkAGI2", "22222222-2222-2222-2222-222222222222"));
    }

    // ------------------------------------------------------------------------
    // Graph's date format
    // ------------------------------------------------------------------------

    @Test
    void graphTimesParseWithAndWithoutFractionalSeconds() {
        assertEquals(LocalDateTime.of(2026, 9, 12, 9, 0),
                AccountCalendarSyncService.parseGraphTime(time("2026-09-12T09:00:00")));
        assertEquals(LocalDateTime.of(2026, 9, 12, 9, 0),
                AccountCalendarSyncService.parseGraphTime(time("2026-09-12T09:00:00.0000000")));
        assertNull(AccountCalendarSyncService.parseGraphTime(null));
        assertNull(AccountCalendarSyncService.parseGraphTime(time("")));
        assertNull(AccountCalendarSyncService.parseGraphTime(time("not a time")));
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static GraphCalendarClient.CalendarViewResponse.GraphDateTime time(String value) {
        return new GraphCalendarClient.CalendarViewResponse.GraphDateTime(value, "Europe/Copenhagen");
    }

    private static GraphCalendarClient.CalendarEventDetails.EventAttendee attendee(String email, String name) {
        return new GraphCalendarClient.CalendarEventDetails.EventAttendee(
                new GraphCalendarClient.CalendarEventRequest.Attendee.EmailAddress(email, name),
                "required",
                null);
    }

    private static GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event(
            String id, boolean cancelled, String start, String end,
            GraphCalendarClient.CalendarEventDetails.EventAttendee... attendees) {
        return new GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent(
                id,
                cancelled,
                start == null ? null : time(start),
                end == null ? null : time(end),
                attendees.length == 0 ? List.of() : List.of(attendees));
    }
}
