package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 *   <li>toggle ON: event created in the FIRST interviewer's mailbox with
 *       the co-interviewers + candidate as attendees; update/cancel
 *       propagate;</li>
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
        Optional<String> id = QuarkusTransaction.requiringNew().call(() ->
                service.createEvent(interview(), candidate(), position()));
        assertTrue(id.isEmpty());
        verifyNoInteractions(graph);
    }

    @Test
    void toggleOn_createsInFirstInterviewersMailbox_withCandidateAttendee() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-123"));

        Optional<String> id = QuarkusTransaction.requiringNew().call(() ->
                service.createEvent(interview(), candidate(), position()));

        assertEquals(Optional.of("evt-123"), id);
        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(eq(interviewerA + "@example.com"), body.capture());
        List<String> attendeeEmails = body.getValue().attendees().stream()
                .map(a -> a.emailAddress().address())
                .toList();
        assertTrue(attendeeEmails.contains(interviewerB + "@example.com"),
                "co-interviewer invited");
        assertTrue(attendeeEmails.contains("candidate@example.com"),
                "candidate invited as external attendee");
        assertTrue(body.getValue().subject().contains("Interview 1"));
    }

    @Test
    void toggleOn_updateAndCancel_propagate() {
        service.calendarEnabled = true;
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-123");

        QuarkusTransaction.requiringNew().run(() ->
                service.updateEvent(interview, candidate(), position()));
        verify(graph).updateCalendarEvent(eq(interviewerA + "@example.com"), eq("evt-123"), any());

        QuarkusTransaction.requiringNew().run(() -> service.cancelEvent(interview));
        verify(graph).deleteCalendarEvent(interviewerA + "@example.com", "evt-123");
    }

    @Test
    void toggleOn_withoutStoredEventId_updateAndCancelAreNoOps() {
        service.calendarEnabled = true;
        RecruitmentInterview interview = interview(); // graphEventId null
        QuarkusTransaction.requiringNew().run(() -> {
            service.updateEvent(interview, candidate(), position());
            service.cancelEvent(interview);
        });
        verify(graph, never()).updateCalendarEvent(anyString(), anyString(), any());
        verify(graph, never()).deleteCalendarEvent(anyString(), anyString());
    }

    @Test
    void eventTimes_wallClockCopenhagen_summerDate() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-tz-summer"));

        // Aug 1 = CEST. The wall-clock string must pass through untouched,
        // stamped Europe/Copenhagen — never "UTC" (that shifted every event
        // by the UTC offset in Outlook).
        QuarkusTransaction.requiringNew().run(() ->
                service.createEvent(interview(), candidate(), position()));

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(anyString(), body.capture());
        assertEquals("2026-08-01T10:00", body.getValue().start().dateTime());
        assertEquals("Europe/Copenhagen", body.getValue().start().timeZone());
        assertEquals("2026-08-01T11:00", body.getValue().end().dateTime());
        assertEquals("Europe/Copenhagen", body.getValue().end().timeZone());
    }

    @Test
    void eventTimes_wallClockCopenhagen_winterDate() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-tz-winter"));

        // Jan 15 = CET. Same IANA id year-round: Graph resolves CET vs CEST
        // from the event date, so DST needs no handling on our side.
        RecruitmentInterview interview = interview();
        interview.setScheduledAt(LocalDateTime.of(2027, 1, 15, 14, 0));
        QuarkusTransaction.requiringNew().run(() ->
                service.createEvent(interview, candidate(), position()));

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(anyString(), body.capture());
        assertEquals("2027-01-15T14:00", body.getValue().start().dateTime());
        assertEquals("Europe/Copenhagen", body.getValue().start().timeZone());
        assertEquals("2027-01-15T15:00", body.getValue().end().dateTime());
        assertEquals("Europe/Copenhagen", body.getValue().end().timeZone());
    }

    @Test
    void roomEmail_invitedAsResourceAttendee_peopleStayRequired() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-room"));

        RecruitmentInterview interview = interview();
        interview.setRoomEmail("room-hq2@trustworks.dk");
        QuarkusTransaction.requiringNew().run(() ->
                service.createEvent(interview, candidate(), position()));

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(anyString(), body.capture());
        List<GraphApiClient.CalendarEventRequest.Attendee> attendees =
                body.getValue().attendees();
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
                .thenReturn(new GraphApiClient.CalendarEvent("evt-no-room"));

        QuarkusTransaction.requiringNew().run(() ->
                service.createEvent(interview(), candidate(), position()));

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(anyString(), body.capture());
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
        when(graph.listRooms()).thenReturn(new GraphApiClient.RoomCollectionResponse(List.of(
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
        when(graph.listRooms())
                .thenThrow(new RuntimeException("Graph 403: missing Place.Read.All"));
        assertTrue(service.listRooms().isEmpty());
    }

    @Test
    void rooms_withoutStart_noFreeBusyLookup_availableNull() {
        service.calendarEnabled = true;
        when(graph.listRooms()).thenReturn(new GraphApiClient.RoomCollectionResponse(List.of(
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
        when(graph.listRooms()).thenReturn(new GraphApiClient.RoomCollectionResponse(List.of(
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-1", "HQ meeting room 2", "room-hq2@trustworks.dk", 8, "HQ"),
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-2", "HQ meeting room 3", "room-hq3@trustworks.dk", 4, "HQ"))));
        when(graph.getSchedule(anyString(), any()))
                .thenReturn(new GraphApiClient.ScheduleCollectionResponse(List.of(
                        new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation(
                                "room-hq2@trustworks.dk", "0"),
                        new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation(
                                "room-hq3@trustworks.dk", "2"))));

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
        when(graph.listRooms()).thenReturn(new GraphApiClient.RoomCollectionResponse(List.of(
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-1", "HQ meeting room 2", "room-hq2@trustworks.dk", 8, "HQ"))));
        when(graph.getSchedule(anyString(), any()))
                .thenThrow(new RuntimeException("Graph 503"));

        var rooms = service.listRooms(LocalDateTime.of(2026, 8, 1, 10, 0));

        assertEquals(1, rooms.size(), "a broken free/busy lookup must not hide rooms");
        assertEquals(null, rooms.get(0).available());
    }

    @Test
    void graphFailure_isSwallowed_schedulingNeverBreaks() {
        service.calendarEnabled = true;
        when(graph.createCalendarEvent(anyString(), any()))
                .thenThrow(new RuntimeException("Graph 403: missing Calendars.ReadWrite"));

        Optional<String> id = QuarkusTransaction.requiringNew().call(() ->
                service.createEvent(interview(), candidate(), position()));
        assertTrue(id.isEmpty(), "a Graph failure yields empty, never an exception");
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

    private RecruitmentPosition position() {
        RecruitmentPosition position = Mockito.mock(RecruitmentPosition.class);
        when(position.getTitle()).thenReturn("Consultant");
        return position;
    }
}
