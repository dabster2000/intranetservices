package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.graph.GraphCalendarClient;
import dk.trustworks.intranet.graph.GraphResponseExceptionMapper;
import dk.trustworks.intranet.graph.GraphResponseExceptionMapper.GraphApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The 2026-08-24 production incident, pinned (DB-free tier): Graph
 * answered 504 Gateway Timeout on the CANDIDATE event create for
 * interview {@code fdb0eb14}; the old code logged "internal event stands
 * alone" and dropped the candidate's ONLY invitation on the floor —
 * no retry, no alert, no timeline trace. These tests pin the two halves
 * of the fix that live in this service: transient Graph failures come
 * back CLASSIFIED retryable (so the caller arms the V533 repair marker),
 * and permanent ones come back non-retryable (so a person is told
 * instead of an automat retrying a 403 forever).
 */
class RecruitmentCalendarFailureClassificationTest {

    /** The prod 504's body, verbatim shape (traceId e19609e13fbd2a78…). */
    private static final String PROD_504_BODY =
            "{\"error\":{\"code\":\"UnknownError\",\"message\":\"\",\"innerError\":{"
                    + "\"date\":\"2026-08-24T15:08:54\","
                    + "\"request-id\":\"078c03a7-cc41-449a-bf8e-205e7c0db0c1\","
                    + "\"client-request-id\":\"078c03a7-cc41-449a-bf8e-205e7c0db0c1\"}}}";

    private RecruitmentCalendarService service;
    private GraphCalendarClient graph;

    @BeforeEach
    void setUp() {
        graph = mock(GraphCalendarClient.class);
        service = new RecruitmentCalendarService();
        service.graphApiClient = graph;
        service.calendarEnabled = true;
        service.configuredOrganizerValue = Optional.of("career@trustworks.dk");
        service.interviewerResolver = uuid ->
                new RecruitmentCalendarService.Interviewer(
                        uuid + "@trustworks.dk", "Name " + uuid);
    }

    // ---- The 504 path, end to end through createEvent ----------------------

    @Test
    void candidateCreate504_internalEventStands_failureClassifiedRetryable() {
        // Call 1 (internal event) succeeds; call 2 (candidate event) is the
        // prod 504 — down to the empty message and the request-id.
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphCalendarClient.CalendarEvent("evt-int", null, null))
                .thenThrow(new GraphApiException(
                        "Graph API error 504 Gateway Timeout: " + PROD_504_BODY,
                        504, null, "078c03a7-cc41-449a-bf8e-205e7c0db0c1"));

        RecruitmentCalendarService.CreateResult result =
                service.createEvent(interview(), candidate(), null);

