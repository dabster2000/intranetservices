package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.microsoft.graph.models.Attendee;
import com.microsoft.graph.models.AttendeeType;
import com.microsoft.graph.models.BodyType;
import com.microsoft.graph.models.DateTimeTimeZone;
import com.microsoft.graph.models.EmailAddress;
import com.microsoft.graph.models.Event;
import com.microsoft.graph.models.ItemBody;
import com.microsoft.graph.models.OnlineMeetingProviderType;
import com.microsoft.graph.models.ResponseType;
import com.microsoft.graph.models.Subscription;
import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeResponse;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.AttendeeStatus;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CancelEventCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.CreateEventCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.GraphSubscriptionInfo;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.SubscribeCommand;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.UpdateEventCommand;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Live implementation of {@link OutlookCalendarPort} backed by Microsoft Graph SDK 6.13.
 *
 * <p>Resolution: registered as {@code @Alternative @Priority(10)} so it beats the
 * {@link NoopOutlookCalendarPort} ({@code @Priority(1)}) at runtime. Mirrors the
 * Slice 2 {@link OpenAIPortImpl} pattern.
 *
 * <p>Auth: app-only via {@link GraphClientFactory} (client credentials). The TAM
 * mailbox is the organizer for every event — passed in via the command and used
 * with {@code client.users().byUserId(mailbox)}.
 *
 * <p>Error mapping: Graph errors surface as {@link ODataError} with an HTTP status
 * code from {@code com.microsoft.kiota.ApiException}. We translate to
 * {@link OutlookCalendarException} with a retryable flag (true for 408/429/5xx,
 * false for 4xx terminal errors like 403/404). The outbox worker uses that flag
 * to decide retry vs. dead-letter.
 */
@JBossLog
@ApplicationScoped
@Alternative
@Priority(10)  // beats NoopOutlookCalendarPort@Priority(1)
public class OutlookCalendarPortImpl implements OutlookCalendarPort {

    private static final String UTC = "UTC";

    @Inject
    GraphServiceClient graph;

    @Override
    public String createEvent(CreateEventCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Event event = buildEvent(cmd.subject(), cmd.bodyHtml(), cmd.startUtc(), cmd.endUtc(),
                cmd.attendeeEmails(), cmd.teamsEnabled());
        try {
            Event created = graph.users().byUserId(cmd.organizerMailbox()).events().post(event);
            String id = created != null ? created.getId() : null;
            log.infof("[OutlookCalendarPort] createEvent interview=%s mailbox=%s -> eventId=%s",
                    cmd.interviewUuid(), cmd.organizerMailbox(), id);
            if (id == null || id.isBlank()) {
                throw new OutlookCalendarException(true, "GRAPH_EMPTY_EVENT_ID",
                        "Graph returned event without id");
            }
            return id;
        } catch (ODataError e) {
            throw mapError("createEvent", e);
        }
    }

    @Override
    public void updateEvent(UpdateEventCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        // PATCH replaces the listed fields; we send the full attendee list so removals
        // propagate. Subject/body intentionally not patched here (only time + attendees).
        Event patch = new Event();
        patch.setStart(toGraphDateTime(cmd.startUtc()));
        patch.setEnd(toGraphDateTime(cmd.endUtc()));
        List<Attendee> attendees = new ArrayList<>();
        if (cmd.attendeeEmails() != null) {
            for (String email : cmd.attendeeEmails()) {
                if (email == null || email.isBlank()) continue;
                Attendee a = new Attendee();
                EmailAddress addr = new EmailAddress();
                addr.setAddress(email);
                a.setEmailAddress(addr);
                a.setType(AttendeeType.Required);
                attendees.add(a);
            }
        }
        patch.setAttendees(attendees);

        try {
            graph.users().byUserId(cmd.organizerMailbox())
                    .events().byEventId(cmd.eventId())
                    .patch(patch);
            log.infof("[OutlookCalendarPort] updateEvent interview=%s eventId=%s attendees=%d",
                    cmd.interviewUuid(), cmd.eventId(), attendees.size());
        } catch (ODataError e) {
            throw mapError("updateEvent", e);
        }
    }

    @Override
    public void cancelEvent(CancelEventCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        try {
            graph.users().byUserId(cmd.organizerMailbox())
                    .events().byEventId(cmd.eventId())
                    .delete();
            log.infof("[OutlookCalendarPort] cancelEvent interview=%s eventId=%s reason=%s",
                    cmd.interviewUuid(), cmd.eventId(), cmd.reason());
        } catch (ODataError e) {
            throw mapError("cancelEvent", e);
        }
    }

