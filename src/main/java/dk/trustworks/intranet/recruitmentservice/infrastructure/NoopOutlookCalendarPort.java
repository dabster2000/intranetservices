package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeResponse;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CancelEventCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CreateEventCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.GraphSubscriptionInfo;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.SubscribeCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.UpdateEventCommand;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fallback no-op implementation. Logs the call and returns deterministic placeholders.
 * Activated when no higher-priority alternative is registered (see {@code OutlookCalendarPortImpl}
 * with {@code @Priority(10)}). Mirrors the {@code NoopOpenAIPort} pattern from Slice 2.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class NoopOutlookCalendarPort implements OutlookCalendarPort {

    private static final Logger LOG = Logger.getLogger(NoopOutlookCalendarPort.class);

    @Override
    public String createEvent(CreateEventCommand cmd) {
        String fakeId = "noop-evt-" + UUID.randomUUID();
        LOG.infof("NOOP createEvent interview=%s mailbox=%s -> %s",
                cmd.interviewUuid(), cmd.organizerMailbox(), fakeId);
        return fakeId;
    }

    @Override
    public void updateEvent(UpdateEventCommand cmd) {
        LOG.infof("NOOP updateEvent interview=%s eventId=%s", cmd.interviewUuid(), cmd.eventId());
    }

    @Override
    public void cancelEvent(CancelEventCommand cmd) {
        LOG.infof("NOOP cancelEvent interview=%s eventId=%s", cmd.interviewUuid(), cmd.eventId());
    }

    @Override
    public List<AttendeeResponse> getAttendeeStatuses(String organizerMailbox, String eventId) {
        return List.of();
    }

    @Override
    public GraphSubscriptionInfo createEventSubscription(SubscribeCommand cmd) {
        return new GraphSubscriptionInfo("noop-sub-" + UUID.randomUUID(), cmd.resource(), cmd.expiresAt());
    }

    @Override
    public GraphSubscriptionInfo renewSubscription(String subscriptionId, Instant newExpiresAt) {
        return new GraphSubscriptionInfo(subscriptionId, "/users/noop/events", newExpiresAt);
    }

    @Override
    public void deleteSubscription(String subscriptionId) {
        LOG.infof("NOOP deleteSubscription %s", subscriptionId);
    }
}
