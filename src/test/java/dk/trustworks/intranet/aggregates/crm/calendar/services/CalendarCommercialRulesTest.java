package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.graph.GraphCalendarClient.AttendeeViewResponse;
import dk.trustworks.intranet.graph.GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent;
import dk.trustworks.intranet.graph.GraphCalendarClient.CalendarEventDetails.EventAttendee;
import dk.trustworks.intranet.graph.GraphCalendarClient.CalendarViewResponse.GraphDateTime;
import dk.trustworks.intranet.graph.GraphCalendarClient.CalendarEventRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Regression fixtures model the audited cases without committing real calendar identities. */
class CalendarCommercialRulesTest {
    private final AccountCalendarSyncService service = new AccountCalendarSyncService();
    private static final Map<String, String> DOMAINS = Map.of("client.example", "client", "other.example", "other");

    @Test
    void currentCommercialRoleAndActualStarredParticipantAreBothRequiredAcrossAllGates() {
        int cases = 0;
        for (boolean commercial : List.of(false, true))
            for (boolean starred : List.of(false, true))
                for (boolean recurring : List.of(false, true))
                    for (boolean delivery : List.of(false, true))
                        for (String guard : List.of("none", "private", "confidential", "personal", "cancelled",
                                "ownerDeclined", "attendeeDeclined", "resource", "internal", "mass")) {
                            var people = new ArrayList<EventAttendee>();
                            people.add(person("alex@client.example", guard.equals("resource") ? "resource" : "required",
                                    guard.equals("attendeeDeclined") ? "declined" : "accepted"));
                            if (guard.equals("internal")) for (int i = 0; i < 8; i++) people.add(person("own" + i + "@trustworks.dk"));
                            if (guard.equals("mass")) for (int i = 0; i < 9; i++) people.add(person("guest" + i + "@client.example"));
                            var event = event(recurring, guard, people);
                            var filters = filters(commercial, starred, delivery);
                            var tally = new CalendarSyncTally();
                            var result = service.toMeetings("owner", event, DOMAINS, filters, tally, 8, 10);
                            boolean admitted = guard.equals("none") && (!(recurring || delivery) || commercial && starred);
                            boolean activityOnly = guard.equals("mass") && !recurring && !delivery;
                            assertEquals(admitted || activityOnly, !result.isEmpty(),
                                    "commercial=" + commercial + " star=" + starred + " recurring=" + recurring
                                            + " delivery=" + delivery + " guard=" + guard);
                            if (admitted) {
                                assertEquals(1, result.getFirst().attendees().size());
                                assertEquals(recurring, result.getFirst().recurring());
                                assertEquals(commercial && starred && (recurring || delivery) ? "STARRED_OVERRIDE" : "NORMAL",
                                        result.getFirst().inclusionReason());
                            }
                            if (activityOnly) assertTrue(result.getFirst().attendees().isEmpty());
                            if (!guard.equals("none")) assertTrue(tally.candidates().isEmpty());
                            cases++;
                        }
        assertEquals(160, cases);
    }

    @Test
    void aStarOnOneAccountCannotRescueAnotherAccountsRecurringProjection() {
        var event = event(true, "none", List.of(person("alex@client.example"), person("one@other.example"), person("two@other.example")));
        var tally = new CalendarSyncTally();
        var meetings = service.toMeetings("owner", event, DOMAINS, filters(true, true, false), tally, 8, 10);
        assertEquals(1, meetings.size());
        assertEquals("client", meetings.getFirst().clientUuid(), "the starred minority delegation must survive");
        assertEquals("other", tally.candidates().getFirst().clientUuid());
        var reversed = event(true, "none", List.of(person("two@other.example"), person("one@other.example"), person("alex@client.example")));
        assertEquals(meetings, service.toMeetings("owner", reversed, DOMAINS, filters(true, true, false), new CalendarSyncTally(), 8, 10));
    }

    @Test
    void normalMultiAccountMeetingsKeepBothAccountsWithStableDistinctProjectionIds() {
        var event = event(false, "none", List.of(person("alex@client.example"), person("one@other.example")));
        var meetings = service.toMeetings("owner", event, DOMAINS, CalendarFilters.empty(), new CalendarSyncTally(), 8, 10);
        assertEquals(Set.of("client", "other"), meetings.stream().map(AccountCalendarSyncService.PendingMeeting::clientUuid).collect(java.util.stream.Collectors.toSet()));
        assertNotEquals(meetings.get(0).uuid(), meetings.get(1).uuid());
        assertEquals(meetings.get(0).icalUid(), meetings.get(1).icalUid());
    }

    @Test
    void duplicateAddressesCannotTripThresholdsAndDeclineVetoWinsRegardlessOfOrder() {
        var people = new ArrayList<EventAttendee>();
        for (int i = 0; i < 20; i++) { people.add(person("alex@client.example")); people.add(person("same@trustworks.dk")); }
        var meetings = service.toMeetings("owner", event(false, "none", people), DOMAINS, CalendarFilters.empty(), new CalendarSyncTally(), 8, 10);
        assertEquals(1, meetings.getFirst().attendees().size());
        assertEquals(1, meetings.getFirst().ownAttendeeCount());
        assertEquals(2, meetings.getFirst().attendeeCount());
        people.add(person("ALEX@client.example", "required", "declined"));
        assertTrue(service.toMeetings("owner", event(false, "none", people), DOMAINS, CalendarFilters.empty(), new CalendarSyncTally(), 8, 10).isEmpty());
    }

