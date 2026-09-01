package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCalendarService.InvitationDetails;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCalendarService.Interviewer;
import dk.trustworks.intranet.graph.GraphCalendarClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The candidate invitation names the panel and the door (change requests
 * (a) + (h), 2026-09-01).
 * <p>
 * Two facts drive every assertion here. First, the candidate's Outlook
 * event carries ONLY the candidate as attendee (the V493 split), so unless
 * the BODY names the interviewers the candidate has no way at all to learn
 * who they are meeting — and the reception iPad at {@code /guest} makes
 * them type the host's name to check in. Second,
 * {@code RecruitmentEmailRenderer} leaves an unknown merge token VERBATIM
 * in the body and the {@code *_link} send gate never runs on the calendar
 * path, so a token without a value would mail a literal
 * {@code {{interviewer_names}}} to a candidate.
 * <p>
 * Plain unit test, no Quarkus boot: the content is pure text and belongs
 * in the DB-free tier that gates deploys.
 */
class RecruitmentCandidateInvitationContentTest {

    private static final String ADDRESS = "Hausergade 3, 1128 København K";

    private RecruitmentCalendarService service;
    private GraphCalendarClient graph;
    private RecruitmentVisitingAddress addressSetting;

    @BeforeEach
    void setUp() {
        graph = mock(GraphCalendarClient.class);
        addressSetting = mock(RecruitmentVisitingAddress.class);
        when(addressSetting.effectiveAddress()).thenReturn(ADDRESS);
        service = new RecruitmentCalendarService();
        service.graphApiClient = graph;
        service.calendarEnabled = true;
        service.configuredOrganizerValue = java.util.Optional.of("career@trustworks.dk");
        service.visitingAddressSetting = addressSetting;
        // The DB seam: uuid -> (mailbox, name), so the roster is assertable
        // without Panache.
        service.interviewerResolver = uuid -> new Interviewer(
                uuid + "@trustworks.dk", "Name " + uuid);
    }

    // ---- Naming the panel ------------------------------------------------------

    @Test
    void interviewerNames_joinsTheRosterTheDanishWay() {
        assertEquals("", RecruitmentCalendarService.interviewerNames(List.of()));
        assertEquals("", RecruitmentCalendarService.interviewerNames(null));
        assertEquals("Ida Iversen", RecruitmentCalendarService.interviewerNames(
                List.of(new Interviewer("i@trustworks.dk", "Ida Iversen"))));
        assertEquals("Ida Iversen og Lars Bo", RecruitmentCalendarService.interviewerNames(
                List.of(new Interviewer("i@trustworks.dk", "Ida Iversen"),
                        new Interviewer("l@trustworks.dk", "Lars Bo"))));
        assertEquals("Ida Iversen, Lars Bo og Mia Munk",
                RecruitmentCalendarService.interviewerNames(
                        List.of(new Interviewer("i@trustworks.dk", "Ida Iversen"),
                                new Interviewer("l@trustworks.dk", "Lars Bo"),
                                new Interviewer("m@trustworks.dk", "Mia Munk"))),
                "roster order, comma-separated, 'og' before the last");
    }

    /**
     * The NPE trap. {@code displayName} returns null for a half-filled user
     * row on purpose, and the extras map used to be a {@code Map.of}, which
     * throws on a null VALUE — inside {@code createEvent}'s catch-all. One
     * unnamed interviewer therefore meant the candidate received NO
     * invitation at all, visible only as a WARN.
     */
    @Test
    void interviewerNames_skipsUnnamedRowsInsteadOfRenderingNull() {
        String names = RecruitmentCalendarService.interviewerNames(Arrays.asList(
                null,
                new Interviewer("n@trustworks.dk", null),
                new Interviewer("b@trustworks.dk", "   "),
                new Interviewer("i@trustworks.dk", "Ida Iversen")));

        assertEquals("Ida Iversen", names);
        assertFalse(names.contains("null"), "a half-filled user row must never print 'null'");
    }

