package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.graph.GraphCalendarClient;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>It is also where the four keep-rules of decisions D1-D3 are pinned. Those rules throw
 * away roughly two thirds of what Graph returns on production, so every one of them is a
 * rule that can silently delete real client relationships if it is written a shade too
 * wide — the former-colleague case and the "Marianne Hansen" case below are the two that
 * would have done exactly that.
 *
 * <p>Fast tier — no Quarkus boot, no Graph, no database.
 */
class AccountCalendarSyncServiceTest {

    private static final String ME = "11111111-1111-1111-1111-111111111111";
    private static final String MALTHE = "33333333-3333-3333-3333-333333333333";
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
        var meeting = toMeeting(event("evt-1", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mette@acme.dk", "Mette Kjær"),
                attendee("hans@trustworks.dk", "Hans Lassen")));

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
        assertNull(toMeeting(event("evt-2", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("hans@trustworks.dk", "Hans"),
                attendee("someone@unknown.dk", "Someone"))));
    }

    /** A cancelled meeting did not happen; counting it would overstate the relationship. */
    @Test
    void cancelledEventsAreDropped() {
        assertNull(toMeeting(event("evt-3", true, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mette@acme.dk", "Mette"))));
    }

    @Test
    void anEventWithNoStartIsDropped() {
        assertNull(toMeeting(event("evt-4", false, null, "2026-09-12T10:00:00",
                attendee("mette@acme.dk", "Mette"))));
    }

    @Test
    void anEventWithNoAttendeesAtAllIsDropped() {
        assertNull(toMeeting(event("evt-5", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00")));
    }

    /**
     * Two clients in one room. Splitting the meeting would double-count it on both
     * accounts; dropping it would lose it. The bigger delegation wins.
     */
    @Test
    void aMeetingWithTwoClientsGoesToTheOneWithMorePeopleInIt() {
        var meeting = toMeeting(event("evt-6", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("a@acme.dk", "A"),
                attendee("b@acme.dk", "B"),
                attendee("c@beta.dk", "C")));

        assertNotNull(meeting);
        assertEquals(CLIENT, meeting.clientUuid());
        assertEquals(2, meeting.attendees().size());
    }

    @Test
    void addressesAreLowerCasedSoTheDomainJoinCannotMissOnCase() {
        var meeting = toMeeting(event("evt-7", false, "2026-09-12T09:00:00", "2026-09-12T09:30:00",
                attendee("Mette.Kjaer@ACME.DK", "Mette Kjær")));

        assertNotNull(meeting);
        assertEquals("mette.kjaer@acme.dk", meeting.attendees().get(0).email());
        assertEquals("acme.dk", meeting.attendees().get(0).domain());
    }

    /** An open-ended event still happened; a negative duration would be worse than zero. */
    @Test
    void anEventWithNoEndGetsZeroMinutesRatherThanANegativeDuration() {
        var meeting = toMeeting(event("evt-8", false, "2026-09-12T09:00:00", null,
                attendee("mette@acme.dk", "Mette")));

        assertNotNull(meeting);
        assertEquals(0, meeting.durationMinutes());
    }

    // ------------------------------------------------------------------------
    // D1 — delivery is not sales
    // ------------------------------------------------------------------------

    /**
     * The filter that removes 630 of 960 production meetings. A consultant placed at the
     * client has a standup with them every morning; those are not sales contact and they
     * made "who last saw them" answer with a standup.
     */
    @Test
    void aMeetingIsDroppedWhenTheMailboxOwnerWasOnAContractWithThatClientThatDay() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = filtersWithContract(LocalDate.of(2026, 1, 1), null);

        assertNull(service.toMeeting(ME, event("evt-9", false, "2026-09-12T09:00:00", "2026-09-12T09:15:00",
                attendee("mette@acme.dk", "Mette Kjær")), DOMAINS, filters, tally));
        assertEquals(1, tally.deliveryDroppedCount(),
                "the run has to be able to say WHY it kept nothing — meetings=0 alone cannot");
    }

    /**
     * The date window is the whole rule. A meeting held while we were still SELLING the
     * assignment is exactly the meeting the account page exists to show, and a filter that
     * looked only at "is this person placed there now" would delete it.
     */
    @Test
    void aMeetingBeforeTheContractStartedIsKept() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = filtersWithContract(LocalDate.of(2026, 10, 1), null);

        var meeting = service.toMeeting(ME, event("evt-10", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mette@acme.dk", "Mette Kjær")), DOMAINS, filters, tally);

        assertNotNull(meeting, "the assignment started three weeks after this meeting");
        assertEquals(0, tally.deliveryDroppedCount());
    }

    /** Delivery at one client says nothing about a meeting with a different one. */
    @Test
    void deliveryAtOneClientDoesNotFilterAMeetingWithAnother() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = CalendarFilters.of(
                DeliveryContractIndex.of(List.of(new DeliveryContractIndex.DeliveryContractRow(
                        OTHER_CLIENT, ME, LocalDate.of(2020, 1, 1), null))),
                ColleagueDirectory.empty(),
                Map.of());

        assertNotNull(service.toMeeting(ME, event("evt-11", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mette@acme.dk", "Mette Kjær")), DOMAINS, filters, tally));
    }

    // ------------------------------------------------------------------------
    // D2 — our own consultants at the client are not client contacts
    // ------------------------------------------------------------------------

    /**
     * {@code mygx@novonordisk.com} is Malthe. Before this filter he was written into
     * {@code account_meeting_attendee} as an external person the firm had met and drawn in
     * the relationship graph as the firm's network into its own account.
     */
    @Test
    void aColleagueWithAClientMailboxIsNotStoredAsAClientContact() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = filtersWithColleague(StatusType.ACTIVE, LocalDate.of(2020, 1, 1));

        var meeting = service.toMeeting(ME, event("evt-12", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mygx@acme.dk", "MYGX (Malthe Yde Andreasen)"),
                attendee("mette@acme.dk", "Mette Kjær")), DOMAINS, filters, tally);

        assertNotNull(meeting);
        assertEquals(1, meeting.attendees().size(), "only the real client person is stored");
        assertEquals("mette@acme.dk", meeting.attendees().get(0).email());
        assertEquals(2, meeting.attendeeCount(), "he was still in the room — attendeeCount counts people");
    }

    /**
     * The case that makes employment part of the rule rather than an optimisation. Malthe
     * left Trustworks in January and works at the client now, so by September he is one of
     * the best client contacts the firm has. A filter that matched on the name alone would
     * delete him.
     */
    @Test
    void aFormerColleagueNowWorkingAtTheClientIsKeptAsAClientContact() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = filtersWithColleague(StatusType.TERMINATED, LocalDate.of(2026, 1, 31));

        var meeting = service.toMeeting(ME, event("evt-13", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mygx@acme.dk", "Malthe Yde Andreasen")), DOMAINS, filters, tally);

        assertNotNull(meeting, "he had left — this is a genuine client relationship");
        assertEquals(1, meeting.attendees().size());
        assertEquals("mygx@acme.dk", meeting.attendees().get(0).email());
        assertFalse(filters.isColleagueEmailOn("mygx@acme.dk", LocalDate.of(2026, 9, 12)),
                "and his address must not be learned as a colleague's either");
    }

    /**
     * Graph omits the display name for some mailboxes: the same address arrives named in
     * one answer and bare in another. A bare address can never be name-matched, so the
     * learned table is the only thing standing between Malthe and a second permanent
     * appearance on the account as "mygx@acme.dk".
     */
    @Test
    void anAddressAlreadyKnownToBeAColleaguesIsDroppedEvenWithNoNameAtAll() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = CalendarFilters.of(
                DeliveryContractIndex.empty(),
                ColleagueDirectory.of(List.of(new ColleagueDirectory.ColleagueRow(
                        MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, LocalDate.of(2020, 1, 1)))),
                Map.of("mygx@acme.dk", MALTHE));

        var meeting = service.toMeeting(ME, event("evt-14", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mygx@acme.dk", null),
                attendee("mette@acme.dk", "Mette Kjær")), DOMAINS, filters, tally);

        assertNotNull(meeting);
        assertEquals(1, meeting.attendees().size());
        assertEquals("mette@acme.dk", meeting.attendees().get(0).email());
    }

    /**
     * The address rule asks the SAME employment question the name rule asks, and this is
     * the test that makes it.
     *
     * <p>A learned address is a fact about WHOSE mailbox it is, not about when. Left
     * date-blind it quietly overrules the employment test: the row is written while the
     * person is employed, and from then on every meeting that address ever appears on is
     * dropped — including the ones held after they left, which are exactly the meetings the
     * employment rule exists to keep. Production already holds the shape:
     * {@code trustworks-mh@aeldresagen.dk} belongs to a colleague who left on 2026-04-01,
     * the address was named on 28 attendee rows while he was employed, and it still appears
     * on a meeting dated 2026-06-04.
     */
    @Test
    void aLearnedAddressStopsFilteringOnceItsOwnerHasLeft() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = CalendarFilters.of(
                DeliveryContractIndex.empty(),
                ColleagueDirectory.of(List.of(
                        new ColleagueDirectory.ColleagueRow(
                                MALTHE, "Malthe", "Yde Andreasen", StatusType.ACTIVE, LocalDate.of(2020, 1, 1)),
                        new ColleagueDirectory.ColleagueRow(
                                MALTHE, "Malthe", "Yde Andreasen", StatusType.TERMINATED, LocalDate.of(2026, 4, 1)))),
                Map.of("mygx@acme.dk", MALTHE));

        // While he was ours: dropped, name or no name.
        assertNull(service.toMeeting(ME, event("evt-14b", false, "2026-03-02T09:00:00", "2026-03-02T10:00:00",
                attendee("mygx@acme.dk", null)), DOMAINS, filters, tally));

        // After he left: a client contact, and the best kind — somebody who knows us.
        var after = service.toMeeting(ME, event("evt-14c", false, "2026-06-04T09:00:00", "2026-06-04T10:00:00",
                attendee("mygx@acme.dk", null)), DOMAINS, filters, tally);

        assertNotNull(after, "he had left by June — the address must stop filtering with him");
        assertEquals(1, after.attendees().size());
        assertEquals("mygx@acme.dk", after.attendees().get(0).email());
    }

    /**
     * An address whose owner is not in the directory at all — a hand-written {@code MANUAL}
     * row, or a user row deleted since — cannot be shown to have been employed, so the
     * attendee is KEPT. The failure that matters here is deleting a real client contact.
     */
    @Test
    void aLearnedAddressWhoseOwnerIsUnknownDoesNotFilter() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = CalendarFilters.of(
                DeliveryContractIndex.empty(), ColleagueDirectory.empty(),
                Map.of("mygx@acme.dk", MALTHE));

        var meeting = service.toMeeting(ME, event("evt-14d", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mygx@acme.dk", null)), DOMAINS, filters, tally);

        assertNotNull(meeting);
        assertEquals(1, meeting.attendees().size());
    }

    /**
     * The self-learning half. A run that sees the name once writes the address down, and
     * from that moment on — in this same run, and in every later one — the bare address is
     * recognised.
     */
    @Test
    void aNameMatchTeachesTheAddressToTheRestOfTheRun() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = filtersWithColleague(StatusType.ACTIVE, LocalDate.of(2020, 1, 1));

        service.toMeeting(ME, event("evt-15", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mygx@acme.dk", "MYGX (Malthe Yde Andreasen)"),
                attendee("mette@acme.dk", "Mette Kjær")), DOMAINS, filters, tally);

        assertTrue(filters.isColleagueEmailOn("mygx@acme.dk", LocalDate.of(2026, 9, 12)));
        assertEquals(1, tally.newlyLearnedEmails());
        var learned = List.copyOf(tally.learnedEmails());
        assertEquals(1, learned.size());
        assertEquals("mygx@acme.dk", learned.get(0).email());
        assertEquals(MALTHE, learned.get(0).userUuid(), "the row has to say WHOSE address it is");
    }

    /**
     * Two of our own consultants comparing notes at the client site is not a meeting with
     * the client. 39 such meetings on production.
     */
    @Test
    void aMeetingWhoseOnlyClientDomainAttendeesAreColleaguesIsDroppedEntirely() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = filtersWithColleague(StatusType.ACTIVE, LocalDate.of(2020, 1, 1));

        assertNull(service.toMeeting(ME, event("evt-16", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mygx@acme.dk", "MYGX (Malthe Yde Andreasen)"),
                attendee("hans@trustworks.dk", "Hans Lassen")), DOMAINS, filters, tally));
        assertEquals(1, tally.colleagueOnlyDroppedCount());
        assertTrue(filters.isColleagueEmailOn("mygx@acme.dk", LocalDate.of(2026, 9, 12)),
                "a dropped meeting still teaches the address — the drop IS the evidence");
    }

    /**
     * The bug a {@code String.contains()} would have shipped. "Anne Hansen" is a substring
     * of "Marianne Hansen", so a naive match would have deleted a real client contact from
     * the account's relationship graph and left no trace that it had.
     */
    @Test
    void anAttendeeWhoseNameMerelyContainsAColleaguesNameIsKept() {
        CalendarSyncTally tally = new CalendarSyncTally();
        CalendarFilters filters = CalendarFilters.of(
                DeliveryContractIndex.empty(),
                ColleagueDirectory.of(List.of(new ColleagueDirectory.ColleagueRow(
                        "44444444-4444-4444-4444-444444444444", "Anne", "Hansen",
                        StatusType.ACTIVE, LocalDate.of(2020, 1, 1)))),
                Map.of());

        var meeting = service.toMeeting(ME, event("evt-17", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("marianne@acme.dk", "Marianne Hansen")), DOMAINS, filters, tally);

        assertNotNull(meeting, "Marianne Hansen is not Anne Hansen");
        assertEquals(1, meeting.attendees().size());
        assertEquals("marianne@acme.dk", meeting.attendees().get(0).email());
    }

    // ------------------------------------------------------------------------
    // D3 — meeting rooms are not people
    // ------------------------------------------------------------------------

    /**
     * {@code "KIT-LLV-Modelokale-2@politi.dk"} is a room. It was being drawn in the
     * relationship graph as somebody the firm knows at Rigspolitiet, and it was inflating
     * {@code attendeeCount}, which the account page reads as the size of the room in people.
     */
    @Test
    void aRoomResourceIsNeitherStoredNorCountedAsAPerson() {
        var meeting = toMeeting(event("evt-18", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("mette@acme.dk", "Mette Kjær"),
                resource("kit-llv-modelokale-2@acme.dk", "KIT-LLV-Modelokale-2")));

        assertNotNull(meeting);
        assertEquals(1, meeting.attendees().size(), "a room is not a client contact");
        assertEquals("mette@acme.dk", meeting.attendees().get(0).email());
        assertEquals(1, meeting.attendeeCount(), "attendeeCount is the size of the room in PEOPLE");
    }

    /** An event that is nothing but a room booking has nobody in it and is not a meeting. */
    @Test
    void anEventWithOnlyARoomOnAClientDomainIsDropped() {
        assertNull(toMeeting(event("evt-19", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                resource("kit-llv-modelokale-2@acme.dk", "KIT-LLV-Modelokale-2"))));
    }

    /**
     * Decided explicitly: politi.dk never sends display names, and dropping attendees we
     * know only by address would empty Rigspolitiet's whole relationship graph.
     */
    @Test
    void anAttendeeKnownOnlyByAnAddressIsStillAPersonAndIsKept() {
        var meeting = toMeeting(event("evt-20", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                attendee("abcd@acme.dk", null)));

        assertNotNull(meeting);
        assertEquals(1, meeting.attendees().size());
        assertEquals("abcd@acme.dk", meeting.attendees().get(0).email());
        assertNull(meeting.attendees().get(0).displayName());
    }

    /** Graph omits the type on events not created in Outlook; that is a normal attendee. */
    @Test
    void anAttendeeWithNoTypeAtAllIsANormalAttendee() {
        var meeting = toMeeting(event("evt-21", false, "2026-09-12T09:00:00", "2026-09-12T10:00:00",
                typedAttendee("mette@acme.dk", "Mette Kjær", null)));

        assertNotNull(meeting);
        assertEquals(1, meeting.attendees().size());
        assertEquals(1, meeting.attendeeCount());
    }

    @Test
    void theResourceTypeIsMatchedWithoutRegardToCase() {
        assertTrue(AccountCalendarSyncService.isResourceAttendee(
                typedAttendee("room@acme.dk", "Room", "Resource")));
        assertFalse(AccountCalendarSyncService.isResourceAttendee(
                typedAttendee("mette@acme.dk", "Mette", "required")));
        assertFalse(AccountCalendarSyncService.isResourceAttendee(null));
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

    /** The ordinary case: no contracts, no colleagues, no learned addresses. */
    private AccountCalendarSyncService.PendingMeeting toMeeting(
            GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event) {
        return service.toMeeting(ME, event, DOMAINS, CalendarFilters.empty(), new CalendarSyncTally());
    }

    /** The mailbox owner on an assignment at {@link #CLIENT} over the given window. */
    private static CalendarFilters filtersWithContract(LocalDate activeFrom, LocalDate activeTo) {
        return CalendarFilters.of(
                DeliveryContractIndex.of(List.of(
                        new DeliveryContractIndex.DeliveryContractRow(CLIENT, ME, activeFrom, activeTo))),
                ColleagueDirectory.empty(),
                Map.of());
    }

    /** Malthe Yde Andreasen, with one status row — ACTIVE or TERMINATED — from that date. */
    private static CalendarFilters filtersWithColleague(StatusType status, LocalDate statusDate) {
        return CalendarFilters.of(
                DeliveryContractIndex.empty(),
                ColleagueDirectory.of(List.of(new ColleagueDirectory.ColleagueRow(
                        MALTHE, "Malthe", "Yde Andreasen", status, statusDate))),
                Map.of());
    }

    private static GraphCalendarClient.CalendarViewResponse.GraphDateTime time(String value) {
        return new GraphCalendarClient.CalendarViewResponse.GraphDateTime(value, "Europe/Copenhagen");
    }

    private static GraphCalendarClient.CalendarEventDetails.EventAttendee attendee(String email, String name) {
        return typedAttendee(email, name, "required");
    }

    /** A meeting room or a projector, as Graph marks them. */
    private static GraphCalendarClient.CalendarEventDetails.EventAttendee resource(String email, String name) {
        return typedAttendee(email, name, "resource");
    }

    private static GraphCalendarClient.CalendarEventDetails.EventAttendee typedAttendee(
            String email, String name, String type) {
        return new GraphCalendarClient.CalendarEventDetails.EventAttendee(
                new GraphCalendarClient.CalendarEventRequest.Attendee.EmailAddress(email, name),
                type,
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
