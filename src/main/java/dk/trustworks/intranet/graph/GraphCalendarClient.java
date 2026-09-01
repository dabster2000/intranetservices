package dk.trustworks.intranet.graph;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.oidc.client.filter.OidcClientFilter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * MicroProfile REST Client for the Microsoft Graph <em>calendar</em> surface —
 * the only Graph integration the intranet keeps. It backs the recruitment
 * interview scheduler ({@code RecruitmentCalendarService}): event
 * create/update/delete, RSVP status reads, free/busy lookups and the tenant's
 * bookable meeting rooms.
 *
 * <p>Authentication is handled automatically via the OIDC client filter,
 * which obtains and manages access tokens using the client-credentials flow
 * ({@code quarkus.oidc-client.graph}). App-level permissions required:
 * {@code Calendars.ReadWrite} and {@code Place.Read.All}.
 *
 * @see <a href="https://learn.microsoft.com/en-us/graph/api/overview">Microsoft Graph API</a>
 */
@Path("/v1.0")
@RegisterRestClient(configKey = "graph-api")
@OidcClientFilter("graph")
@RegisterProvider(GraphResponseExceptionMapper.class)
@RegisterProvider(GraphApiLoggingFilter.class)
@RegisterProvider(dk.trustworks.intranet.perf.PerfRestClientFilter.class)
public interface GraphCalendarClient {

    /**
     * Creates a calendar event in a user's default calendar. Used by the
     * recruitment interview scheduler (behind
     * {@code dk.trustworks.recruitment.graph.calendar.enabled}) — requires
     * the app-level {@code Calendars.ReadWrite} permission.
     *
     * @param userPrincipal the mailbox owner (UPN/email or user id)
     * @param event         the event to create
     * @return the created event (only {@code id} is mapped)
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/user-post-events">Create event</a>
     */
    @POST
    @Path("/users/{userPrincipal}/calendar/events")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    CalendarEvent createCalendarEvent(
        @PathParam("userPrincipal") String userPrincipal,
        CalendarEventRequest event
    );

    /**
     * Updates a calendar event (partial PATCH — only non-null fields are
     * sent). Attendees receive an updated invitation.
     *
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/event-update">Update event</a>
     */
    @PATCH
    @Path("/users/{userPrincipal}/events/{eventId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    CalendarEvent updateCalendarEvent(
        @PathParam("userPrincipal") String userPrincipal,
        @PathParam("eventId") String eventId,
        CalendarEventRequest patch
    );

    /**
     * Deletes a calendar event — attendees receive a cancellation. 404 is
     * treated as idempotent by the caller.
     *
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/event-delete">Delete event</a>
     */
    @DELETE
    @Path("/users/{userPrincipal}/events/{eventId}")
    void deleteCalendarEvent(
        @PathParam("userPrincipal") String userPrincipal,
        @PathParam("eventId") String eventId
    );

    /**
     * Reads one calendar event's live status: start time and per-attendee
     * RSVP responses (interview scheduling plan Phase 5 — on-demand
     * polling, no webhooks). The {@code Prefer} header makes Graph return
     * the start as Europe/Copenhagen wall clock, directly comparable to
     * the interview row's {@code scheduled_at}.
     *
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/event-get">Get event</a>
     */
    @GET
    @Path("/users/{userPrincipal}/events/{eventId}")
    @Produces(MediaType.APPLICATION_JSON)
    @ClientHeaderParam(name = "Prefer", value = "outlook.timezone=\"Europe/Copenhagen\"")
    CalendarEventDetails getCalendarEventDetails(
        @PathParam("userPrincipal") String userPrincipal,
        @PathParam("eventId") String eventId,
        @QueryParam("$select") String select
    );

