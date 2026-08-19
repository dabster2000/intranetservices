package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P11 DoD (manual-mode alternative): the Graph calendar bridge is
 * test-covered behind its config flag —
 * {@code dk.trustworks.recruitment.graph.calendar.enabled}:
 * <ul>
 *   <li>toggle OFF (the shipped default): no Graph call ever, empty
 *       result — manual scheduling untouched;</li>
 *   <li>toggle ON, NO configured organizer (the pre-V492 fallback): event
 *       created in the FIRST interviewer's mailbox, so they are the
 *       organizer and only the co-interviewers are attendees;</li>
 *   <li>toggle ON WITH the configured shared mailbox — what production
 *       actually runs: EVERY interviewer is an attendee. Until this case
 *       existed, the suite's only multi-interviewer test pinned the legacy
 *       fallback, the one configuration in which excluding interviewer[0]
 *       is correct, so the V492 attendee drop passed every gate;</li>
 *   <li>Graph failure: swallowed — scheduling never fails on calendar
 *       trouble.</li>
 * </ul>
 * The service instance is constructed by hand so the flag can be set per
 * test; user lookups run against real fixture rows.
 */
@QuarkusTest
class RecruitmentCalendarServiceTest {

    @Inject
    EntityManager em;

    private String interviewerA;
    private String interviewerB;
    private String candidateUuid;

    private RecruitmentCalendarService service;
    private GraphApiClient graph;

