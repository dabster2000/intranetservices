package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Event enrichment + organizer hardening (interview scheduling plan
 * Phase 2): Teams fields, create-idempotency, the D3 privacy posture,
 * and the stored-organizer rule that fixes the interviewer-#1-removal
 * bug. Plain unit test — single-interviewer fixtures keep the event
 * builder off {@code User.findById}, so this runs in the DB-free tier
 * that gates deploys.
 */
class RecruitmentCalendarEventShapingTest {

    private RecruitmentCalendarService service;
    private GraphApiClient graph;

    @BeforeEach
    void setUp() {
        graph = mock(GraphApiClient.class);
        service = new RecruitmentCalendarService();
        service.graphApiClient = graph;
        service.calendarEnabled = true;
        service.configuredOrganizerValue = java.util.Optional.of("career@trustworks.dk");
    }

    @Test
    void create_teamsInterview_carriesTheFullEnrichment() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-1", true,
                        new GraphApiClient.CalendarEvent.OnlineMeeting(
                                "https://teams.microsoft.com/l/meetup-join/x")));
        RecruitmentInterview interview = interview();
        interview.setOnlineMeeting(true);

        Optional<RecruitmentCalendarService.CreatedEvent> created =
                service.createEvent(interview, candidateWithoutEmail(), null);

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(eq("career@trustworks.dk"), body.capture());
        GraphApiClient.CalendarEventRequest request = body.getValue();
        assertEquals(Boolean.TRUE, request.isOnlineMeeting());
        assertEquals("teamsForBusiness", request.onlineMeetingProvider());
        assertEquals(List.of("Recruitment"), request.categories());
        assertEquals(15, request.reminderMinutesBeforeStart());
        assertEquals("private", request.sensitivity(), "D3: private, informative subject kept");
        assertEquals("int-1", request.transactionId(),
                "create idempotency: the interview UUID, so a retried create never double-books");
        assertEquals(Boolean.TRUE, request.responseRequested());

        assertEquals("evt-1", created.orElseThrow().eventId());
        assertEquals("career@trustworks.dk", created.orElseThrow().organizer(),
                "the organizer used is handed back for persistence");
        assertEquals("https://teams.microsoft.com/l/meetup-join/x",
                created.orElseThrow().joinUrl());
    }

    @Test
    void create_plainInterview_sendsNoTeamsFields() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-2", null, null));

        Optional<RecruitmentCalendarService.CreatedEvent> created =
                service.createEvent(interview(), candidateWithoutEmail(), null);

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(anyString(), body.capture());
        assertNull(body.getValue().isOnlineMeeting(),
                "FALSE is never sent — absent keeps Graph's default");
        assertNull(body.getValue().onlineMeetingProvider());
        assertNull(created.orElseThrow().joinUrl());
    }

    @Test
    void update_addressesTheStoredOrganizer_evenWithConfigAndNewInterviewers() {
        // The interviewer-#1-removal bug: the event lives in the mailbox it
        // was CREATED under. Whatever the list looks like now, and whatever
        // the config says today, updates PATCH the stored organizer.
        when(graph.updateCalendarEvent(anyString(), anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-3", null, null));
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-3");
        interview.setGraphOrganizer("original.organizer@trustworks.dk");

        service.updateEvent(interview, candidateWithoutEmail(), null);

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).updateCalendarEvent(eq("original.organizer@trustworks.dk"),
                eq("evt-3"), body.capture());
        assertNull(body.getValue().transactionId(),
                "transactionId is create-only — Graph rejects it on PATCH");
        assertEquals("private", body.getValue().sensitivity(),
                "enrichment fields keep riding on PATCH");
    }

    @Test
    void update_capturesTheJoinUrl_whenTeamsWasJustEnabled() {
        when(graph.updateCalendarEvent(anyString(), anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-4", true,
                        new GraphApiClient.CalendarEvent.OnlineMeeting(
                                "https://teams.microsoft.com/l/meetup-join/y")));
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-4");
        interview.setGraphOrganizer("career@trustworks.dk");
        interview.setOnlineMeeting(true);

        Optional<String> joinUrl = service.updateEvent(interview, candidateWithoutEmail(), null);

        assertEquals(Optional.of("https://teams.microsoft.com/l/meetup-join/y"), joinUrl);
        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).updateCalendarEvent(anyString(), anyString(), body.capture());
        assertEquals(Boolean.TRUE, body.getValue().isOnlineMeeting(),
                "PATCHing Teams onto an existing event works in this tenant (Phase 0.3 spike)");
    }

    @Test
    void create_storedOrganizerWinsOverConfig() {
        // Resolution order is stored → config → first interviewer; a row
        // that somehow carries an organizer already keeps addressing it.
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-5", null, null));
        RecruitmentInterview interview = interview();
        interview.setGraphOrganizer("stored@trustworks.dk");

        service.createEvent(interview, candidateWithoutEmail(), null);

        verify(graph).createCalendarEvent(eq("stored@trustworks.dk"), any());
    }

    @Test
    void graphFailure_stillSwallowed_neverThrows() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenThrow(new RuntimeException("Graph 503"));
        assertTrue(service.createEvent(interview(), candidateWithoutEmail(), null).isEmpty());

        when(graph.updateCalendarEvent(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("Graph 503"));
        RecruitmentInterview synced = interview();
        synced.setGraphEventId("evt-6");
        synced.setGraphOrganizer("career@trustworks.dk");
        assertTrue(service.updateEvent(synced, candidateWithoutEmail(), null).isEmpty());
    }

    // ---- Two-event split (plan Phase 6) ----------------------------------------

    @Test
    void create_withCandidateEmail_splitsIntoInternalAndCandidateEvents() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-int", null, null))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-cand", null, null));
        RecruitmentPosition position = position();

        Optional<RecruitmentCalendarService.CreatedEvent> created =
                service.createEvent(interview(), candidate(), position);

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> bodies =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        org.mockito.Mockito.verify(graph, org.mockito.Mockito.times(2))
                .createCalendarEvent(eq("career@trustworks.dk"), bodies.capture());

        GraphApiClient.CalendarEventRequest internal = bodies.getAllValues().get(0);
        assertTrue(internal.attendees().stream()
                        .noneMatch(a -> "anna@example.com".equals(a.emailAddress().address())),
                "the candidate never rides on the internal event");
        assertTrue(internal.subject().contains("Consultant"),
                "interviewers-only surface may name the position again");
        assertTrue(internal.body().content().contains("Focus areas"),
                "internal note carries the kit pointer and focus areas");

        GraphApiClient.CalendarEventRequest candidateEvent = bodies.getAllValues().get(1);
        assertEquals(1, candidateEvent.attendees().size(), "candidate only");
        assertEquals("anna@example.com",
                candidateEvent.attendees().get(0).emailAddress().address());
        assertEquals("html", candidateEvent.body().contentType());
        assertTrue(candidateEvent.body().content().contains("Kære Anna"),
                "fallback body greets the candidate (template unreadable in unit tests)");
        assertNull(candidateEvent.isOnlineMeeting(),
                "the candidate event must never mint a SECOND Teams meeting");
        assertEquals("int-1-candidate", candidateEvent.transactionId());
        assertTrue(candidateEvent.subject().contains("Trustworks"));
        assertEquals("evt-cand", created.orElseThrow().candidateEventId());
    }

    @Test
    void legacyUpdate_keepsTheCandidateOnTheSingleEvent() {
        when(graph.updateCalendarEvent(anyString(), anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-old", null, null));
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-old");
        interview.setGraphOrganizer("career@trustworks.dk");

        service.updateEvent(interview, candidate(), position());

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).updateCalendarEvent(anyString(), eq("evt-old"), body.capture());
        assertTrue(body.getValue().attendees().stream()
                        .anyMatch(a -> "anna@example.com".equals(a.emailAddress().address())),
                "pre-split rows keep the candidate as attendee — nobody gets double-invited");
        assertTrue(!body.getValue().subject().contains("Consultant"),
                "candidate-visible surface keeps the position out of the subject");
    }

    @Test
    void splitUpdate_patchesBothEvents() {
        when(graph.updateCalendarEvent(anyString(), anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-int", null, null));
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-int");
        interview.setGraphCandidateEventId("evt-cand");
        interview.setGraphOrganizer("career@trustworks.dk");

        service.updateEvent(interview, candidate(), position());

        verify(graph).updateCalendarEvent(eq("career@trustworks.dk"), eq("evt-int"), any());
        verify(graph).updateCalendarEvent(eq("career@trustworks.dk"), eq("evt-cand"), any());
    }

    @Test
    void cancel_deletesBothEventsOnSplitRows() {
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-int");
        interview.setGraphCandidateEventId("evt-cand");
        interview.setGraphOrganizer("career@trustworks.dk");

        service.cancelEvent(interview);

        verify(graph).deleteCalendarEvent(eq("career@trustworks.dk"), eq("evt-int"));
        verify(graph).deleteCalendarEvent(eq("career@trustworks.dk"), eq("evt-cand"));
    }

    // ---- Candidate invitation rendering ----------------------------------------

    @Test
    void candidateInvitation_rendersTheTemplate_withInterviewMergeFields() {
        RecruitmentEmailTemplate template = new RecruitmentEmailTemplate();
        template.setSubject("Samtale hos Trustworks");
        template.setBody("<p>Kære {{candidate_first_name}}</p>"
                + "<p>{{interview_date}} kl. {{interview_time}} — {{interview_location}}</p>");
        template.setBodyFormat(RecruitmentEmailBodyFormat.HTML);

        RecruitmentCalendarService.CandidateInvitation invitation =
                RecruitmentCalendarService.candidateInvitation(
                        interviewWithLocation("HQ meeting room 2"), candidate(), position(),
                        null, template);

        assertEquals("Samtale hos Trustworks", invitation.subject());
        assertTrue(invitation.htmlBody().contains("Kære Anna"));
        assertTrue(invitation.htmlBody().contains("20/08/2026"));
        assertTrue(invitation.htmlBody().contains("10:00"));
        assertTrue(invitation.htmlBody().contains("HQ meeting room 2"));
    }

    @Test
    void candidateInvitation_appendsTheTeamsLink_andSanitizes() {
        RecruitmentEmailTemplate template = new RecruitmentEmailTemplate();
        template.setSubject("Samtale hos Trustworks");
        template.setBody("<p>Hej</p><script>alert(1)</script>");
        template.setBodyFormat(RecruitmentEmailBodyFormat.HTML);

        RecruitmentCalendarService.CandidateInvitation invitation =
                RecruitmentCalendarService.candidateInvitation(
                        interviewWithLocation(null), candidate(), position(),
                        "https://teams.microsoft.com/l/meetup-join/x", template);

        assertTrue(!invitation.htmlBody().contains("<script"),
                "the sanitizer is mandatory — stored HTML goes straight to Outlook");
        assertTrue(invitation.htmlBody().contains(
                "href=\"https://teams.microsoft.com/l/meetup-join/x\""));
        assertTrue(invitation.htmlBody().contains("Deltag i mødet"));
    }

    @Test
    void candidateInvitation_withoutTemplate_fallsBackToTheBuiltInDanishBody() {
        RecruitmentCalendarService.CandidateInvitation invitation =
                RecruitmentCalendarService.candidateInvitation(
                        interviewWithLocation(null), candidate(), position(), null, null);

        assertEquals("Samtale hos Trustworks", invitation.subject());
        assertTrue(invitation.htmlBody().contains("Kære Anna"));
        assertTrue(invitation.htmlBody().contains("<br>"), "plain fallback is HTML-ified");
    }

    @Test
    void internalBody_carriesPositionAndFocusAreas() {
        String body = RecruitmentCalendarService.internalBody(position());
        assertTrue(body.contains("Position: Consultant"));
        assertTrue(body.contains("Focus areas: Why consulting, Culture fit"));
        assertTrue(RecruitmentCalendarService.internalBody(null)
                .contains("Scheduled via the Trustworks intranet"));
    }

    // ---- Fixtures --------------------------------------------------------------

    /** One interviewer on purpose: the attendee loop starts at index 1, so
     * the builder never calls {@code User.findById} — DB-free. */
    private static RecruitmentInterview interview() {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid("int-1");
        interview.setKind(RecruitmentInterviewKind.ROUND);
        interview.setRound(1);
        interview.setScheduledAt(LocalDateTime.of(2026, 8, 20, 10, 0));
        interview.setDurationMinutes(60);
        interview.setInterviewerUuids(List.of("interviewer-1"));
        return interview;
    }

    private static RecruitmentCandidate candidate() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName("Anna");
        candidate.setLastName("Nielsen");
        candidate.setEmail("anna@example.com");
        return candidate;
    }

    /** Keeps the captors on the INTERNAL event: no email = no candidate
     * event (split coverage has its own tests). */
    private static RecruitmentCandidate candidateWithoutEmail() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName("Anna");
        candidate.setLastName("Nielsen");
        return candidate;
    }

    private static RecruitmentPosition position() {
        RecruitmentPosition position = new RecruitmentPosition();
        position.setTitle("Consultant");
        position.setScorecardTemplate(List.of(
                new dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute(
                        "WHY_CONSULTING", "Why consulting"),
                new dk.trustworks.intranet.recruitmentservice.model.ScorecardAttribute(
                        "CULTURE_FIT", "Culture fit")));
        return position;
    }

    private static RecruitmentInterview interviewWithLocation(String location) {
        RecruitmentInterview interview = interview();
        interview.setScheduledAt(LocalDateTime.of(2026, 8, 20, 10, 0));
        interview.setLocation(location);
        return interview;
    }
}
