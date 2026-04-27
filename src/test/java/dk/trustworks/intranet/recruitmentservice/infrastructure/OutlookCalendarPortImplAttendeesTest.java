package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.microsoft.graph.models.Attendee;
import com.microsoft.graph.models.EmailAddress;
import com.microsoft.graph.models.Event;
import com.microsoft.graph.models.ResponseStatus;
import com.microsoft.graph.models.ResponseType;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.users.UsersRequestBuilder;
import com.microsoft.graph.users.item.UserItemRequestBuilder;
import com.microsoft.graph.users.item.events.EventsRequestBuilder;
import com.microsoft.graph.users.item.events.item.EventItemRequestBuilder;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeResponse;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit + Mockito test for {@link OutlookCalendarPortImpl#getAttendeeStatuses}.
 *
 * <p>Verifies that all interesting Graph {@link ResponseType} values map to the
 * port's {@link AttendeeStatus} domain enum (with everything that isn't a real
 * RSVP — None, Organizer, NotResponded — collapsing to {@code NONE}).
 */
class OutlookCalendarPortImplAttendeesTest {

    private GraphServiceClient graph;
    private OutlookCalendarPortImpl port;

    private static final String MAILBOX = "tam@trustworks.dk";
    private static final String EVENT_ID = "AAMkAGI2_evt_99";

    @BeforeEach
    void setUp() {
        graph = mock(GraphServiceClient.class);
        UsersRequestBuilder usersBuilder = mock(UsersRequestBuilder.class);
        UserItemRequestBuilder userItemBuilder = mock(UserItemRequestBuilder.class);
        EventsRequestBuilder eventsBuilder = mock(EventsRequestBuilder.class);
        EventItemRequestBuilder eventItemBuilder = mock(EventItemRequestBuilder.class);

        when(graph.users()).thenReturn(usersBuilder);
        when(usersBuilder.byUserId(MAILBOX)).thenReturn(userItemBuilder);
        when(userItemBuilder.events()).thenReturn(eventsBuilder);
        when(eventsBuilder.byEventId(EVENT_ID)).thenReturn(eventItemBuilder);

        Event ev = new Event();
        ev.setAttendees(List.of(
                attendee("a@x.dk", ResponseType.Accepted),
                attendee("b@x.dk", ResponseType.Declined),
                attendee("c@x.dk", ResponseType.TentativelyAccepted),
                attendee("d@x.dk", ResponseType.NotResponded),
                attendee("e@x.dk", ResponseType.None),
                attendee("f@x.dk", ResponseType.Organizer)
        ));
        when(eventItemBuilder.get()).thenReturn(ev);

        port = new OutlookCalendarPortImpl();
        port.graph = graph;
    }

    @Test
    void getAttendeeStatuses_maps_all_response_types_correctly() {
        List<AttendeeResponse> statuses = port.getAttendeeStatuses(MAILBOX, EVENT_ID);

        Map<String, AttendeeStatus> byEmail = statuses.stream()
                .collect(Collectors.toMap(AttendeeResponse::email, AttendeeResponse::status));

        assertEquals(6, byEmail.size());
        assertEquals(AttendeeStatus.ACCEPTED, byEmail.get("a@x.dk"));
        assertEquals(AttendeeStatus.DECLINED, byEmail.get("b@x.dk"));
        assertEquals(AttendeeStatus.TENTATIVE, byEmail.get("c@x.dk"));
        assertEquals(AttendeeStatus.NONE, byEmail.get("d@x.dk"));
        assertEquals(AttendeeStatus.NONE, byEmail.get("e@x.dk"));
        assertEquals(AttendeeStatus.NONE, byEmail.get("f@x.dk"));
    }

    private static Attendee attendee(String email, ResponseType rt) {
        Attendee a = new Attendee();
        EmailAddress addr = new EmailAddress();
        addr.setAddress(email);
        a.setEmailAddress(addr);
        ResponseStatus rs = new ResponseStatus();
        rs.setResponse(rt);
        a.setStatus(rs);
        return a;
    }
}