        assertNotNull(result.created(), "the internal event stands — scheduling never fails");
        assertEquals("evt-int", result.created().eventId());
        assertNull(result.created().candidateEventId(),
                "the candidate's invitation does not exist yet");
        assertNull(result.internalFailure());
        assertNotNull(result.candidateFailure(),
                "the failure is REPORTED, not swallowed — this was the whole bug");
        assertTrue(result.candidateFailure().retryable(),
                "504 is transient: the repair sweep must get a shot at it");
        assertEquals("078c03a7-cc41-449a-bf8e-205e7c0db0c1",
                result.candidateFailure().graphRequestId(),
                "the Graph correlation id rides along for the operator alert");
    }

    @Test
    void internalCreate504_reportedRetryable_noCandidateAttempt() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenThrow(new GraphApiException("Graph API error 504", 504));

        RecruitmentCalendarService.CreateResult result =
                service.createEvent(interview(), candidate(), null);

        assertNull(result.created());
        assertNotNull(result.internalFailure());
        assertTrue(result.internalFailure().retryable());
        assertNull(result.candidateFailure(),
                "no candidate attempt happened — nothing to classify");
    }

    @Test
    void candidateCreatePermanent403_classifiedNotRetryable() {
        when(graph.createCalendarEvent(anyString(), any()))
                .thenReturn(new GraphCalendarClient.CalendarEvent("evt-int", null, null))
                .thenThrow(new GraphApiException(
                        "Graph API error 403: missing Calendars.ReadWrite", 403));

        RecruitmentCalendarService.CreateResult result =
                service.createEvent(interview(), candidate(), null);

        assertNotNull(result.created());
        assertNotNull(result.candidateFailure());
        assertFalse(result.candidateFailure().retryable(),
                "a 403 retried every 5 minutes is the same 403 — a person must act");
    }

    @Test
    void splitUpdate_candidateHalf504_reportedRetryable() {
        when(graph.updateCalendarEvent(anyString(), anyString(), any()))
                .thenReturn(new GraphCalendarClient.CalendarEvent("evt-int", null, null))
                .thenThrow(new GraphApiException("Graph API error 504", 504));
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-int");
        interview.setGraphCandidateEventId("evt-cand");
        interview.setGraphOrganizer("career@trustworks.dk");

        RecruitmentCalendarService.UpdateResult result =
                service.updateEvent(interview, candidate(), null);

        assertFalse(result.candidateUpdated());
        assertNotNull(result.candidateFailure(),
                "a stale candidate invitation (OLD time) must be reported, not WARNed away");
        assertTrue(result.candidateFailure().retryable());
    }

    @Test
    void splitUpdate_bothHalvesSucceed_reportsCandidateUpdated() {
        when(graph.updateCalendarEvent(anyString(), anyString(), any()))
                .thenReturn(new GraphCalendarClient.CalendarEvent("evt-int", null, null));
        RecruitmentInterview interview = interview();
        interview.setGraphEventId("evt-int");
        interview.setGraphCandidateEventId("evt-cand");
        interview.setGraphOrganizer("career@trustworks.dk");

        RecruitmentCalendarService.UpdateResult result =
                service.updateEvent(interview, candidate(), null);

        assertTrue(result.candidateUpdated(),
                "the timeline needs to know the candidate got an updated invitation");
        assertNull(result.candidateFailure());
    }

    // ---- The classification rule itself ------------------------------------

    @Test
    void classify_transientStatuses_areRetryable() {
        for (int status : new int[]{429, 500, 502, 503, 504, 408}) {
            assertTrue(RecruitmentCalendarService.classifyGraphFailure(
                            new GraphApiException("Graph API error " + status, status))
                    .retryable(), status + " must be retryable");
        }
    }

    @Test
    void classify_permanentStatuses_areNot() {
        for (int status : new int[]{400, 401, 403, 404, 409, 422}) {
            assertFalse(RecruitmentCalendarService.classifyGraphFailure(
                            new GraphApiException("Graph API error " + status, status))
                    .retryable(), status + " must go to a person, not a retry loop");
        }
    }

    @Test
    void classify_wrappedTimeouts_areRetryable() {
        assertTrue(RecruitmentCalendarService.classifyGraphFailure(
                new jakarta.ws.rs.ProcessingException("timeout",
                        new java.net.SocketTimeoutException("read timed out"))).retryable());
        assertTrue(RecruitmentCalendarService.classifyGraphFailure(
                new RuntimeException("wrapped",
                        new java.io.IOException("connection reset"))).retryable());
    }

    @Test
    void classify_surprises_areNotRetryable() {
        // Our own bug retried forever is a slow-motion outage: hand it over.
        assertFalse(RecruitmentCalendarService.classifyGraphFailure(
                new NullPointerException("shaping bug")).retryable());
    }

    @Test
    void requestId_extractedFromTheProd504Body() {
        assertEquals("078c03a7-cc41-449a-bf8e-205e7c0db0c1",
                GraphResponseExceptionMapper.extractRequestId(PROD_504_BODY));
        assertNull(GraphResponseExceptionMapper.extractRequestId("{\"error\":{}}"));
        assertNull(GraphResponseExceptionMapper.extractRequestId(null));
    }

    // ---- Fixtures ----------------------------------------------------------

    private static RecruitmentInterview interview() {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid("int-504");
        interview.setKind(RecruitmentInterviewKind.ROUND);
        interview.setRound(2);
        interview.setScheduledAt(LocalDateTime.of(2026, 8, 25, 15, 0));
        interview.setDurationMinutes(60);
        interview.setInterviewerUuids(List.of("interviewer-1"));
        return interview;
    }

    private static RecruitmentCandidate candidate() {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setFirstName("Martin");
        candidate.setLastName("Testesen");
        candidate.setEmail("candidate@example.com");
        return candidate;
    }
}
