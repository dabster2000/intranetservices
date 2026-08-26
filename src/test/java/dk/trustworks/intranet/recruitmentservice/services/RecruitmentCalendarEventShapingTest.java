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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * bug. Plain unit test that runs in the DB-free tier gating deploys.
 * <p>
 * The attendee list is reachable here because {@code interviewerResolver}
 * is stubbed in {@link #setUp()}. It used to be unreachable: the fixtures
 * carried ONE interviewer precisely so the builder never called
 * {@code User.findById} (which throws outside Quarkus), which meant the
 * gate could not express a multi-interviewer expectation at all — and
 * that is how the V492 attendee drop reached production unnoticed. Never
 * reintroduce a fixture whose single interviewer is load-bearing.
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
        // The DB seam: uuid -> mailbox, so the attendee list is assertable
        // without Panache. "interviewer-1" becomes interviewer-1@trustworks.dk.
        service.interviewerResolver = uuid ->
                new RecruitmentCalendarService.Interviewer(
                        uuid + "@trustworks.dk", "Name " + uuid);
    }

    @Test
    void create_teamsInterview_carriesTheFullEnrichment() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-1", true,
                        new GraphApiClient.CalendarEvent.OnlineMeeting(
                                "https://teams.microsoft.com/l/meetup-join/x")));
        RecruitmentInterview interview = interview();
        interview.setOnlineMeeting(true);

        RecruitmentCalendarService.CreateResult created =
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

        assertEquals("evt-1", created.created().eventId());
        assertEquals("career@trustworks.dk", created.created().organizer(),
                "the organizer used is handed back for persistence");
        assertEquals("https://teams.microsoft.com/l/meetup-join/x",
                created.created().joinUrl());
    }

    @Test
    void create_offerMeeting_namesTheMeetingInsteadOfAnUnnumberedRound() {
        // The offer meeting carries no round. Before the OFFER kind
        // existed, every subject was "INFORMAL ? uformel snak : Interview
        // %d" — a third kind falls into the round branch and renders
        // "Interview null: Anna Nielsen" on the candidate's own calendar.
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-offer", null, null));
        RecruitmentInterview interview = interview();
        interview.setKind(RecruitmentInterviewKind.OFFER);
        interview.setRound(null);

        service.createEvent(interview, candidateWithoutEmail(), null);

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(anyString(), body.capture());
        String subject = body.getValue().subject();
        assertTrue(subject.startsWith("Samtale: "),
                "the offer meeting gets its own subject, got: " + subject);
        assertTrue(!subject.contains("null"), "no round number to render: " + subject);
        assertTrue(!subject.contains("Uformel"),
                "an offer meeting is not the uformel snak: " + subject);
    }

    @Test
    void create_plainInterview_sendsNoTeamsFields() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-2", null, null));

        RecruitmentCalendarService.CreateResult created =
                service.createEvent(interview(), candidateWithoutEmail(), null);

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(anyString(), body.capture());
        assertNull(body.getValue().isOnlineMeeting(),
                "FALSE is never sent — absent keeps Graph's default");
        assertNull(body.getValue().onlineMeetingProvider());
        assertNull(created.created().joinUrl());
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

        RecruitmentCalendarService.UpdateResult update =
                service.updateEvent(interview, candidateWithoutEmail(), null);

        assertEquals("https://teams.microsoft.com/l/meetup-join/y", update.joinUrl());
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
        RecruitmentCalendarService.CreateResult failed =
                service.createEvent(interview(), candidateWithoutEmail(), null);
        assertNull(failed.created(), "a Graph failure yields no event, never an exception");
        assertTrue(failed.internalFailure() != null,
                "…but the failure is now CLASSIFIED for the caller, not swallowed");

        when(graph.updateCalendarEvent(anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("Graph 503"));
        RecruitmentInterview synced = interview();
        synced.setGraphEventId("evt-6");
        synced.setGraphOrganizer("career@trustworks.dk");
        assertNull(service.updateEvent(synced, candidateWithoutEmail(), null).joinUrl());
    }

    // ---- Two-event split (plan Phase 6) ----------------------------------------

    @Test
    void create_withCandidateEmail_splitsIntoInternalAndCandidateEvents() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-int", null, null))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-cand", null, null));
        RecruitmentPosition position = position();

        RecruitmentCalendarService.CreateResult created =
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
        assertEquals("evt-cand", created.created().candidateEventId());
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
        String body = RecruitmentCalendarService.internalBody(
                position(), candidate(), "career@trustworks.dk");
        assertTrue(body.contains("Position: Consultant"));
        assertTrue(body.contains("Focus areas: Why consulting, Culture fit"));
        assertTrue(RecruitmentCalendarService
                .internalBody(null, candidate(), "career@trustworks.dk")
                .contains("Scheduled via the Trustworks intranet"));
    }

    // ---- The candidate note: the split is invisible in Outlook ---------------
    //
    // Interviewers open this event, see themselves and a room, and conclude
    // nobody invited the candidate. Reported in production 2026-08-22 on an
    // interview whose candidate had in fact already ACCEPTED — on her own
    // event, whose RSVP goes to the shared organizer mailbox and never to the
    // interviewer. The note is the only place inside Outlook that says so.

    @Test
    void internalBody_saysWhereTheCandidateWentAndWhoGetsTheAnswer() {
        String body = RecruitmentCalendarService.internalBody(
                position(), candidate(), "career@trustworks.dk");

        assertTrue(body.contains("anna@example.com"), "names the invited address");
        assertTrue(body.contains("separate event"));
        assertTrue(body.contains("career@trustworks.dk"), "names the mailbox the RSVP lands in");
        assertTrue(body.contains("Interviews tab"), "points at the surface that shows the answer");
    }

    @Test
    void internalBody_noCandidateEmail_saysNoInvitationWasSent() {
        String body = RecruitmentCalendarService.internalBody(
                position(), candidateWithoutEmail(), "career@trustworks.dk");

        assertTrue(body.contains("NO email address"));
        assertTrue(body.contains("no invitation was sent"));
        assertFalse(body.contains("separate event"),
                "must not claim a candidate event that cannot exist without an address");
    }

    @Test
    void candidateInvitationNote_unknownOrganizer_dropsTheMailboxClause() {
        String note = RecruitmentCalendarService.candidateInvitationNote(candidate(), null);

        assertTrue(note.contains("anna@example.com"));
        assertFalse(note.contains("RSVP replies go to"),
                "never name a mailbox we could not resolve");
        assertTrue(note.contains("Interviews tab"));
    }

    @Test
    void candidateInvitationNote_blankEmail_takesTheNotSentBranch() {
        RecruitmentCandidate blank = candidate();
        blank.setEmail("   ");

        assertTrue(RecruitmentCalendarService.candidateInvitationNote(blank, "career@trustworks.dk")
                .contains("no invitation was sent"));
        assertTrue(RecruitmentCalendarService.candidateInvitationNote(null, "career@trustworks.dk")
                .contains("no invitation was sent"));
    }

    // ---- Attendee list: organizer exclusion is by identity, not position -------
    //
    // Regression pins for the V492 drop. buildInternalEvent used to skip
    // interviewerUuids[0] unconditionally, which was right only while the
    // organizer WAS the first interviewer. Once V492 pointed new events at the
    // shared career@trustworks.dk mailbox, that skip silently un-invited a real
    // person — and invited nobody at all when one interviewer was assigned.

    @Test
    void attendees_sharedOrganizer_invitesEveryInterviewer() {
        List<GraphApiClient.CalendarEventRequest.Attendee> attendees =
                RecruitmentCalendarService.interviewerAttendees(
                        List.of(new RecruitmentCalendarService.Interviewer("a@trustworks.dk", "A"),
                                new RecruitmentCalendarService.Interviewer("b@trustworks.dk", "B")),
                        "career@trustworks.dk");

        assertEquals(List.of("a@trustworks.dk", "b@trustworks.dk"),
                attendees.stream().map(a -> a.emailAddress().address()).toList(),
                "the organizer is nobody on the roster, so NOBODY is excluded");
        assertTrue(attendees.stream().allMatch(a -> "required".equals(a.type())));
        assertEquals("A", attendees.get(0).emailAddress().name(),
                "name rides along so external clients do not render a raw address");
    }

    @Test
    void attendees_singleInterviewer_isStillInvited() {
        assertEquals(List.of("solo@trustworks.dk"),
                RecruitmentCalendarService.interviewerAttendees(
                                List.of(new RecruitmentCalendarService.Interviewer(
                                        "solo@trustworks.dk", "Solo")),
                                "career@trustworks.dk").stream()
                        .map(a -> a.emailAddress().address()).toList(),
                "the one-interviewer case used to produce an event with no human attendee");
    }

    @Test
    void attendees_legacyOrganizerIsAnInterviewer_excludesExactlyThem() {
        List<GraphApiClient.CalendarEventRequest.Attendee> attendees =
                RecruitmentCalendarService.interviewerAttendees(
                        List.of(new RecruitmentCalendarService.Interviewer("a@trustworks.dk", "A"),
                                new RecruitmentCalendarService.Interviewer("b@trustworks.dk", "B")),
                        "a@trustworks.dk");

        assertEquals(List.of("b@trustworks.dk"),
                attendees.stream().map(a -> a.emailAddress().address()).toList(),
                "pre-V492 rows store an interviewer as organizer — they must not be double-invited");
    }

    @Test
    void attendees_excludesTheOrganizerWhereverTheySitOnTheRoster() {
        assertEquals(List.of("a@trustworks.dk", "c@trustworks.dk"),
                RecruitmentCalendarService.interviewerAttendees(
                                List.of(new RecruitmentCalendarService.Interviewer("a@trustworks.dk", "A"),
                                        new RecruitmentCalendarService.Interviewer("b@trustworks.dk", "B"),
                                        new RecruitmentCalendarService.Interviewer("c@trustworks.dk", "C")),
                                "b@trustworks.dk").stream()
                        .map(a -> a.emailAddress().address()).toList(),
                "identity, not index: interviewer[0] is invited and the middle one is dropped");
    }

    @Test
    void attendees_organizerMatchIsCaseAndWhitespaceInsensitive() {
        assertTrue(RecruitmentCalendarService.interviewerAttendees(
                        List.of(new RecruitmentCalendarService.Interviewer("A@Trustworks.DK", "A")),
                        "  a@trustworks.dk  ").isEmpty(),
                "Outlook mailboxes are case-insensitive; a casing difference must not double-invite");
    }

    @Test
    void attendees_noOrganizer_invitesEveryone() {
        assertEquals(1, RecruitmentCalendarService.interviewerAttendees(
                        List.of(new RecruitmentCalendarService.Interviewer("a@trustworks.dk", "A")),
                        null).size());
    }

    @Test
    void attendees_unresolvableMailboxesAreSkippedWithoutLosingTheRest() {
        assertEquals(List.of("b@trustworks.dk"),
                RecruitmentCalendarService.interviewerAttendees(
                                java.util.Arrays.asList(
                                        null,
                                        new RecruitmentCalendarService.Interviewer(null, "No mailbox"),
                                        new RecruitmentCalendarService.Interviewer("  ", "Blank"),
                                        new RecruitmentCalendarService.Interviewer("b@trustworks.dk", "B")),
                                "career@trustworks.dk").stream()
                        .map(a -> a.emailAddress().address()).toList());
        assertTrue(RecruitmentCalendarService.interviewerAttendees(null, "career@trustworks.dk").isEmpty());
        assertTrue(RecruitmentCalendarService.interviewerAttendees(List.of(), "career@trustworks.dk").isEmpty());
    }

    @Test
    void create_twoInterviewers_bothRideOnTheInternalEvent() {
        // The end-to-end shape of the reported bug: two people picked, one booked.
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-7", null, null));
        RecruitmentInterview interview = interview();
        interview.setInterviewerUuids(List.of("interviewer-1", "interviewer-2"));

        service.createEvent(interview, candidateWithoutEmail(), null);

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(eq("career@trustworks.dk"), body.capture());
        assertEquals(List.of("interviewer-1@trustworks.dk", "interviewer-2@trustworks.dk"),
                body.getValue().attendees().stream()
                        .map(a -> a.emailAddress().address()).toList(),
                "both interviewers are invited — this asserted only interviewer-2 before the fix");
    }

    @Test
    void update_twoInterviewers_reschedulePatchesTheFullAttendeeList() {
        // Reschedule shares the builder, so it was equally truncated — which is
        // why no amount of re-saving ever repaired a dropped interviewer.
        when(graph.updateCalendarEvent(anyString(), anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-8", null, null));
        RecruitmentInterview interview = interview();
        interview.setInterviewerUuids(List.of("interviewer-1", "interviewer-2"));
        interview.setGraphEventId("evt-8");
        interview.setGraphOrganizer("career@trustworks.dk");

        service.updateEvent(interview, candidateWithoutEmail(), null);

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).updateCalendarEvent(anyString(), eq("evt-8"), body.capture());
        assertEquals(2, body.getValue().attendees().size());
    }

    @Test
    void create_roomIsBookedAsAResourceAlongsideEveryInterviewer() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphApiClient.CalendarEvent("evt-9", null, null));
        RecruitmentInterview interview = interview();
        interview.setRoomEmail("hp3@trustworks.dk");
        interview.setLocation("HP3");

        service.createEvent(interview, candidateWithoutEmail(), null);

        ArgumentCaptor<GraphApiClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.CalendarEventRequest.class);
        verify(graph).createCalendarEvent(anyString(), body.capture());
        assertEquals(List.of("required", "resource"),
                body.getValue().attendees().stream().map(a -> a.type()).toList(),
                "the room used to be the ONLY attendee on a single-interviewer event");
    }

    // ---- Fixtures --------------------------------------------------------------

    /** One interviewer — the single-interviewer case that invited NOBODY
     * before the identity-based organizer exclusion landed. */
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