    @Test
    void interviewerNames_nobodyNamed_isEmptyNotNull() {
        assertEquals("", RecruitmentCalendarService.interviewerNames(
                Arrays.asList(new Interviewer("n@trustworks.dk", null), null)));
    }

    // ---- Physical vs online ----------------------------------------------------

    @Test
    void invitationDetails_physicalInterview_carriesTheAddressAndTheArrivalInstruction() {
        InvitationDetails details = RecruitmentCalendarService.invitationDetails(
                interview(false), List.of(new Interviewer("i@trustworks.dk", "Ida Iversen")),
                ADDRESS);

        assertEquals("Ida Iversen", details.interviewerNames());
        assertEquals(ADDRESS, details.visitingAddress());
        assertTrue(details.arrivalInstructions().contains(ADDRESS));
        assertTrue(details.arrivalInstructions().contains("receptionen"),
                "the arrival line must point at the reception iPad: "
                        + details.arrivalInstructions());
        assertTrue(details.arrivalInstructions().contains("dit navn")
                        && details.arrivalInstructions().contains("dit firma"),
                "it must name what the kiosk asks for: " + details.arrivalInstructions());
    }

    /**
     * Online: the Teams link and nothing else. The mode comes from
     * {@code online_meeting} (V492) and from nothing else — {@code location}
     * is free text ("Microsoft Teams", a room name, whatever was typed), so
     * no string test on it could tell a hybrid from a Teams-only meeting.
     */
    @Test
    void invitationDetails_onlineInterview_dropsTheAddressButStillNamesThePanel() {
        InvitationDetails details = RecruitmentCalendarService.invitationDetails(
                interview(true), List.of(new Interviewer("i@trustworks.dk", "Ida Iversen")),
                ADDRESS);

        assertEquals("Ida Iversen", details.interviewerNames(),
                "who you are meeting matters on a Teams call too");
        assertEquals("", details.visitingAddress());
        assertEquals("", details.arrivalInstructions());
    }

    @Test
    void invitationDetails_blankedAddressSetting_isTheOptOut() {
        for (String blank : List.of("", "   ")) {
            InvitationDetails details = RecruitmentCalendarService.invitationDetails(
                    interview(false), List.of(), blank);
            assertEquals("", details.visitingAddress());
            assertEquals("", details.arrivalInstructions());
        }
        assertEquals("", RecruitmentCalendarService.invitationDetails(
                interview(false), List.of(), null).arrivalInstructions());
    }

    /** Never null, whatever a caller hands in — the extras map cannot take one. */
    @Test
    void invitationDetails_normalisesNullComponentsToEmpty() {
        InvitationDetails details = new InvitationDetails(null, null, null);
        assertEquals("", details.interviewerNames());
        assertEquals("", details.visitingAddress());
        assertEquals("", details.arrivalInstructions());
    }

    // ---- The template path ----------------------------------------------------

    @Test
    void candidateInvitation_resolvesEveryNewToken() {
        RecruitmentCalendarService.CandidateInvitation invitation =
                RecruitmentCalendarService.candidateInvitation(
                        interview(false), candidate(), position(), null, template(),
                        RecruitmentCalendarService.invitationDetails(
                                interview(false),
                                List.of(new Interviewer("i@trustworks.dk", "Ida Iversen"),
                                        new Interviewer("l@trustworks.dk", "Lars Bo")),
                                ADDRESS));

        assertTrue(invitation.htmlBody().contains("Ida Iversen og Lars Bo"));
        assertTrue(invitation.htmlBody().contains("Hausergade 3, 1128 K"),
                "the visiting address must reach the candidate: " + invitation.htmlBody());
        assertTrue(invitation.htmlBody().contains("receptionen"));
        assertFalse(invitation.htmlBody().contains("{{"),
                "an unresolved token is left VERBATIM and reaches the candidate: "
                        + invitation.htmlBody());
    }

