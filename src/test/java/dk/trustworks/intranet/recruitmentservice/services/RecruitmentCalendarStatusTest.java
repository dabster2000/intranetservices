package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.CalendarStatusResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient.CalendarEventDetails;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient.CalendarEventDetails.EventAttendee;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient.CalendarEventRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RSVP + drift mapping (interview scheduling plan Phase 5): Graph
 * attendee responses → the UI's four states, and the wall-clock drift
 * check that catches events moved in Outlook behind the intranet's back.
 * Plain unit test in the DB-free tier that gates deploys.
 */
class RecruitmentCalendarStatusTest {

    private static final LocalDateTime SCHEDULED = LocalDateTime.of(2026, 8, 20, 10, 0);

    private RecruitmentCalendarService service;
    private GraphApiClient graph;

    @BeforeEach
    void setUp() {
        graph = mock(GraphApiClient.class);
        service = new RecruitmentCalendarService();
        service.graphApiClient = graph;
        service.calendarEnabled = true;
        service.configuredOrganizer = "career@trustworks.dk";
    }

    // ---- Pure mapping ----------------------------------------------------------

    @Test
    void mapsResponses_perParticipant_roomIgnored_caseInsensitive() {
        Map<String, String> interviewers = new LinkedHashMap<>();
        interviewers.put("uuid-a", "Anna.Alpha@trustworks.dk");
        interviewers.put("uuid-b", "bo.beta@trustworks.dk");
        CalendarEventDetails details = new CalendarEventDetails(
                new CalendarEventRequest.DateTimeTimeZone("2026-08-20T10:00:00.0000000", "Europe/Copenhagen"),
                List.of(
                        attendee("anna.alpha@trustworks.dk", "required", "accepted"),
                        attendee("BO.BETA@trustworks.dk", "required", "declined"),
                        attendee("candidate@example.com", "required", "tentativelyAccepted"),
                        attendee("room-hq2@trustworks.dk", "resource", "accepted"),
                        attendee("stranger@elsewhere.com", "required", "accepted")),
                null);

        CalendarStatusResponse status = RecruitmentCalendarService.calendarStatus(
                interviewers, "cand-1", "Candidate@Example.com", SCHEDULED, details);

        assertTrue(status.known());
        assertEquals(3, status.rsvps().size(), "room and strangers never surface");
        assertEquals(new CalendarStatusResponse.Rsvp("INTERVIEWER", "uuid-a", "ACCEPTED"),
                status.rsvps().get(0));
        assertEquals(new CalendarStatusResponse.Rsvp("INTERVIEWER", "uuid-b", "DECLINED"),
                status.rsvps().get(1));
        assertEquals(new CalendarStatusResponse.Rsvp("CANDIDATE", "cand-1", "TENTATIVE"),
                status.rsvps().get(2));
        assertFalse(status.drifted(), "same wall-clock minute, fraction ignored");
        assertEquals(SCHEDULED, status.outlookStart());
    }

    @Test
    void organizerAndNotRespondedNormalize_theWayTheUiReadsThem() {
        Map<String, String> interviewers = Map.of("uuid-a", "anna@trustworks.dk");
        CalendarEventDetails details = new CalendarEventDetails(null,
                List.of(attendee("anna@trustworks.dk", "required", "organizer")), null);

        CalendarStatusResponse organizer = RecruitmentCalendarService.calendarStatus(
                interviewers, null, null, SCHEDULED, details);
        assertEquals("ACCEPTED", organizer.rsvps().get(0).response(),
                "the organizer holds the meeting — that reads as accepted");

        CalendarEventDetails pending = new CalendarEventDetails(null,
                List.of(attendee("anna@trustworks.dk", "required", "notResponded")), null);
        assertEquals("NONE", RecruitmentCalendarService.calendarStatus(
                        interviewers, null, null, SCHEDULED, pending)
                .rsvps().get(0).response());
    }

    @Test
    void movedInOutlook_flagsDrift_withTheOutlookTime() {
        CalendarEventDetails details = new CalendarEventDetails(
                new CalendarEventRequest.DateTimeTimeZone("2026-08-21T13:30:00.0000000", "Europe/Copenhagen"),
                List.of(), null);

        CalendarStatusResponse status = RecruitmentCalendarService.calendarStatus(
                Map.of(), null, null, SCHEDULED, details);

        assertTrue(status.drifted());
        assertEquals(LocalDateTime.of(2026, 8, 21, 13, 30), status.outlookStart());
    }

    @Test
    void missingPieces_neverThrow() {
        CalendarStatusResponse status = RecruitmentCalendarService.calendarStatus(
                Map.of(), null, null, SCHEDULED,
                new CalendarEventDetails(null, null, null));

        assertTrue(status.known());
        assertTrue(status.rsvps().isEmpty());
        assertFalse(status.drifted());
        assertNull(status.outlookStart());
    }

    // ---- Service edges ---------------------------------------------------------

    @Test
    void toggleOffOrUnsynced_returnsUnknown_neverTouchesGraph() {
        RecruitmentInterview unsynced = interview();
        assertFalse(service.eventStatus(unsynced, candidate()).known(),
                "no graph_event_id = nothing to read");

        service.calendarEnabled = false;
        RecruitmentInterview synced = interview();
        synced.setGraphEventId("evt-1");
        assertFalse(service.eventStatus(synced, candidate()).known());
        verifyNoInteractions(graph);
    }

    @Test
    void graphFailure_returnsUnknown_neverThrows() {
        when(graph.getCalendarEventDetails(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("Graph 503"));
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-1");
        interview.setGraphOrganizer("career@trustworks.dk");

        CalendarStatusResponse status = service.eventStatus(interview, candidate());

        assertFalse(status.known());
        assertTrue(status.rsvps().isEmpty());
    }

    @Test
    void readsTheEvent_underTheStoredOrganizer() {
        when(graph.getCalendarEventDetails(anyString(), anyString(), any()))
                .thenReturn(new CalendarEventDetails(
                        new CalendarEventRequest.DateTimeTimeZone("2026-08-20T10:00:00.0000000",
                                "Europe/Copenhagen"),
                        List.of(attendee("anna@example.com", "required", "accepted")),
                        null));
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-1");
        interview.setGraphOrganizer("original.organizer@trustworks.dk");

        CalendarStatusResponse status = service.eventStatus(interview, candidate());

        assertTrue(status.known());
        assertEquals(1, status.rsvps().size(), "the candidate's RSVP maps by email");
        assertEquals("CANDIDATE", status.rsvps().get(0).participantType());
        org.mockito.Mockito.verify(graph).getCalendarEventDetails(
                org.mockito.ArgumentMatchers.eq("original.organizer@trustworks.dk"),
                org.mockito.ArgumentMatchers.eq("evt-1"), anyString());
    }

    // ---- Fixtures --------------------------------------------------------------

    private static EventAttendee attendee(String email, String type, String response) {
        return new EventAttendee(
                new CalendarEventRequest.Attendee.EmailAddress(email, null),
                type, new EventAttendee.AttendeeStatus(response));
    }

    /** No interviewers on purpose — email resolution would need the DB. */
    private static RecruitmentInterview interview() {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid("int-1");
        interview.setKind(RecruitmentInterviewKind.ROUND);
        interview.setRound(1);
        interview.setScheduledAt(SCHEDULED);
        interview.setDurationMinutes(60);
        interview.setInterviewerUuids(List.of());
        return interview;
    }

    private static RecruitmentCandidate candidate() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName("Anna");
        candidate.setLastName("Andersen");
        candidate.setEmail("anna@example.com");
        return candidate;
    }
}