    @BeforeEach
    void seed() {
        interviewerA = UUID.randomUUID().toString();
        interviewerB = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, interviewerA, "Ida", "Interviewer");
            P8ProfileFixtures.insertUser(em, interviewerB, "Ib", "Interviewer");
        });
        graph = mock(GraphApiClient.class);
        service = new RecruitmentCalendarService();
        service.graphApiClient = graph;
        // Hand-constructed: no CDI ran, so every @Inject field is null. The
        // limiter is dereferenced on the first line of every free/busy probe,
        // so leaving it out NPEs the whole availability half of this class.
        service.mailboxLimiter =
                new dk.trustworks.intranet.sharepoint.client.GraphMailboxConcurrencyLimiter(2, 50);
        service.sleeper = millis -> { };
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("DELETE FROM user WHERE uuid IN :u")
                        .setParameter("u", List.of(interviewerA, interviewerB))
                        .executeUpdate());
    }

    @Test
    void toggleOff_neverTouchesGraph() {
        service.calendarEnabled = false;
        Optional<RecruitmentCalendarService.CreatedEvent> created = QuarkusTransaction.requiringNew().call(() ->
                service.createEvent(interview(), candidate(), null));
        assertTrue(created.isEmpty());
        verifyNoInteractions(graph);
    }

    @Test
    void configuredSharedOrganizer_invitesEveryInterviewer_includingTheFirst() {
        // The PRODUCTION configuration. career@trustworks.dk is on nobody's
        // roster, so no interviewer is the organizer and none may be skipped.
        // Before the identity-based exclusion landed, interviewerA was dropped
        // here and a single-interviewer interview invited nobody at all.
        service.calendarEnabled = true;
        service.configuredOrganizerValue = Optional.of("career@trustworks.dk");
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-shared", null, null));

        QuarkusTransaction.requiringNew().call(() ->
                service.createEvent(interview(), candidate(), null));

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph, times(2)).createCalendarEvent(eq("career@trustworks.dk"), body.capture());
        List<String> attendeeEmails = body.getAllValues().get(0).attendees().stream()
                .map(a -> a.emailAddress().address())
                .toList();
        assertEquals(List.of(interviewerA + "@example.com", interviewerB + "@example.com"),
                attendeeEmails,
                "both interviewers, in roster order — the drop was interviewerA going missing");
    }

    @Test
    void toggleOn_createsInFirstInterviewersMailbox_withCandidateAttendee() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-123", null, null));

        Optional<RecruitmentCalendarService.CreatedEvent> created = QuarkusTransaction.requiringNew().call(() ->
                service.createEvent(interview(), candidate(), null));

        assertEquals("evt-123", created.orElseThrow().eventId());
        // Phase 6 split: two creates in the same mailbox (no shared-organizer
        // config in tests) — [0] internal, [1] the candidate's own event.
        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph, times(2)).createCalendarEvent(eq(interviewerA + "@example.com"), body.capture());
        GraphApiClient.CalendarEventRequest internal = body.getAllValues().get(0);
        List<String> attendeeEmails = internal.attendees().stream()
                .map(a -> a.emailAddress().address())
                .toList();
        assertTrue(attendeeEmails.contains(interviewerB + "@example.com"),
                "co-interviewer invited");
        assertTrue(!attendeeEmails.contains(interviewerA + "@example.com"),
                "interviewerA hosts the event here, so they are the organizer — never also an attendee");
        assertTrue(!attendeeEmails.contains("candidate@example.com"),
                "the candidate rides on their OWN event, never the internal one");
        assertEquals("Interview 1: Kim Kandidat", internal.subject());
        // Named, not address-only: an external mail client cannot resolve
        // a Trustworks address against our directory.
        assertEquals("Ib Interviewer", internal.attendees().stream()
                .filter(a -> a.emailAddress().address().equals(interviewerB + "@example.com"))
                .findFirst().orElseThrow().emailAddress().name());
        GraphApiClient.CalendarEventRequest candidateEvent = body.getAllValues().get(1);
        assertEquals(List.of("candidate@example.com"), candidateEvent.attendees().stream()
                .map(a -> a.emailAddress().address()).toList());
        assertEquals("html", candidateEvent.body().contentType());
    }

    @Test
    void toggleOn_updateAndCancel_propagate() {
        service.calendarEnabled = true;
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-123");

        QuarkusTransaction.requiringNew().run(() ->
                service.updateEvent(interview, candidate(), null));
        verify(graph).updateCalendarEvent(eq(interviewerA + "@example.com"), eq("evt-123"), any());

        QuarkusTransaction.requiringNew().run(() -> service.cancelEvent(interview));
        verify(graph).deleteCalendarEvent(interviewerA + "@example.com", "evt-123");
    }

    @Test
    void toggleOn_withoutStoredEventId_updateAndCancelAreNoOps() {
        service.calendarEnabled = true;
        RecruitmentInterview interview = interview(); // graphEventId null
        QuarkusTransaction.requiringNew().run(() -> {
            service.updateEvent(interview, candidate(), null);
            service.cancelEvent(interview);
        });
        verify(graph, never()).updateCalendarEvent(anyString(), anyString(), any());
        verify(graph, never()).deleteCalendarEvent(anyString(), anyString());
    }

    @Test
    void eventTimes_wallClockCopenhagen_summerDate() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-tz-summer", null, null));

        // Aug 1 = CEST. The wall-clock string must pass through untouched,
        // stamped Europe/Copenhagen — never "UTC" (that shifted every event
        // by the UTC offset in Outlook).
        QuarkusTransaction.requiringNew().run(() ->
                service.createEvent(interview(), candidate(), null));

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph, times(2)).createCalendarEvent(anyString(), body.capture());
        assertEquals("2026-08-01T10:00", body.getValue().start().dateTime());
        assertEquals("Europe/Copenhagen", body.getValue().start().timeZone());
        assertEquals("2026-08-01T11:00", body.getValue().end().dateTime());
        assertEquals("Europe/Copenhagen", body.getValue().end().timeZone());
    }

    @Test
    void eventTimes_wallClockCopenhagen_winterDate() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-tz-winter", null, null));

        // Jan 15 = CET. Same IANA id year-round: Graph resolves CET vs CEST
        // from the event date, so DST needs no handling on our side.
        RecruitmentInterview interview = interview();
        interview.setScheduledAt(LocalDateTime.of(2027, 1, 15, 14, 0));
        QuarkusTransaction.requiringNew().run(() ->
                service.createEvent(interview, candidate(), null));

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph, times(2)).createCalendarEvent(anyString(), body.capture());
        assertEquals("2027-01-15T14:00", body.getValue().start().dateTime());
        assertEquals("Europe/Copenhagen", body.getValue().start().timeZone());
        assertEquals("2027-01-15T15:00", body.getValue().end().dateTime());
        assertEquals("Europe/Copenhagen", body.getValue().end().timeZone());
    }

    @Test
    void roomEmail_invitedAsResourceAttendee_peopleStayRequired() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-room", null, null));

        RecruitmentInterview interview = interview();
        interview.setRoomEmail("room-hq2@trustworks.dk");
        QuarkusTransaction.requiringNew().run(() ->
                service.createEvent(interview, candidate(), null));

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph, times(2)).createCalendarEvent(anyString(), body.capture());
        // The room books through the INTERNAL event (index 0) — the
        // candidate event never carries the resource attendee.
        List<GraphApiClient.CalendarEventRequest.Attendee> attendees =
                body.getAllValues().get(0).attendees();
        List<GraphApiClient.CalendarEventRequest.Attendee> resources = attendees.stream()
                .filter(a -> "resource".equals(a.type()))
                .toList();
        assertEquals(1, resources.size(), "the room mailbox is the one resource attendee");
        assertEquals("room-hq2@trustworks.dk", resources.get(0).emailAddress().address());
        assertEquals("HQ meeting room 2", resources.get(0).emailAddress().name(),
                "the room label rides along as the attendee display name");
        assertTrue(attendees.stream()
                        .filter(a -> !"resource".equals(a.type()))
                        .allMatch(a -> "required".equals(a.type())),
                "people are still plain required attendees");
    }

    @Test
    void withoutRoomEmail_noResourceAttendee() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-no-room", null, null));

        QuarkusTransaction.requiringNew().run(() ->
                service.createEvent(interview(), candidate(), null));

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph, times(2)).createCalendarEvent(anyString(), body.capture());
        assertTrue(body.getValue().attendees().stream()
                .noneMatch(a -> "resource".equals(a.type())));
    }

    // ---- Rooms lookup ----------------------------------------------------------

    @Test
    void rooms_toggleOff_returnsEmpty_neverTouchesGraph() {
        service.calendarEnabled = false;
        assertTrue(service.listRooms().isEmpty());
        verifyNoInteractions(graph);
    }

    @Test
    void rooms_toggleOn_mapsRooms_skippingRoomsWithoutMailbox() {
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any())).thenReturn(new GraphApiClient.RoomCollectionResponse(List.of(
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-1", "HQ meeting room 2", "room-hq2@trustworks.dk", 8, "HQ"),
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-2", "Unbookable corner", null, null, null))));

        var rooms = service.listRooms();

        assertEquals(1, rooms.size(), "a room without a mailbox cannot be booked — dropped");
        assertEquals("HQ meeting room 2", rooms.get(0).displayName());
        assertEquals("room-hq2@trustworks.dk", rooms.get(0).emailAddress());
        assertEquals(8, rooms.get(0).capacity());
        assertEquals("HQ", rooms.get(0).building());
    }

    @Test
    void rooms_graphFailure_returnsEmpty_neverThrows() {
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any()))
                .thenThrow(new RuntimeException("Graph 403: missing Place.Read.All"));
        assertTrue(service.listRooms().isEmpty());
    }

    @Test
    void rooms_withoutStart_noFreeBusyLookup_availableNull() {
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any())).thenReturn(new GraphApiClient.RoomCollectionResponse(List.of(
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-1", "HQ meeting room 2", "room-hq2@trustworks.dk", 8, "HQ"))));

        var rooms = service.listRooms(null);

        assertEquals(1, rooms.size());
        assertEquals(null, rooms.get(0).available());
        verify(graph, never()).getSchedule(anyString(), any());
    }

    @Test
    void rooms_withStart_marksFreeAndBusy_fromOneGetScheduleCall() {
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any())).thenReturn(new GraphApiClient.RoomCollectionResponse(List.of(
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-1", "HQ meeting room 2", "room-hq2@trustworks.dk", 8, "HQ"),
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-2", "HQ meeting room 3", "room-hq3@trustworks.dk", 4, "HQ"))));
        when(graph.getSchedule(anyString(), any()))
                .thenReturn(new GraphApiClient.ScheduleCollectionResponse(List.of(
                        new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation(
                                "room-hq2@trustworks.dk", "0", null),
                        new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation(
                                "room-hq3@trustworks.dk", "2", null))));

        var rooms = service.listRooms(LocalDateTime.of(2026, 8, 1, 10, 0));

        assertEquals(2, rooms.size());
        assertEquals(Boolean.TRUE, rooms.get(0).available(), "all-zero view = free");
        assertEquals(Boolean.FALSE, rooms.get(1).available(), "non-zero digit = busy");

        // One call for both rooms, wall-clock Copenhagen 60-minute window.
        ArgumentCaptor<GraphApiClient.ScheduleRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.ScheduleRequest.class);
        verify(graph).getSchedule(eq("room-hq2@trustworks.dk"), body.capture());
        assertEquals(List.of("room-hq2@trustworks.dk", "room-hq3@trustworks.dk"),
                body.getValue().schedules());
        assertEquals("2026-08-01T10:00", body.getValue().startTime().dateTime());
        assertEquals("Europe/Copenhagen", body.getValue().startTime().timeZone());
        assertEquals("2026-08-01T11:00", body.getValue().endTime().dateTime());
    }

    @Test
    void rooms_withStart_freeBusyFailure_roomsStillReturned_availableNull() {
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any())).thenReturn(new GraphApiClient.RoomCollectionResponse(List.of(
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-1", "HQ meeting room 2", "room-hq2@trustworks.dk", 8, "HQ"))));
        when(graph.getSchedule(anyString(), any()))
                .thenThrow(new RuntimeException("Graph 503"));

        var rooms = service.listRooms(LocalDateTime.of(2026, 8, 1, 10, 0));

        assertEquals(1, rooms.size(), "a broken free/busy lookup must not hide rooms");
        assertEquals(null, rooms.get(0).available());
    }

    @Test
    void event_usesTheInterviewsOwnDuration() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-90", null, null));

        RecruitmentInterview interview = interview();
        interview.setDurationMinutes(90);
        QuarkusTransaction.requiringNew().run(() ->
                service.createEvent(interview, candidate(), null));

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph, times(2)).createCalendarEvent(anyString(), body.capture());
        assertEquals("2026-08-01T10:00", body.getValue().start().dateTime());
        assertEquals("2026-08-01T11:30", body.getValue().end().dateTime(),
                "the Outlook event end follows duration_minutes, not a fixed hour");
    }

    @Test
    void rooms_withStartAndDuration_freeBusyWindowMatchesDuration() {
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any())).thenReturn(new GraphApiClient.RoomCollectionResponse(List.of(
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-1", "HQ meeting room 2", "room-hq2@trustworks.dk", 8, "HQ"))));
        when(graph.getSchedule(anyString(), any()))
                .thenReturn(new GraphApiClient.ScheduleCollectionResponse(List.of(
                        new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation(
                                "room-hq2@trustworks.dk", "0", null))));

        service.listRooms(LocalDateTime.of(2026, 8, 1, 10, 0), 120);

        ArgumentCaptor<GraphApiClient.ScheduleRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.ScheduleRequest.class);
        verify(graph).getSchedule(anyString(), body.capture());
        assertEquals("2026-08-01T12:00", body.getValue().endTime().dateTime(),
                "the probed window is the picked duration, not a fixed hour");
    }

    // ---- Interviewer availability ----------------------------------------------

    @Test
    void interviewerAvailability_toggleOff_returnsEmpty_neverTouchesGraph() {
        service.calendarEnabled = false;
        assertTrue(service.interviewerAvailability(
                List.of("a@example.com"), LocalDateTime.of(2026, 8, 1, 10, 0), 60)
                .freeByMailbox().isEmpty());
        verifyNoInteractions(graph);
    }

    @Test
    void interviewerAvailability_strictRule_anyNonFreeDigitIsBusy() {
        service.calendarEnabled = true;
        when(graph.getSchedule(anyString(), any()))
                .thenReturn(new GraphApiClient.ScheduleCollectionResponse(List.of(
                        new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation(
                                "free@example.com", "0", null),
                        new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation(
                                "tentative@example.com", "1", null),
                        new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation(
                                "busy@example.com", "2", null))));

        var result = service.interviewerAvailability(
                List.of("free@example.com", "tentative@example.com", "busy@example.com"),
                LocalDateTime.of(2026, 8, 1, 10, 0), 90).freeByMailbox();

        assertEquals(Boolean.TRUE, result.get("free@example.com"));
        assertEquals(Boolean.FALSE, result.get("tentative@example.com"),
                "tentative counts as busy — same strict rule as rooms");
        assertEquals(Boolean.FALSE, result.get("busy@example.com"));

        ArgumentCaptor<GraphApiClient.ScheduleRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.ScheduleRequest.class);
        verify(graph).getSchedule(eq("free@example.com"), body.capture());
        assertEquals("2026-08-01T11:30", body.getValue().endTime().dateTime(),
                "the probed window follows the picked duration");
    }

    @Test
    void interviewerAvailability_graphFailure_returnsEmpty_neverThrows() {
        service.calendarEnabled = true;
        when(graph.getSchedule(anyString(), any()))
                .thenThrow(new RuntimeException("Graph 503"));
        var result = service.interviewerAvailability(
                List.of("a@example.com"), LocalDateTime.of(2026, 8, 1, 10, 0), 60);
        assertTrue(result.freeByMailbox().isEmpty());
        assertFalse(result.complete(), "a failed lookup reports itself as unread");
    }

    @Test
    void graphFailure_isSwallowed_schedulingNeverBreaks() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenThrow(new RuntimeException("Graph 403: missing Calendars.ReadWrite"));

        Optional<RecruitmentCalendarService.CreatedEvent> created = QuarkusTransaction.requiringNew().call(() ->
                service.createEvent(interview(), candidate(), null));
        assertTrue(created.isEmpty(), "a Graph failure yields empty, never an exception");
    }

    // ---- Fixtures --------------------------------------------------------------

    private RecruitmentInterview interview() {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid(UUID.randomUUID().toString());
        interview.setApplicationUuid(UUID.randomUUID().toString());
        interview.setKind(RecruitmentInterviewKind.ROUND);
        interview.setRound(1);
        interview.setScheduledAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        interview.setInterviewerUuids(List.of(interviewerA, interviewerB));
        interview.setLocation("HQ meeting room 2");
        interview.setStatus(RecruitmentInterviewStatus.SCHEDULED);
        return interview;
    }

    private RecruitmentCandidate candidate() {
        RecruitmentCandidate candidate = Mockito.mock(RecruitmentCandidate.class);
        when(candidate.getUuid()).thenReturn(candidateUuid);
        when(candidate.getFirstName()).thenReturn("Kim");
        when(candidate.getLastName()).thenReturn("Kandidat");
        when(candidate.getEmail()).thenReturn("candidate@example.com");
        return candidate;
    }
}