    @Test
    void organizerIsAnActualParticipantAndDeduplicatesAgainstInvitation() {
        var base = event(false, "none", List.of(person("own@trustworks.dk")));
        var event = new AttendeeViewEvent(base.id(), base.iCalUId(), base.type(), base.seriesMasterId(), false,
                base.start(), base.end(), base.responseStatus(), base.attendees(), "normal",
                new AttendeeViewResponse.EventOrganizer(new CalendarEventRequest.Attendee.EmailAddress("alex@client.example", "Organizer")));
        var meetings = service.toMeetings("owner", event, DOMAINS, CalendarFilters.empty(), new CalendarSyncTally(), 8, 10);
        assertEquals("alex@client.example", meetings.getFirst().attendees().getFirst().email());
    }

    @Test
    void functionalAddressesGoToReviewWithoutBecomingPeopleOrStarredOverrides() {
        for (String email : List.of("sg.it@client.example", "noreply-meeting-booking@client.example", "support@client.example")) {
            var tally = new CalendarSyncTally();
            var filters = CalendarFilters.empty().withCommercialStars(Set.of("owner"), Map.of("client", Set.of(email)));
            assertTrue(service.toMeetings("owner", event(true, "none", List.of(person(email))), DOMAINS, filters, tally, 8, 10).isEmpty());
            assertEquals("SHARED_ADDRESS", tally.candidates().getFirst().reason());
        }
        assertFalse(CalendarSharedAddressFilter.isShared("it.larsen@client.example"));
    }

    @Test
    void recurringAndDeliveryReviewCandidatesDoNotCreateRelationshipsBeforeReview() {
        var tally = new CalendarSyncTally();
        assertTrue(service.toMeetings("owner", event(true, "none", List.of(person("alex@client.example"))),
                DOMAINS, filters(true, false, true), tally, 8, 10).isEmpty());
        assertEquals(1, tally.candidates().size());
        assertEquals("RECURRING", tally.candidates().getFirst().reason());
    }

    @Test
    void missingSensitivityAndSeriesMastersCannotBecomeMeetingsEvenWithTheOverride() {
        var base = event(true, "none", List.of(person("alex@client.example")));
        var unknown = new AttendeeViewEvent(base.id(), base.iCalUId(), base.type(), base.seriesMasterId(), false,
                base.start(), base.end(), base.responseStatus(), base.attendees(), null, null);
        var master = new AttendeeViewEvent(base.id(), base.iCalUId(), "seriesMaster", base.seriesMasterId(), false,
                base.start(), base.end(), base.responseStatus(), base.attendees(), "normal", null);
        for (var item : List.of(unknown, master)) {
            var tally = new CalendarSyncTally();
            assertTrue(service.toMeetings("owner", item, DOMAINS, filters(true, true, true), tally, 8, 10).isEmpty());
            assertTrue(tally.candidates().isEmpty());
        }
    }

    @Test
    void aSkippedMailboxReportsIncompleteSoARecoveryCannotClaimSuccess() {
        assertEquals(1, AccountCalendarSyncService.MailboxResult.incomplete().readsTruncated());
        assertEquals(0, AccountCalendarSyncService.MailboxResult.incomplete().meetingsKept());
    }

    private static CalendarFilters filters(boolean commercial, boolean star, boolean delivery) {
        var index = delivery ? DeliveryContractIndex.of(List.of(new DeliveryContractIndex.DeliveryContractRow(
                "client", "owner", LocalDate.of(2025, 1, 1), LocalDate.of(2027, 1, 1)))) : DeliveryContractIndex.empty();
        return CalendarFilters.of(index, ColleagueDirectory.empty(), Map.of()).withCommercialStars(
                commercial ? Set.of("owner") : Set.of(), star ? Map.of("client", Set.of("ALEX@CLIENT.EXAMPLE")) : Map.of());
    }

    private static AttendeeViewEvent event(boolean recurring, String guard, List<EventAttendee> attendees) {
        return new AttendeeViewEvent("event", "ical-occurrence", recurring ? "occurrence" : "singleInstance",
                recurring ? "series" : null, guard.equals("cancelled"),
                new GraphDateTime("2026-09-01T09:00:00", "Europe/Copenhagen"),
                new GraphDateTime("2026-09-01T10:00:00", "Europe/Copenhagen"),
                new AttendeeViewResponse.EventResponseStatus(guard.equals("ownerDeclined") ? "declined" : "accepted"),
                attendees, Set.of("private", "personal", "confidential").contains(guard) ? guard : "normal", null);
    }
    private static EventAttendee person(String email) { return person(email, "required", "accepted"); }
    private static EventAttendee person(String email, String type, String response) {
        return new EventAttendee(new CalendarEventRequest.Attendee.EmailAddress(email, "External Person"), type,
                new EventAttendee.AttendeeStatus(response));
    }
}