    @Override
    public List<AttendeeResponse> getAttendeeStatuses(String organizerMailbox, String eventId) {
        Objects.requireNonNull(organizerMailbox, "organizerMailbox");
        Objects.requireNonNull(eventId, "eventId");
        try {
            Event event = graph.users().byUserId(organizerMailbox)
                    .events().byEventId(eventId)
                    .get();
            if (event == null || event.getAttendees() == null) return List.of();
            List<AttendeeResponse> out = new ArrayList<>(event.getAttendees().size());
            for (Attendee a : event.getAttendees()) {
                if (a.getEmailAddress() == null) continue;
                ResponseType rt = a.getStatus() != null ? a.getStatus().getResponse() : null;
                out.add(new AttendeeResponse(a.getEmailAddress().getAddress(), mapResponseType(rt)));
            }
            return List.copyOf(out);
        } catch (ODataError e) {
            throw mapError("getAttendeeStatuses", e);
        }
    }

    @Override
    public GraphSubscriptionInfo createEventSubscription(SubscribeCommand cmd) {
        throw new UnsupportedOperationException("createEventSubscription not implemented yet");
    }

    @Override
    public GraphSubscriptionInfo renewSubscription(String subscriptionId, Instant newExpiresAt) {
        throw new UnsupportedOperationException("renewSubscription not implemented yet");
    }

    @Override
    public void deleteSubscription(String subscriptionId) {
        throw new UnsupportedOperationException("deleteSubscription not implemented yet");
    }

    // ---- helpers ----

    Event buildEvent(String subject, String bodyHtml, Instant start, Instant end,
                     List<String> attendeeEmails, boolean teamsEnabled) {
        Event event = new Event();
        event.setSubject(subject);

        ItemBody body = new ItemBody();
        body.setContentType(BodyType.Html);
        body.setContent(bodyHtml == null ? "" : bodyHtml);
        event.setBody(body);

        event.setStart(toGraphDateTime(start));
        event.setEnd(toGraphDateTime(end));

        List<Attendee> attendees = new ArrayList<>();
        if (attendeeEmails != null) {
            for (String email : attendeeEmails) {
                if (email == null || email.isBlank()) continue;
                Attendee a = new Attendee();
                EmailAddress addr = new EmailAddress();
                addr.setAddress(email);
                a.setEmailAddress(addr);
                a.setType(AttendeeType.Required);
                attendees.add(a);
            }
        }
        event.setAttendees(attendees);

        if (teamsEnabled) {
            event.setIsOnlineMeeting(Boolean.TRUE);
            event.setOnlineMeetingProvider(OnlineMeetingProviderType.TeamsForBusiness);
        }
        return event;
    }

    static DateTimeTimeZone toGraphDateTime(Instant instant) {
        DateTimeTimeZone dt = new DateTimeTimeZone();
        // Graph expects ISO local-datetime (no offset/zone in dateTime field) plus a separate timeZone.
        OffsetDateTime utc = instant.atOffset(ZoneOffset.UTC);
        dt.setDateTime(utc.toLocalDateTime().toString());
        dt.setTimeZone(UTC);
        return dt;
    }

    static AttendeeStatus mapResponseType(ResponseType rt) {
        if (rt == null) return AttendeeStatus.NONE;
        return switch (rt) {
            case Accepted -> AttendeeStatus.ACCEPTED;
            case Declined -> AttendeeStatus.DECLINED;
            case TentativelyAccepted -> AttendeeStatus.TENTATIVE;
            // None / Organizer / NotResponded → NONE
            default -> AttendeeStatus.NONE;
        };
    }

    static OutlookCalendarException mapError(String op, ODataError e) {
        int status = e.getResponseStatusCode();
        String code = e.getError() != null && e.getError().getCode() != null
                ? e.getError().getCode()
                : "GRAPH_" + status;
        String detail = e.getError() != null && e.getError().getMessage() != null
                ? e.getError().getMessage()
                : (e.getMessage() != null ? e.getMessage() : op + " failed");
        boolean retryable = status == 408 || status == 429 || (status >= 500 && status < 600);
        return new OutlookCalendarException(retryable, code, detail, e);
    }
}