    /**
     * The online body must not show an empty "Adresse" line. The tokens
     * still RESOLVE (to nothing) — leaving them out of the extras map would
     * print the braces — so the paragraph that held them is dropped instead.
     */
    @Test
    void candidateInvitation_onlineInterview_leavesNoEmptyParagraphAndNoBraces() {
        RecruitmentCalendarService.CandidateInvitation invitation =
                RecruitmentCalendarService.candidateInvitation(
                        interview(true), candidate(), position(), null, template(),
                        RecruitmentCalendarService.invitationDetails(
                                interview(true),
                                List.of(new Interviewer("i@trustworks.dk", "Ida Iversen")),
                                ADDRESS));

        assertFalse(invitation.htmlBody().contains("{{"), invitation.htmlBody());
        assertFalse(invitation.htmlBody().contains("Hausergade"),
                "no address on a Teams interview: " + invitation.htmlBody());
        assertFalse(invitation.htmlBody().contains("<p></p>"),
                "the emptied paragraph must not survive as a blank line: "
                        + invitation.htmlBody());
        assertTrue(invitation.htmlBody().contains("Ida Iversen"));
    }

    @Test
    void candidateInvitation_nullDetails_stillRendersEveryTokenAsEmpty() {
        RecruitmentCalendarService.CandidateInvitation invitation =
                RecruitmentCalendarService.candidateInvitation(
                        interview(false), candidate(), position(), null, template(), null);

        assertFalse(invitation.htmlBody().contains("{{"), invitation.htmlBody());
    }

    @Test
    void dropEmptyParagraphs_onlyRemovesTheEmptyOnes() {
        assertEquals("<p>Hej</p>", RecruitmentCalendarService.dropEmptyParagraphs(
                "<p></p><p>Hej</p><p> </p><p><br></p>"));
        assertEquals("<p><strong>Sted:</strong> HP3</p>",
                RecruitmentCalendarService.dropEmptyParagraphs(
                        "<p><strong>Sted:</strong> HP3</p>"));
    }

    // ---- The built-in fallback body --------------------------------------------

    @Test
    void invitationBody_fallback_namesThePanelAndTellsThemWhatToDoOnArrival() {
        String body = RecruitmentCalendarService.invitationBody(
                interview(false), candidate(), true,
                RecruitmentCalendarService.invitationDetails(
                        interview(false),
                        List.of(new Interviewer("i@trustworks.dk", "Ida Iversen"),
                                new Interviewer("l@trustworks.dk", "Lars Bo")),
                        ADDRESS));

        assertTrue(body.contains("Du skal møde Ida Iversen og Lars Bo."), body);
        assertTrue(body.contains(ADDRESS), body);
        assertTrue(body.contains("receptionen"), body);
        assertTrue(body.endsWith("Med venlig hilsen\nTrustworks"), body);
    }

    @Test
    void invitationBody_online_saysNothingAboutAnAddress() {
        String body = RecruitmentCalendarService.invitationBody(
                interview(true), candidate(), true,
                RecruitmentCalendarService.invitationDetails(
                        interview(true),
                        List.of(new Interviewer("i@trustworks.dk", "Ida Iversen")),
                        ADDRESS));

        assertTrue(body.contains("Du skal møde Ida Iversen."), body);
        assertFalse(body.contains("Hausergade"), body);
        assertFalse(body.contains("receptionen"), body);
    }

    @Test
    void invitationBody_nobodyResolvable_omitsTheLineRatherThanPrintingAnEmptyOne() {
        String body = RecruitmentCalendarService.invitationBody(
                interview(false), candidate(), true,
                RecruitmentCalendarService.invitationDetails(interview(false), List.of(), ""));

        assertFalse(body.contains("Du skal møde"), body);
        assertFalse(body.contains("null"), body);
    }

    // ---- End to end through the event builders ---------------------------------

