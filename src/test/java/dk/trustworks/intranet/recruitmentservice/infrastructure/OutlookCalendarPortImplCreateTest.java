package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.microsoft.graph.models.Attendee;
import com.microsoft.graph.models.AttendeeType;
import com.microsoft.graph.models.BodyType;
import com.microsoft.graph.models.Event;
import com.microsoft.graph.models.OnlineMeetingProviderType;
import com.microsoft.graph.models.odataerrors.MainError;
import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.UsersRequestBuilder;
import com.microsoft.graph.users.item.UserItemRequestBuilder;
import com.microsoft.graph.users.item.events.EventsRequestBuilder;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CreateEventCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit + Mockito test for {@link OutlookCalendarPortImpl#createEvent}.
 *
 * <p>Stubs the Graph SDK's fluent chain
 * {@code graph.users().byUserId(mailbox).events().post(event)} and asserts:
 * <ul>
 *   <li>the canonical Event payload (subject, HTML body, UTC start/end, attendees,
 *       Teams provider when {@code teamsEnabled}) is sent;</li>
 *   <li>HTTP 429 → {@link OutlookCalendarException} with {@code retryable=true};</li>
 *   <li>HTTP 403 → {@link OutlookCalendarException} with {@code retryable=false}.</li>
 * </ul>
 */
class OutlookCalendarPortImplCreateTest {

    private GraphServiceClient graph;
    private UsersRequestBuilder usersBuilder;
    private UserItemRequestBuilder userItemBuilder;
    private EventsRequestBuilder eventsBuilder;
    private OutlookCalendarPortImpl port;

    private static final String MAILBOX = "tam@trustworks.dk";
    private static final String INTERVIEW_UUID = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        graph = mock(GraphServiceClient.class);
        usersBuilder = mock(UsersRequestBuilder.class);
        userItemBuilder = mock(UserItemRequestBuilder.class);
        eventsBuilder = mock(EventsRequestBuilder.class);

        when(graph.users()).thenReturn(usersBuilder);
        when(usersBuilder.byUserId(MAILBOX)).thenReturn(userItemBuilder);
        when(userItemBuilder.events()).thenReturn(eventsBuilder);

        port = new OutlookCalendarPortImpl();
        port.graph = graph;
    }

    @Test
    void createEvent_sends_canonical_payload_and_returns_event_id() {
        Event returned = new Event();
        returned.setId("AAMkAGI2_evt_1");
        when(eventsBuilder.post(any(Event.class))).thenReturn(returned);

        CreateEventCommand cmd = new CreateEventCommand(
                INTERVIEW_UUID,
                MAILBOX,
                "Trustworks interview — Round 1",
                "<p>Hi Jane</p>",
                Instant.parse("2026-05-12T09:00:00Z"),
                Instant.parse("2026-05-12T10:00:00Z"),
                List.of("interviewer@trustworks.dk", "candidate@example.com"),
                true);

        String id = port.createEvent(cmd);

        assertEquals("AAMkAGI2_evt_1", id);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        org.mockito.Mockito.verify(eventsBuilder).post(captor.capture());
        Event sent = captor.getValue();

        assertEquals("Trustworks interview — Round 1", sent.getSubject());
        assertNotNull(sent.getBody());
        assertEquals(BodyType.Html, sent.getBody().getContentType());
        assertEquals("<p>Hi Jane</p>", sent.getBody().getContent());

        assertNotNull(sent.getStart());
        assertEquals("UTC", sent.getStart().getTimeZone());
        // local-datetime portion only — Graph derives the offset from timeZone
        assertEquals("2026-05-12T09:00", sent.getStart().getDateTime());
        assertNotNull(sent.getEnd());
        assertEquals("UTC", sent.getEnd().getTimeZone());
        assertEquals("2026-05-12T10:00", sent.getEnd().getDateTime());

        assertNotNull(sent.getAttendees());
        assertEquals(2, sent.getAttendees().size());
        Attendee a0 = sent.getAttendees().get(0);
        assertEquals("interviewer@trustworks.dk", a0.getEmailAddress().getAddress());
        assertEquals(AttendeeType.Required, a0.getType());
        Attendee a1 = sent.getAttendees().get(1);
        assertEquals("candidate@example.com", a1.getEmailAddress().getAddress());

        assertTrue(Boolean.TRUE.equals(sent.getIsOnlineMeeting()));
        assertEquals(OnlineMeetingProviderType.TeamsForBusiness, sent.getOnlineMeetingProvider());
    }

    @Test
    void createEvent_maps_429_to_retryable() {
        ODataError err = buildOData(429, "TooManyRequests", "rate limited");
        when(eventsBuilder.post(any(Event.class))).thenThrow(err);

        CreateEventCommand cmd = simpleCmd();
        OutlookCalendarException ex = assertThrows(OutlookCalendarException.class, () -> port.createEvent(cmd));

        assertTrue(ex.isRetryable(), "429 must be retryable");
        assertEquals("TooManyRequests", ex.getErrorCode());
        assertEquals("rate limited", ex.getDetail());
    }

    @Test
    void createEvent_maps_403_to_terminal() {
        ODataError err = buildOData(403, "ErrorAccessDenied", "no consent");
        when(eventsBuilder.post(any(Event.class))).thenThrow(err);

        CreateEventCommand cmd = simpleCmd();
        OutlookCalendarException ex = assertThrows(OutlookCalendarException.class, () -> port.createEvent(cmd));

        assertFalse(ex.isRetryable(), "403 must be terminal");
        assertEquals("ErrorAccessDenied", ex.getErrorCode());
    }

    private CreateEventCommand simpleCmd() {
        return new CreateEventCommand(
                INTERVIEW_UUID, MAILBOX, "s", "b",
                Instant.parse("2026-05-12T09:00:00Z"),
                Instant.parse("2026-05-12T10:00:00Z"),
                List.of("a@b.dk"), false);
    }

    /**
     * Build a real {@link ODataError} with a usable {@code responseStatusCode}.
     * We can't easily set the status via spy (final-like field semantics), so
     * we use Mockito on the ODataError instance itself.
     */
    private static ODataError buildOData(int status, String code, String message) {
        ODataError err = mock(ODataError.class);
        when(err.getResponseStatusCode()).thenReturn(status);
        MainError main = new MainError();
        main.setCode(code);
        main.setMessage(message);
        when(err.getError()).thenReturn(main);
        when(err.getMessage()).thenReturn(message);
        return err;
    }
}
