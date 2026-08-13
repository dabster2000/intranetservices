package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.CalendarStatusResponse;
import dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomsResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient.CalendarEventRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Outlook calendar bridging for interview scheduling (ATS plan §P11).
 * Ships DARK behind {@code dk.trustworks.recruitment.graph.calendar.enabled}
 * (default {@code false}) — the Graph {@code Calendars.ReadWrite}
 * application permission is a tenant-admin workstream with lead time (plan
 * §4); until IT grants it, scheduling is manual and this service is a
 * silent no-op. The interview model ({@code graph_event_id} nullable)
 * supports both modes, so flipping the property later needs no migration.
 * <p>
 * Failure posture: the intranet interview row is the source of truth — a
 * Graph failure is logged and swallowed, scheduling itself NEVER fails on
 * calendar trouble. {@code graph_event_id} simply stays null (create) or
 * keeps its last value (update/cancel).
 * <p>
 * Event shape: created in the FIRST interviewer's calendar (they act as
 * organizer); remaining interviewers and the candidate (external, when an
 * email exists) are invited as required attendees. Duration is the
 * interview row's {@code duration_minutes} (V474), default 60. The body is
 * written to the candidate whenever they are on the invitation — see
 * {@link #invitationBody}.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentCalendarService {

    static final int DEFAULT_DURATION_MINUTES = 60;

    /** Graph getSchedule accepts at most 20 schedules per call. */
    static final int GET_SCHEDULE_BATCH = 20;

    /**
     * How many caller mailboxes one free/busy batch may try before it is
     * given up as unknown (see {@link #callerCandidates}).
     */
    static final int CALLER_ATTEMPTS = 3;

    /**
     * Interview times are wall-clock as entered by the scheduler (P11
     * findings, deviation 8): {@code scheduledAt} is a naive
     * {@code LocalDateTime} meaning Copenhagen local time. Graph must be
     * told exactly that — an IANA zone id it resolves per date, so CET vs
     * CEST (DST) is handled by Graph, not by us. Never send "UTC" here:
     * Outlook would shift every event by the UTC offset.
     */
    static final String EVENT_TIME_ZONE = "Europe/Copenhagen";

    /**
     * Lazily resolved: the Graph REST client's OIDC filter needs the
     * {@code graph} client credentials, which environments without the
     * toggle (tests, local) may not configure — with the toggle off the
     * client must never be instantiated.
     */
    @Inject
    @RestClient
    Instance<GraphApiClient> graphApiClientInstance;

    /** Test seam; resolved from {@link #graphApiClientInstance} on first use. */
    GraphApiClient graphApiClient;

    @ConfigProperty(name = "dk.trustworks.recruitment.graph.calendar.enabled", defaultValue = "false")
    boolean calendarEnabled;

    /**
     * The shared organizer mailbox new events are created under (plan
     * Phase 2, decision D1: {@code career@trustworks.dk} — env
     * {@code DK_TRUSTWORKS_RECRUITMENT_GRAPH_CALENDAR_ORGANIZER}).
     * Empty (the default) falls back to the first interviewer's mailbox,
     * the pre-V492 behavior — the code ships safely ahead of the config.
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.graph.calendar.organizer", defaultValue = "")
    String configuredOrganizer;

    private GraphApiClient graph() {
        if (graphApiClient == null) {
            graphApiClient = graphApiClientInstance.get();
        }
        return graphApiClient;
    }

    public boolean isEnabled() {
        return calendarEnabled;
    }

    /**
     * What {@link #createEvent} handed to Graph and got back — the caller
     * persists all three so update/cancel address the right mailbox and
     * the UI can offer the Teams link.
     */
    public record CreatedEvent(String eventId, String organizer, String joinUrl) { }

    /**
     * Create the Outlook event for a newly scheduled interview.
     *
     * @return the created event's linkage to store on the interview, or
     *         empty when the toggle is off, no organizer mailbox
     *         resolves, or Graph fails (logged, never thrown)
     */
    public Optional<CreatedEvent> createEvent(RecruitmentInterview interview,
                                              RecruitmentCandidate candidate) {
        if (!calendarEnabled) {
            return Optional.empty();
        }
        try {
            String organizer = organizerMailbox(interview);
            if (organizer == null) {
                log.warnv("Graph calendar: no organizer mailbox resolvable for interview {0} — skipping",
                        interview.getUuid());
                return Optional.empty();
            }
            GraphApiClient.CalendarEvent created = graph().createCalendarEvent(
                    organizer, buildEvent(interview, candidate, true));
            if (created == null || created.id() == null) {
                return Optional.empty();
            }
            return Optional.of(new CreatedEvent(created.id(), organizer, joinUrlOf(created)));
        } catch (Exception e) {
            log.warnv("Graph calendar create failed for interview {0}: {1} — proceeding without calendar event",
                    interview.getUuid(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Push a reschedule (new time/location/attendees, possibly a newly
     * enabled Teams meeting) to the existing Outlook event.
     *
     * @return the join link from the PATCH response when Graph included
     *         one (it may lag — a later read backfills), else empty
     */
    public Optional<String> updateEvent(RecruitmentInterview interview,
                                        RecruitmentCandidate candidate) {
        if (!calendarEnabled || interview.getGraphEventId() == null) {
            return Optional.empty();
        }
        try {
            String organizer = organizerMailbox(interview);
            if (organizer == null) {
                return Optional.empty();
            }
            GraphApiClient.CalendarEvent updated = graph().updateCalendarEvent(
                    organizer, interview.getGraphEventId(),
                    buildEvent(interview, candidate, false));
            return Optional.ofNullable(updated != null ? joinUrlOf(updated) : null);
        } catch (Exception e) {
            log.warnv("Graph calendar update failed for interview {0}: {1} — calendar may be stale",
                    interview.getUuid(), e.getMessage());
            return Optional.empty();
        }
    }

    private static String joinUrlOf(GraphApiClient.CalendarEvent event) {
        return event.onlineMeeting() != null ? event.onlineMeeting().joinUrl() : null;
    }

    /**
     * The Outlook event's live RSVP + drift status (plan Phase 5) — one
     * on-demand Graph read, no webhooks. Unknown ({@code known=false})
     * when the toggle is off, the interview has no event, no organizer
     * resolves, or Graph fails (logged, never thrown).
     */
    public CalendarStatusResponse eventStatus(RecruitmentInterview interview,
                                              RecruitmentCandidate candidate) {
        CalendarStatusResponse unknown =
                new CalendarStatusResponse(false, List.of(), false, null);
        if (!calendarEnabled || interview.getGraphEventId() == null) {
            return unknown;
        }
        try {
            String organizer = organizerMailbox(interview);
            if (organizer == null) {
                return unknown;
            }
            GraphApiClient.CalendarEventDetails details = graph().getCalendarEventDetails(
                    organizer, interview.getGraphEventId(), "start,attendees,onlineMeeting");
            if (details == null) {
                return unknown;
            }
            Map<String, String> emailByInterviewerUuid = new LinkedHashMap<>();
            for (String interviewerUuid : interview.getInterviewerUuids()) {
                String email = userEmail(interviewerUuid);
                if (email != null) {
                    emailByInterviewerUuid.put(interviewerUuid, email);
                }
            }
            return calendarStatus(emailByInterviewerUuid,
                    candidate != null ? candidate.getUuid() : null,
                    candidate != null ? candidate.getEmail() : null,
                    interview.getScheduledAt(), details);
        } catch (Exception e) {
            log.warnv("Graph calendar status read failed for interview {0}: {1} — status unknown",
                    interview.getUuid(), e.getMessage());
            return unknown;
        }
    }

    /**
     * The pure status mapping — package-private so the DB-free tier that
     * gates deploys pins it. Attendee emails are matched
     * case-insensitively; the room (and any attendee we cannot place) is
     * ignored; the organizer's own pseudo-response counts as accepted
     * only when the organizer IS an interviewer (shared-mailbox events
     * never surface it because the mailbox maps to nobody).
     */
    static CalendarStatusResponse calendarStatus(Map<String, String> emailByInterviewerUuid,
                                                 String candidateUuid,
                                                 String candidateEmail,
                                                 LocalDateTime scheduledAt,
                                                 GraphApiClient.CalendarEventDetails details) {
        Map<String, String> interviewerUuidByEmail = new HashMap<>();
        emailByInterviewerUuid.forEach((uuid, email) ->
                interviewerUuidByEmail.put(email.toLowerCase(Locale.ROOT), uuid));
        String candidateEmailLower = candidateEmail != null && !candidateEmail.isBlank()
                ? candidateEmail.toLowerCase(Locale.ROOT)
                : null;

        List<CalendarStatusResponse.Rsvp> rsvps = new ArrayList<>();
        if (details.attendees() != null) {
            for (GraphApiClient.CalendarEventDetails.EventAttendee attendee : details.attendees()) {
                if (attendee.emailAddress() == null || attendee.emailAddress().address() == null) {
                    continue;
                }
                String email = attendee.emailAddress().address().toLowerCase(Locale.ROOT);
                String response = normalizeResponse(
                        attendee.status() != null ? attendee.status().response() : null);
                String interviewerUuid = interviewerUuidByEmail.get(email);
                if (interviewerUuid != null) {
                    rsvps.add(new CalendarStatusResponse.Rsvp(
                            "INTERVIEWER", interviewerUuid, response));
                } else if (candidateEmailLower != null && candidateEmailLower.equals(email)) {
                    rsvps.add(new CalendarStatusResponse.Rsvp(
                            "CANDIDATE", candidateUuid, response));
                }
                // else: the room's auto-response or an unplaceable address — ignored.
            }
        }

        LocalDateTime outlookStart = parseGraphDateTime(
                details.start() != null ? details.start().dateTime() : null);
        boolean drifted = outlookStart != null && scheduledAt != null
                && !outlookStart.equals(scheduledAt);
        return new CalendarStatusResponse(true, List.copyOf(rsvps), drifted, outlookStart);
    }

    /** Graph responses → the four states the UI shows. */
    private static String normalizeResponse(String graphResponse) {
        if (graphResponse == null) {
            return "NONE";
        }
        return switch (graphResponse) {
            case "accepted", "organizer" -> "ACCEPTED";
            case "declined" -> "DECLINED";
            case "tentativelyAccepted" -> "TENTATIVE";
            default -> "NONE"; // none, notResponded, unknown future values
        };
    }

    /**
     * Graph event datetimes carry fractional seconds
     * ({@code 2026-08-20T10:00:00.0000000}); the interview row stores
     * minute precision — truncate so equal wall-clock times compare equal.
     */
    private static LocalDateTime parseGraphDateTime(String dateTime) {
        if (dateTime == null || dateTime.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateTime)
                    .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Cancel the Outlook event (attendees get a cancellation). 404 = already gone, fine. */
    public void cancelEvent(RecruitmentInterview interview) {
        if (!calendarEnabled || interview.getGraphEventId() == null) {
            return;
        }
        try {
            String organizer = organizerMailbox(interview);
            if (organizer == null) {
                return;
            }
            graph().deleteCalendarEvent(organizer, interview.getGraphEventId());
        } catch (WebApplicationException e) {
            if (e.getResponse() == null || e.getResponse().getStatus() != 404) {
                log.warnv("Graph calendar delete failed for interview {0}: {1}",
                        interview.getUuid(), e.getMessage());
            }
        } catch (Exception e) {
            log.warnv("Graph calendar delete failed for interview {0}: {1}",
                    interview.getUuid(), e.getMessage());
        }
    }

    /**
     * The tenant's bookable meeting rooms (Graph
     * {@code /places/microsoft.graph.room}, requires {@code Place.Read.All}).
     * Empty when the toggle is off or Graph fails (logged, never thrown) —
     * the UI hides the room picker and scheduling degrades to free-text
     * location, same posture as event sync.
     */
    public List<MeetingRoomsResponse.MeetingRoom> listRooms() {
        return listRooms(null);
    }

    /**
     * As {@link #listRooms()}, with the default 60-minute slot length.
     */
    public List<MeetingRoomsResponse.MeetingRoom> listRooms(LocalDateTime start) {
        return listRooms(start, DEFAULT_DURATION_MINUTES);
    }

    /**
     * As {@link #listRooms()}, but when {@code start} is given each room
     * additionally carries its free/busy state for the interview slot
     * {@code [start, start + durationMinutes)} (one Graph
     * {@code getSchedule} call for all rooms). Availability failures
     * degrade to {@code available = null} — never to an empty room list;
     * a broken free/busy lookup must not block scheduling.
     */
    public List<MeetingRoomsResponse.MeetingRoom> listRooms(LocalDateTime start, int durationMinutes) {
        if (!calendarEnabled) {
            return List.of();
        }
        List<MeetingRoomsResponse.MeetingRoom> rooms;
        try {
            GraphApiClient.RoomCollectionResponse response = graph().listRooms();
            if (response == null || response.value() == null) {
                return List.of();
            }
            rooms = response.value().stream()
                    .filter(room -> room.emailAddress() != null && !room.emailAddress().isBlank())
                    .map(room -> new MeetingRoomsResponse.MeetingRoom(
                            room.displayName(), room.emailAddress(),
                            room.capacity(), room.building(), null))
                    .toList();
        } catch (Exception e) {
            log.warnv("Graph rooms lookup failed: {0} — returning no rooms", e.getMessage());
            return List.of();
        }
        if (start == null || rooms.isEmpty()) {
            return rooms;
        }
        Map<String, Boolean> freeByRoom = mailboxAvailability(
                rooms.stream().map(MeetingRoomsResponse.MeetingRoom::emailAddress).toList(),
                start, durationMinutes);
        return rooms.stream()
                .map(room -> new MeetingRoomsResponse.MeetingRoom(
                        room.displayName(), room.emailAddress(),
                        room.capacity(), room.building(),
                        freeByRoom.get(room.emailAddress())))
                .toList();
    }

    /**
     * Free/busy per interviewer mailbox for the interview slot — the same
     * strict rule and Graph call as rooms. Empty map when the toggle is
     * off; a mailbox missing from the result means "unknown" (callers
     * show the person unmarked, never as busy). Strictly free/busy — no
     * event details are requested or returned.
     */
    public Map<String, Boolean> interviewerAvailability(List<String> emails,
                                                        LocalDateTime start,
                                                        int durationMinutes) {
        if (!calendarEnabled || emails.isEmpty()) {
            return Map.of();
        }
        return mailboxAvailability(emails, start, durationMinutes);
    }

    /**
     * Free/busy per mailbox (room or person) for
     * {@code [start, start + durationMinutes)} — the Boolean collapse of
     * {@link #mailboxWindowSchedules}: a mailbox is available only when its
     * whole availability view is "0"s — tentative ("1"), busy ("2"),
     * out-of-office ("3") and working-elsewhere ("4") all count as busy.
     * A mailbox whose view is unknown is absent from the map (= unknown,
     * never busy).
     */
    private Map<String, Boolean> mailboxAvailability(List<String> mailboxes,
                                                     LocalDateTime start,
                                                     int durationMinutes) {
        Map<String, Boolean> result = new HashMap<>();
        mailboxWindowSchedules(mailboxes, start, start.plusMinutes(durationMinutes))
                .forEach((mailbox, schedule) -> {
                    if (schedule.availabilityView() != null) {
                        result.put(mailbox,
                                schedule.availabilityView().chars().allMatch(c -> c == '0'));
                    }
                });
        return result;
    }

    /**
     * One day's availability grid: 48 15-minute cells over the 07:00–19:00
     * Europe/Copenhagen probe window, per mailbox (person or room), plus
     * working hours. Strictly free/busy digits — no event details are ever
     * requested. Empty when the toggle is off. Keys echo the probed
     * addresses as Graph returns them — compare case-insensitively.
     */
    public Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> daySchedule(
            List<String> mailboxes, LocalDate date) {
        if (!calendarEnabled || mailboxes.isEmpty()) {
            return Map.of();
        }
        return mailboxWindowSchedules(mailboxes,
                date.atTime(AvailabilitySlotSuggester.DAY_WINDOW_START),
                date.atTime(AvailabilitySlotSuggester.DAY_WINDOW_END));
    }

    /**
     * Ranked slot suggestions for a set of interviewers (see
     * {@link AvailabilitySlotSuggester} for the rules). One multi-day
     * {@code getSchedule} window per 20-mailbox batch — NEVER one call per
     * scanned day: the 10-business-day scan must stay well inside the ALB's
     * 60s idle timeout.
     * <p>
     * Empty when the toggle is off, and also when Graph answered nothing at
     * all for a non-empty probe set — with every schedule unknown the
     * suggester would happily propose every working-hours slot, and blind
     * suggestions carry more trust than they have earned.
     */
    public List<AvailabilitySlotSuggester.Slot> suggestedSlots(List<String> interviewerEmails,
                                                               int durationMinutes,
                                                               LocalDate from,
                                                               int headcount,
                                                               LocalDateTime notBefore) {
        if (!calendarEnabled) {
            return List.of();
        }
        List<MeetingRoomsResponse.MeetingRoom> rooms = listRooms();
        List<String> mailboxes = new ArrayList<>(interviewerEmails);
        rooms.forEach(room -> mailboxes.add(room.emailAddress()));
        if (mailboxes.isEmpty()) {
            return List.of();
        }
        Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> schedules =
                mailboxWindowSchedules(mailboxes,
                        from.atTime(AvailabilitySlotSuggester.DAY_WINDOW_START),
                        lastBusinessDay(from, AvailabilitySlotSuggester.BUSINESS_DAYS)
                                .atTime(AvailabilitySlotSuggester.DAY_WINDOW_END));
        if (schedules.isEmpty()) {
            return List.of();
        }
        Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> byLowercase = new HashMap<>();
        schedules.forEach((mailbox, schedule) ->
                byLowercase.put(mailbox.toLowerCase(Locale.ROOT), schedule));
        return AvailabilitySlotSuggester.suggest(
                from,
                byLowercase,
                interviewerEmails.stream().map(email -> email.toLowerCase(Locale.ROOT)).toList(),
                rooms.stream()
                        .map(room -> new AvailabilitySlotSuggester.RoomOption(
                                room.emailAddress().toLowerCase(Locale.ROOT),
                                room.displayName(), room.capacity()))
                        .toList(),
                durationMinutes, headcount, notBefore);
    }

    /** The date of the {@code businessDays}-th weekday counting from (and
     * including) {@code from} — the far edge of the suggestion scan. */
    static LocalDate lastBusinessDay(LocalDate from, int businessDays) {
        LocalDate day = from;
        int seen = 0;
        while (true) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY
                    && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                seen++;
                if (seen == businessDays) {
                    return day;
                }
            }
            day = day.plusDays(1);
        }
    }

    /**
     * Raw schedules per mailbox (room or person) for
     * {@code [windowStart, windowEnd)} via Graph {@code getSchedule} (max
     * 20 schedules per call — batched), at 15-minute resolution: the
     * availability digit string plus working hours, uncollapsed.
     * Failures are contained per batch (missing = unknown), logged, never
     * thrown.
     * <p>
     * The mailbox in the {@code getSchedule} URL only has to be <em>a</em>
     * valid mailbox — the addresses actually probed ride in the body. It
     * used to be {@code batch.get(0)}, which is how one employee whose
     * mailbox does not exist in the tenant (Graph answers the URL with 404
     * {@code ErrorInvalidUser}) blanked out their whole batch and — the
     * catch sitting outside the loop — every batch after it: in production
     * only the first 20 of ~140 interviewers came back marked. Now a
     * failure costs at most one batch, and the first caller that answers
     * is reused for the rest.
     */
    private Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> mailboxWindowSchedules(
            List<String> mailboxes, LocalDateTime windowStart, LocalDateTime windowEnd) {
        Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> result = new HashMap<>();
        int batches = 0;
        int failedBatches = 0;
        String provenCaller = null;
        for (int i = 0; i < mailboxes.size(); i += GET_SCHEDULE_BATCH) {
            List<String> batch = mailboxes.subList(i,
                    Math.min(i + GET_SCHEDULE_BATCH, mailboxes.size()));
            batches++;
            boolean resolved = false;
            for (String caller : callerCandidates(provenCaller, batch)) {
                Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> batchResult =
                        probeBatch(caller, batch, windowStart, windowEnd);
                if (batchResult == null) {
                    continue;
                }
                provenCaller = caller;
                result.putAll(batchResult);
                resolved = true;
                break;
            }
            if (!resolved) {
                failedBatches++;
            }
        }
        if (failedBatches > 0) {
            log.warnv("Graph free/busy: {0} of {1} batch(es) unresolved — those mailboxes shown without availability",
                    failedBatches, batches);
        }
        return result;
    }

    /**
     * Caller mailboxes to try for one batch: the one already proven to
     * answer, then the batch's own leading addresses. Bounded by
     * {@link #CALLER_ATTEMPTS} — a wholly broken Graph must not turn one
     * sweep into a retry storm.
     */
    private List<String> callerCandidates(String provenCaller, List<String> batch) {
        List<String> candidates = new ArrayList<>();
        if (provenCaller != null) {
            candidates.add(provenCaller);
        }
        for (String mailbox : batch) {
            if (candidates.size() >= CALLER_ATTEMPTS) {
                break;
            }
            if (!candidates.contains(mailbox)) {
                candidates.add(mailbox);
            }
        }
        return candidates;
    }

    /**
     * One {@code getSchedule} call at 15-minute resolution.
     *
     * @return the raw schedule map for this batch, or {@code null} when
     *         Graph failed (logged) — the caller then retries with another
     *         caller mailbox, or leaves the batch unknown
     */
    private Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> probeBatch(
            String caller,
            List<String> batch,
            LocalDateTime windowStart,
            LocalDateTime windowEnd) {
        try {
            GraphApiClient.ScheduleCollectionResponse response = graph().getSchedule(
                    caller,
                    new GraphApiClient.ScheduleRequest(
                            batch,
                            new CalendarEventRequest.DateTimeTimeZone(
                                    windowStart.toString(), EVENT_TIME_ZONE),
                            new CalendarEventRequest.DateTimeTimeZone(
                                    windowEnd.toString(), EVENT_TIME_ZONE),
                            AvailabilitySlotSuggester.INTERVAL_MINUTES));
            if (response == null || response.value() == null) {
                return Map.of();
            }
            Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> batchResult = new HashMap<>();
            for (GraphApiClient.ScheduleCollectionResponse.ScheduleInformation info : response.value()) {
                if (info.scheduleId() == null) {
                    continue;
                }
                batchResult.put(info.scheduleId(),
                        new AvailabilitySlotSuggester.MailboxWindowSchedule(
                                info.availabilityView(), toWorkingHours(info.workingHours())));
            }
            return batchResult;
        } catch (Exception e) {
            log.warnv("Graph free/busy lookup failed asking as {0}: {1}", caller, e.getMessage());
            return null;
        }
    }

    /**
     * Normalize Graph working hours ({@code "08:00:00.0000000"}, lowercase
     * day names) into the suggester's shape. Unparseable parts degrade to
     * null (= unconstrained) — working hours refine suggestions, they must
     * never break availability itself.
     */
    static AvailabilitySlotSuggester.WorkingHours toWorkingHours(
            GraphApiClient.ScheduleCollectionResponse.ScheduleInformation.WorkingHours workingHours) {
        if (workingHours == null) {
            return null;
        }
        Set<DayOfWeek> days = null;
        if (workingHours.daysOfWeek() != null) {
            days = new HashSet<>();
            for (String day : workingHours.daysOfWeek()) {
                try {
                    days.add(DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException | NullPointerException e) {
                    // unknown token — skip, never fail the sweep
                }
            }
        }
        LocalTime start = parseGraphTime(workingHours.startTime());
        LocalTime end = parseGraphTime(workingHours.endTime());
        String timeZoneName = workingHours.timeZone() != null ? workingHours.timeZone().name() : null;
        if ((days == null || days.isEmpty()) && start == null && end == null) {
            return null;
        }
        return new AvailabilitySlotSuggester.WorkingHours(days, start, end, timeZoneName);
    }

    private static LocalTime parseGraphTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(time);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ---- Shaping -----------------------------------------------------------

    /**
     * The full event body. {@code create} gates the create-only fields:
     * {@code transactionId} (Graph rejects it on PATCH) rides only on
     * creates — the interview UUID, so a retry-stormed create never
     * double-books.
     */
    private CalendarEventRequest buildEvent(RecruitmentInterview interview,
                                            RecruitmentCandidate candidate,
                                            boolean create) {
        Objects.requireNonNull(interview.getScheduledAt(), "scheduledAt must be set before calendar sync");
        // The position is named nowhere on the invitation — not here and
        // not in the body: the candidate is an attendee, and what they are
        // being seen for is not the invitation's business.
        String subject = interview.getKind() == RecruitmentInterviewKind.INFORMAL
                ? "Uformel snak: %s".formatted(candidateName(candidate))
                : "Interview %d: %s".formatted(interview.getRound(), candidateName(candidate));

        List<CalendarEventRequest.Attendee> attendees = new ArrayList<>();
        List<String> interviewers = interview.getInterviewerUuids();
        for (int i = 1; i < interviewers.size(); i++) {
            // Name as well as address: the candidate's mail client cannot
            // resolve a Trustworks address against our directory, so a
            // nameless attendee shows up as a raw firstname.lastname@… .
            User user = User.findById(interviewers.get(i));
            String email = mailboxOf(user);
            if (email != null) {
                attendees.add(required(email, displayName(user)));
            }
        }
        boolean candidateInvited = candidate.getEmail() != null && !candidate.getEmail().isBlank();
        if (candidateInvited) {
            attendees.add(required(candidate.getEmail(), candidateName(candidate)));
        }
        if (interview.getRoomEmail() != null && !interview.getRoomEmail().isBlank()) {
            // Graph's room-booking convention: the room mailbox is invited
            // as a "resource" attendee, which books the room's calendar.
            attendees.add(new CalendarEventRequest.Attendee(
                    new CalendarEventRequest.Attendee.EmailAddress(
                            interview.getRoomEmail(), interview.getLocation()),
                    "resource"));
        }

        // Teams fields are one-way: TRUE turns the meeting online (works on
        // both create and PATCH — verified in this tenant, Phase 0.3 spike);
        // FALSE is never sent, so an existing Teams meeting is not silently
        // stripped by an unrelated reschedule.
        boolean teams = interview.isOnlineMeeting();
        return new CalendarEventRequest(
                subject,
                new CalendarEventRequest.ItemBody("text",
                        invitationBody(interview, candidate, candidateInvited)),
                new CalendarEventRequest.DateTimeTimeZone(
                        interview.getScheduledAt().toString(), EVENT_TIME_ZONE),
                new CalendarEventRequest.DateTimeTimeZone(
                        interview.getScheduledAt().plusMinutes(interview.getDurationMinutes()).toString(),
                        EVENT_TIME_ZONE),
                interview.getLocation() != null
                        ? new CalendarEventRequest.EventLocation(interview.getLocation())
                        : null,
                attendees,
                teams ? Boolean.TRUE : null,
                teams ? "teamsForBusiness" : null,
                List.of("Recruitment"),
                15,
                // D3: the informative subject stays; the event itself is
                // marked private so shared-calendar viewers see busy only.
                "private",
                create ? interview.getUuid() : null,
                Boolean.TRUE);
    }

    /**
     * The invitation body. Whenever the candidate has an email they are a
     * required attendee, so this text is read by someone outside the
     * company: it is addressed to them, in Danish like every other
     * candidate-facing template (V446), and carries no internal links. It
     * used to be the internal note "Scheduled via the Trustworks intranet —
     * see /recruitment/interviews for the interview kit", which named a
     * path no candidate can open.
     * <p>
     * The position is deliberately NOT named here — the body greets and
     * sets expectations, nothing more.
     * <p>
     * Interviewers read the same body — one event, one body — and lose
     * nothing: the kit reaches them through "My interviews" and the Slack
     * card, not through here.
     * <p>
     * With no candidate email the event is interviewers-only and the
     * internal note is kept: nobody external can see it, and the pointer is
     * useful there.
     * <p>
     * Package-private on purpose: pure text, so it is covered by a plain
     * unit test in the DB-free tier that gates deploys, rather than only by
     * the ungated {@code @QuarkusTest} tier.
     */
    static String invitationBody(RecruitmentInterview interview,
                                 RecruitmentCandidate candidate,
                                 boolean candidateInvited) {
        if (!candidateInvited) {
            return "Scheduled via the Trustworks intranet — see /recruitment/interviews for the interview kit.";
        }
        // Danish addresses people by first name; without one, greet
        // namelessly rather than "Kære <surname>", which reads wrong.
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName().trim();
        String greeting = first.isEmpty() ? "Hej" : "Kære " + first;
        String occasion = interview.getKind() == RecruitmentInterviewKind.INFORMAL
                ? "en uformel snak med dig"
                : "at møde dig til samtale";
        // Time, place and participants are the invitation's own fields —
        // repeating them here only invites drift when the event is updated.
        return """
                %s

                Vi glæder os til %s hos Trustworks.

                Er du forhindret, eller har du spørgsmål inden vi ses, er du \
                velkommen til at svare på denne invitation.

                Med venlig hilsen
                Trustworks""".formatted(greeting, occasion);
    }

    private static CalendarEventRequest.Attendee required(String email, String name) {
        return new CalendarEventRequest.Attendee(
                new CalendarEventRequest.Attendee.EmailAddress(email, name), "required");
    }

    /**
     * The mailbox to address the event under, in order (Phase 2 organizer
     * hardening):
     * <ol>
     *   <li>the interview's STORED organizer — update/cancel must PATCH the
     *       mailbox the event actually lives in, whatever the interviewer
     *       list looks like today;</li>
     *   <li>the configured shared mailbox (D1, {@code career@trustworks.dk})
     *       for new events;</li>
     *   <li>the first interviewer's mailbox — the pre-V492 fallback while
     *       the config is empty.</li>
     * </ol>
     */
    private String organizerMailbox(RecruitmentInterview interview) {
        if (interview.getGraphOrganizer() != null && !interview.getGraphOrganizer().isBlank()) {
            return interview.getGraphOrganizer();
        }
        if (configuredOrganizer != null && !configuredOrganizer.isBlank()) {
            return configuredOrganizer.trim();
        }
        List<String> interviewers = interview.getInterviewerUuids();
        if (interviewers == null || interviewers.isEmpty()) {
            return null;
        }
        return userEmail(interviewers.get(0));
    }

    private String userEmail(String userUuid) {
        return mailboxOf(User.findById(userUuid));
    }

    private static String mailboxOf(User user) {
        return user != null && user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail()
                : null;
    }

    /**
     * The attendee's display name, or null when the user has no usable
     * name — {@code User.getFullname()} is unguarded and would render
     * "null null" for a half-filled row, which is worse than the bare
     * address Outlook falls back to.
     */
    static String displayName(User user) {
        if (user == null) {
            return null;
        }
        String first = user.getFirstname() == null ? "" : user.getFirstname().trim();
        String last = user.getLastname() == null ? "" : user.getLastname().trim();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? null : name;
    }

    private static String candidateName(RecruitmentCandidate candidate) {
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        return (first + " " + last).trim();
    }
}
