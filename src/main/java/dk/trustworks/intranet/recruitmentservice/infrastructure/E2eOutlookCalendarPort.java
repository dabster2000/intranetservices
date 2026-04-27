package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeResponse;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeStatus;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CancelEventCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CreateEventCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.GraphSubscriptionInfo;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.SubscribeCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.UpdateEventCommand;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic in-memory {@link OutlookCalendarPort} for Playwright dev-e2e runs.
 * Captures every call so Playwright specs can assert on it via test endpoints
 * (or after-deploy DB inspection). Active only under {@code dev-e2e} profile.
 *
 * <p>Resolution order at runtime:
 * <ul>
 *   <li>{@link NoopOutlookCalendarPort} {@code @Priority(1)}</li>
 *   <li>{@link OutlookCalendarPortImpl} {@code @Priority(10)}</li>
 *   <li>{@link E2eOutlookCalendarPort} {@code @Priority(100)} — wins in dev-e2e.</li>
 * </ul>
 */
@ApplicationScoped
@Alternative
@Priority(100)
@IfBuildProfile("dev-e2e")
public class E2eOutlookCalendarPort implements OutlookCalendarPort {

    public final ConcurrentHashMap<String, String> createdEvents = new ConcurrentHashMap<>();
    public final AtomicInteger updateCount = new AtomicInteger();
    public final AtomicInteger cancelCount = new AtomicInteger();

    @Override
    public String createEvent(CreateEventCommand cmd) {
        String id = "e2e-evt-" + UUID.randomUUID();
        createdEvents.put(cmd.interviewUuid(), id);
        return id;
    }

    @Override
    public void updateEvent(UpdateEventCommand cmd) {
        updateCount.incrementAndGet();
    }

    @Override
    public void cancelEvent(CancelEventCommand cmd) {
        cancelCount.incrementAndGet();
        createdEvents.remove(cmd.interviewUuid());
    }

    @Override
    public List<AttendeeResponse> getAttendeeStatuses(String organizerMailbox, String eventId) {
        // Deterministic: first attendee accepts, rest pending.
        return List.of(new AttendeeResponse("e2e-attendee@trustworks.dk", AttendeeStatus.ACCEPTED));
    }

    @Override
    public GraphSubscriptionInfo createEventSubscription(SubscribeCommand cmd) {
        return new GraphSubscriptionInfo("e2e-sub-" + UUID.randomUUID(), cmd.resource(), cmd.expiresAt());
    }

    @Override
    public GraphSubscriptionInfo renewSubscription(String subscriptionId, Instant newExpiresAt) {
        return new GraphSubscriptionInfo(subscriptionId, "/users/e2e/events", newExpiresAt);
    }

    @Override
    public void deleteSubscription(String subscriptionId) {
        // no-op
    }
}