    @Test
    void createEvent_candidateEventBodyNamesEveryInterviewer() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphCalendarClient.CalendarEvent("evt-1", null, null));
        RecruitmentInterview interview = interview(false);
        interview.setInterviewerUuids(List.of("a", "b"));

        service.createEvent(interview, candidate(), position());

        ArgumentCaptor<GraphCalendarClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphCalendarClient.CalendarEventRequest.class);
        verify(graph, org.mockito.Mockito.atLeastOnce())
                .createCalendarEvent(anyString(), body.capture());
        String candidateBody = body.getAllValues().get(body.getAllValues().size() - 1)
                .body().content();
        assertTrue(candidateBody.contains("Name a og Name b"), candidateBody);
        assertTrue(candidateBody.contains("Hausergade"), candidateBody);
    }

    /**
     * Reschedule re-renders both events through the same builders, so a
     * changed panel propagates. It is the only repair path a recruiter has
     * — the pre-fix positional attendee drop was invisible precisely
     * because re-saving could not fix it.
     */
    @Test
    void updateEvent_reschedule_reRendersTheCandidateBodyWithTheCurrentPanel() {
        when(graph.updateCalendarEvent(anyString(), anyString(), any()))
                .thenReturn(new GraphCalendarClient.CalendarEvent("evt-1", null, null));
        RecruitmentInterview interview = interview(false);
        interview.setInterviewerUuids(List.of("a", "b", "c"));
        interview.setGraphEventId("evt-int");
        interview.setGraphCandidateEventId("evt-cand");
        interview.setGraphOrganizer("career@trustworks.dk");

        service.updateEvent(interview, candidate(), position());

        ArgumentCaptor<GraphCalendarClient.CalendarEventRequest> body =
                ArgumentCaptor.forClass(GraphCalendarClient.CalendarEventRequest.class);
        verify(graph).updateCalendarEvent(anyString(), org.mockito.ArgumentMatchers.eq("evt-cand"),
                body.capture());
        assertTrue(body.getValue().body().content().contains("Name a, Name b og Name c"),
                body.getValue().body().content());
    }

    // ---- Fixtures --------------------------------------------------------------

    private static RecruitmentInterview interview(boolean online) {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid("int-1");
        interview.setKind(RecruitmentInterviewKind.ROUND);
        interview.setRound(1);
        interview.setScheduledAt(LocalDateTime.of(2026, 8, 20, 10, 0));
        interview.setDurationMinutes(60);
        interview.setOnlineMeeting(online);
        interview.setLocation(online ? "Microsoft Teams" : "HP3");
        interview.setInterviewerUuids(List.of("a"));
        return interview;
    }

    private static RecruitmentCandidate candidate() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName("Anna");
        candidate.setLastName("Nielsen");
        candidate.setEmail("anna@example.com");
        return candidate;
    }

    private static RecruitmentPosition position() {
        RecruitmentPosition position = new RecruitmentPosition();
        position.setTitle("Consultant");
        return position;
    }

    /** The body V553 seeds, so the tokens under test are the shipped ones. */
    private static RecruitmentEmailTemplate template() {
        RecruitmentEmailTemplate template = new RecruitmentEmailTemplate();
        template.setSubject("Samtale hos Trustworks");
        template.setBody("<p>Kære {{candidate_first_name}}</p>"
                + "<p>Vi glæder os til at møde dig hos Trustworks.</p>"
                + "<p><strong>Tidspunkt:</strong> {{interview_date}} kl. {{interview_time}}"
                + "<br><strong>Sted:</strong> {{interview_location}}"
                + "<br><strong>Du skal møde:</strong> {{interviewer_names}}</p>"
                + "<p>{{arrival_instructions}}</p>"
                + "<p>Med venlig hilsen<br>Trustworks</p>");
        template.setBodyFormat(RecruitmentEmailBodyFormat.HTML);
        return template;
    }
}