    /**
     * The status subset of the Graph event resource. Attendee
     * {@code status.response} values: {@code none}, {@code organizer},
     * {@code tentativelyAccepted}, {@code accepted}, {@code declined},
     * {@code notResponded}.
     */
    record CalendarEventDetails(
        CalendarEventRequest.DateTimeTimeZone start,
        List<EventAttendee> attendees,
        CalendarEvent.OnlineMeeting onlineMeeting
    ) {
        public record EventAttendee(
            CalendarEventRequest.Attendee.EmailAddress emailAddress,
            String type,
            AttendeeStatus status
        ) {
            public record AttendeeStatus(String response) { }
        }
    }

    /**
     * The events overlapping a time window in a mailbox's default
     * calendar — the Method B recheck (plan §9.3): the
     * {@code availabilityView} digit string cannot attribute busyness to
     * events, so the hold-time recheck reads {@code calendarView} with
     * {@code $select=id,showAs} and excludes this request's own hold
     * event ids; any OTHER busy/tentative event is a conflict. Strictly
     * ids + free/busy status — no subjects or bodies are requested.
     * <p>
     * {@code startDateTime}/{@code endDateTime} are ISO-8601; with the
     * {@code Prefer} header below they are interpreted AND returned as
     * Europe/Copenhagen wall clock, matching the interview loop's naive
     * datetimes.
     *
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/calendar-list-calendarview">List calendarView</a>
     */
    @GET
    @Path("/users/{userPrincipal}/calendarView")
    @Produces(MediaType.APPLICATION_JSON)
    @ClientHeaderParam(name = "Prefer", value = "outlook.timezone=\"Europe/Copenhagen\"")
    CalendarViewResponse calendarView(
        @PathParam("userPrincipal") String userPrincipal,
        @QueryParam("startDateTime") String startDateTime,
        @QueryParam("endDateTime") String endDateTime,
        @QueryParam("$select") String select,
        @QueryParam("$top") Integer top
    );

    /** {@code calendarView} response — ids, free/busy status, the
     * cancellation flag and the interval bounds. No subjects or bodies
     * are ever requested. {@code isCancelled} keeps "Annulleret:"
     * meetings — cancelled but still on the calendar, typically still
     * non-free {@code showAs} — from blocking a slot (F1b);
     * {@code start}/{@code end} let the caller drop boundary-touching
     * events whatever Graph's window semantics are (F1c). */
    record CalendarViewResponse(
        @JsonProperty("value") List<CalendarViewEvent> value
    ) {
        public record CalendarViewEvent(String id, String showAs,
                                        @JsonProperty("isCancelled") Boolean isCancelled,
                                        GraphDateTime start, GraphDateTime end) { }

        /** Graph's {@code dateTimeTimeZone} shape. */
        public record GraphDateTime(String dateTime, String timeZone) { }
    }

    /**
     * Lists the tenant's bookable meeting rooms. Used by the recruitment
     * interview scheduler's room picker — requires the app-level
     * {@code Place.Read.All} permission.
     *
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/place-list">List places</a>
     */
    @GET
    @Path("/places/microsoft.graph.room")
    @Produces(MediaType.APPLICATION_JSON)
    RoomCollectionResponse listRooms();

    /**
     * As {@link #listRooms()}, with explicit paging. Graph returns rooms a
     * page at a time and signals more with {@code @odata.nextLink}; the
     * unpaged call above silently sees only the first page, which for the
     * scheduler means rooms that exist but can never be suggested. Same
     * {@code Place.Read.All} permission.
     *
     * @param top       page size
     * @param skipToken continuation token parsed from
     *                  {@code @odata.nextLink}; null for the first page
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/place-list">List places</a>
     */
    @GET
    @Path("/places/microsoft.graph.room")
    @Produces(MediaType.APPLICATION_JSON)
    RoomCollectionResponse listRoomsPaged(
        @QueryParam("$top") Integer top,
        @QueryParam("$skiptoken") String skipToken
    );

