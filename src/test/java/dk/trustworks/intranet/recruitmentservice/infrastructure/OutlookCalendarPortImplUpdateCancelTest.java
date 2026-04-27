package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.microsoft.graph.models.Event;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.UsersRequestBuilder;
import com.microsoft.graph.users.item.UserItemRequestBuilder;
import com.microsoft.graph.users.item.events.EventsRequestBuilder;
import com.microsoft.graph.users.item.events.item.EventItemRequestBuilder;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CancelEventCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.UpdateEventCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit + Mockito tests for {@link OutlookCalendarPortImpl#updateEvent} and
 * {@link OutlookCalendarPortImpl#cancelEvent}. Verifies:
 * <ul>
 *   <li>updateEvent issues a PATCH against the event item with the full attendee
 *       list, so removals are propagated.</li>
 *   <li>cancelEvent issues a DELETE against the event item via Graph SDK.</li>
 * </ul>
 */
class OutlookCalendarPortImplUpdateCancelTest {

    private GraphServiceClient graph;
    private UsersRequestBuilder usersBuilder;
    private UserItemRequestBuilder userItemBuilder;
    private EventsRequestBuilder eventsBuilder;
    private EventItemRequestBuilder eventItemBuilder;
    private OutlookCalendarPortImpl port;

    private static final String MAILBOX = "tam@trustworks.dk";
    private static final String EVENT_ID = "AAMkAGI2_evt_42";
    private static final String INTERVIEW_UUID = "22222222-2222-2222-2222-222222222222";

    @BeforeEach
    void setUp() {
        graph = mock(GraphServiceClient.class);
        usersBuilder = mock(UsersRequestBuilder.class);
        userItemBuilder = mock(UserItemRequestBuilder.class);
        eventsBuilder = mock(EventsRequestBuilder.class);
        eventItemBuilder = mock(EventItemRequestBuilder.class);

        when(graph.users()).thenReturn(usersBuilder);
        when(usersBuilder.byUserId(MAILBOX)).thenReturn(userItemBuilder);
        when(userItemBuilder.events()).thenReturn(eventsBuilder);
        when(eventsBuilder.byEventId(EVENT_ID)).thenReturn(eventItemBuilder);

        port = new OutlookCalendarPortImpl();
        port.graph = graph;
    }

    @Test
    void updateEvent_PATCHes_with_full_attendee_list() {
        when(eventItemBuilder.patch(any(Event.class))).thenReturn(new Event());

        UpdateEventCommand cmd = new UpdateEventCommand(
                INTERVIEW_UUID, MAILBOX, EVENT_ID,
                Instant.parse("2026-05-13T13:00:00Z"),
                Instant.parse("2026-05-13T14:00:00Z"),
                List.of("a@trustworks.dk", "b@trustworks.dk", "c@example.com"));

        port.updateEvent(cmd);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventItemBuilder).patch(captor.capture());
        Event sent = captor.getValue();

        assertNotNull(sent.getAttendees());
        assertEquals(3, sent.getAttendees().size(), "PATCH must include the full attendee list");
        assertEquals("a@trustworks.dk", sent.getAttendees().get(0).getEmailAddress().getAddress());
        assertEquals("b@trustworks.dk", sent.getAttendees().get(1).getEmailAddress().getAddress());
        assertEquals("c@example.com", sent.getAttendees().get(2).getEmailAddress().getAddress());

        assertEquals("UTC", sent.getStart().getTimeZone());
        assertEquals("2026-05-13T13:00", sent.getStart().getDateTime());
        assertEquals("UTC", sent.getEnd().getTimeZone());
        assertEquals("2026-05-13T14:00", sent.getEnd().getDateTime());
    }

    @Test
    void cancelEvent_DELETEs_via_event_item() {
        CancelEventCommand cmd = new CancelEventCommand(
                INTERVIEW_UUID, MAILBOX, EVENT_ID, "candidate withdrew");

        port.cancelEvent(cmd);

        verify(eventItemBuilder).delete();
    }
}
