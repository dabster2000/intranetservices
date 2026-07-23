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