    /** Graph places response — the subset of the room resource we use. */
    record RoomCollectionResponse(
        @JsonProperty("value") List<Room> value,
        @JsonProperty("@odata.nextLink") String odataNextLink) {

        /** A single, final page — no continuation. */
        public RoomCollectionResponse(List<Room> value) {
            this(value, null);
        }

        public record Room(
            String id,
            String displayName,
            String emailAddress,
            Integer capacity,
            String building
        ) { }
    }

    /**
     * Free/busy lookup for up to 20 mailboxes (rooms) in one call. Used by
     * the recruitment room picker to hide rooms already booked for the
     * chosen interview slot — covered by the app-level
     * {@code Calendars.ReadWrite} permission.
     *
     * @param userPrincipal any resolvable mailbox to anchor the call (a
     *                      room's own address works)
     * @see <a href="https://learn.microsoft.com/en-us/graph/api/calendar-getschedule">getSchedule</a>
     */
    @POST
    @Path("/users/{userPrincipal}/calendar/getSchedule")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    ScheduleCollectionResponse getSchedule(
        @PathParam("userPrincipal") String userPrincipal,
        ScheduleRequest request
    );

    /** Request body of {@code getSchedule}. */
    record ScheduleRequest(
        List<String> schedules,
        CalendarEventRequest.DateTimeTimeZone startTime,
        CalendarEventRequest.DateTimeTimeZone endTime,
        Integer availabilityViewInterval
    ) { }

    /**
     * {@code getSchedule} response. {@code availabilityView} is one digit
     * per interval; "0" = free — any other digit means busy/tentative/OOF.
     * {@code workingHours} rides along in every response (no extra
     * permission) — start/end are wall-clock strings like
     * {@code "08:00:00.0000000"} in the mailbox's own {@code timeZone}
     * (a Windows zone name, e.g. "Romance Standard Time").
     */
    record ScheduleCollectionResponse(
        @JsonProperty("value") List<ScheduleInformation> value
    ) {
        public record ScheduleInformation(
            String scheduleId,
            String availabilityView,
            WorkingHours workingHours
        ) {
            public record WorkingHours(
                List<String> daysOfWeek,
                String startTime,
                String endTime,
                TimeZoneName timeZone
            ) {
                public record TimeZoneName(String name) { }
            }
        }
    }

    /**
     * Graph calendar event response — the id plus the Teams meeting link
     * when the event is an online meeting ({@code onlineMeeting} may lag
     * on PATCH responses; a later read backfills it).
     */
    record CalendarEvent(String id, Boolean isOnlineMeeting, OnlineMeeting onlineMeeting) {
        public record OnlineMeeting(String joinUrl) { }
    }

    /**
     * Graph calendar event create/patch body (subset of the Graph event
     * resource). Null fields are omitted on PATCH.
     * <p>
     * {@code transactionId} is CREATE-ONLY (Graph rejects it on PATCH):
     * the caller's idempotency key — a retried create with the same id
     * never double-books.
     * <p>
     * {@code showAs} ({@code free|tentative|busy|oof|workingElsewhere})
     * and {@code isReminderOn} exist for the Method B placeholder holds
     * (attendee-less tentative events that must not pop reminders);
     * ordinary interview events leave both null and keep Graph's
     * defaults.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CalendarEventRequest(
        String subject,
        ItemBody body,
        DateTimeTimeZone start,
        DateTimeTimeZone end,
        EventLocation location,
        List<Attendee> attendees,
        Boolean isOnlineMeeting,
        String onlineMeetingProvider,
        List<String> categories,
        Integer reminderMinutesBeforeStart,
        String sensitivity,
        String transactionId,
        Boolean responseRequested,
        String showAs,
        Boolean isReminderOn
    ) {
        public record ItemBody(String contentType, String content) { }
        public record DateTimeTimeZone(String dateTime, String timeZone) { }
        public record EventLocation(String displayName) { }
        public record Attendee(EmailAddress emailAddress, String type) {
            public record EmailAddress(String address, String name) { }
        }
    }
}
