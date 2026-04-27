package dk.trustworks.intranet.recruitmentservice.ports;

import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeResponse;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CancelEventCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CreateEventCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.GraphSubscriptionInfo;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.SubscribeCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.UpdateEventCommand;

import java.time.Instant;
import java.util.List;

public interface OutlookCalendarPort {
    String createEvent(CreateEventCommand cmd);
    void updateEvent(UpdateEventCommand cmd);
    void cancelEvent(CancelEventCommand cmd);
    List<AttendeeResponse> getAttendeeStatuses(String organizerMailbox, String eventId);
    GraphSubscriptionInfo createEventSubscription(SubscribeCommand cmd);
    GraphSubscriptionInfo renewSubscription(String subscriptionId, Instant newExpiresAt);
    void deleteSubscription(String subscriptionId);
}
