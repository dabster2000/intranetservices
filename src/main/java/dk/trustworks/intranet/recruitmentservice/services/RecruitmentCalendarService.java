package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.CalendarStatusResponse;
import dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomsResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailBodyFormat;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewKind;
import dk.trustworks.intranet.graph.GraphCalendarClient;
import dk.trustworks.intranet.graph.GraphCalendarClient.CalendarEventRequest;
import dk.trustworks.intranet.graph.GraphMailboxConcurrencyLimiter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Event shape: created under the mailbox {@link #organizerMailbox} resolves
 * — since V492 the shared {@code career@trustworks.dk}, before that the
 * first interviewer's own calendar. EVERY interviewer is invited as a
 * required attendee except the one whose mailbox IS the organizer, because
 * Outlook derives the organizer from the hosting mailbox and listing them
 * again duplicates them. The exclusion is by mailbox identity, never by
 * list position: it was positional until V492 moved the organizer to a
 * shared mailbox, at which point interviewer #1 silently stopped being
 * invited. The candidate (external, when an email exists) is invited on
 * their own event since V493. Duration is the interview row's
 * {@code duration_minutes} (V474), default 60. The body is written to the
 * candidate whenever they are on the invitation — see {@link #invitationBody}.
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
     * Wait applied once to a throttled (429) batch when Graph sent no
     * {@code Retry-After}, and the ceiling applied when it did. Graph can
     * ask for an hour; a request-scoped caller sitting behind the ALB's 60s
     * idle timeout cannot honour that, so we honour what we can and give up
     * honestly rather than hang.
     */
    static final long THROTTLE_DEFAULT_WAIT_MS = 1_000L;
    static final long THROTTLE_MAX_WAIT_MS = 5_000L;

    /**
     * Total time one sweep may spend BLOCKED — throttle backoff and waiting
     * for a concurrency permit, together.
     * <p>
     * A sweep is 7-8 sequential batches, each of which may climb a 3-rung
     * caller ladder, so any PER-BATCH bound is multiplied by ~24 before the
     * caller feels it. The request sits behind the ALB's 60s idle timeout,
     * and a fix that turns 429s into 504s would be worse than the bug it
     * replaces.
     * <p>
     * Both waits share ONE budget deliberately: they are the same scarce
     * resource — the caller's patience — and budgeting them separately is
     * exactly how you bound each and still blow the sum.
     */
    static final long BLOCKING_BUDGET_MS = 10_000L;

    /**
     * The blocking allowance for a single sweep, spent down by permit waits
     * and throttle backoff alike. Deliberately not thread-safe: one instance
     * belongs to one sweep on one thread.
     */
    static final class SweepBudget {
        private long remainingMs = BLOCKING_BUDGET_MS;

        /** Grant up to {@code requestedMs}, never more than is left. */
        long take(long requestedMs) {
            long granted = Math.max(0L, Math.min(requestedMs, remainingMs));
            remainingMs -= granted;
            return granted;
        }

        long remaining() {
            return remainingMs;
        }
    }

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
    Instance<GraphCalendarClient> graphApiClientInstance;

    /** Test seam; resolved from {@link #graphApiClientInstance} on first use. */
    GraphCalendarClient graphApiClient;

    /**
     * The tenant-side MailboxConcurrency guard. Application-scoped on
     * purpose: the cap is per app per mailbox, so nothing request-scoped can
     * enforce it (production 2026-08-15).
     */
    @Inject
    GraphMailboxConcurrencyLimiter mailboxLimiter;

    /** Which rooms automation may book, and in what order (V513). One-way
     * edge: the policy bean takes rooms as input and never calls back here. */
    @Inject
    RecruitmentMeetingRoomPolicyService roomPolicyService;

    /**
     * The HR-editable visiting address the candidate is told to turn up at
     * (V553). Read through {@link #visitingAddress()}, never directly: the
     * field is null in the unit tests that build this service with a bare
     * {@code new}, exactly like {@link #configuredOrganizerValue}.
     */
    @Inject
    RecruitmentVisitingAddress visitingAddressSetting;

    /**
     * Rotates which mailbox anchors the {@code getSchedule} URL. Shared
     * across requests — the whole point is that two concurrent sweeps do
     * not pick the same anchor. Package-private so tests can pin it.
     */
    final AtomicInteger callerCursor = new AtomicInteger();

    /**
     * Addresses Graph has answered 404 {@code ErrorInvalidUser} for as a
     * caller — employees with no mailbox in the tenant. Remembered for the
     * process lifetime so the rotation stops paying for them: without this
     * memo, rotating the anchor would re-discover the same dead addresses on
     * every sweep, which is the one thing the old sticky-caller design was
     * actually good at.
     */
    private final Set<String> mailboxLessCallers = ConcurrentHashMap.newKeySet();

    /** Test seam so throttle backoff is asserted without wall-clock time. */
    java.util.function.LongConsumer sleeper = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    /** Rooms requested per Graph places page. */
    private static final int ROOM_PAGE_SIZE = 100;
    /** Runaway guard on room pagination — a tenant with 10 000 rooms is a
     * bug somewhere, not a room list worth waiting for. */
    private static final int ROOM_PAGE_LIMIT = 100;

    @ConfigProperty(name = "dk.trustworks.recruitment.graph.calendar.enabled", defaultValue = "false")
    boolean calendarEnabled;

    /**
     * The shared organizer mailbox new events are created under (plan
     * Phase 2, decision D1: {@code career@trustworks.dk} — env
     * {@code DK_TRUSTWORKS_RECRUITMENT_GRAPH_CALENDAR_ORGANIZER}).
     * Absent falls back to the first interviewer's mailbox, the
     * pre-V492 behavior — the code ships safely ahead of the config.
     * <p>
     * {@code Optional<String>} deliberately — NOT a plain String with
     * {@code defaultValue = ""}: SmallRye converts an empty string to
     * null, which makes the property REQUIRED and fails every boot
     * without the env var (SRCFG00014) — the cvtool.username trap. This
     * exact shape took the whole {@code @QuarkusTest} tier down between
     * the Method A merge and the Method B validation phase.
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.graph.calendar.organizer")
    Optional<String> configuredOrganizerValue;

    /** The organizer mailbox, or null when unconfigured (fallback applies).
     * Null-tolerant on the FIELD too: unit tests build this service with
     * bare {@code new}, where no injection ever ran. */
    private String configuredOrganizer() {
        if (configuredOrganizerValue == null) {
            return null;
        }
        return configuredOrganizerValue.filter(v -> !v.isBlank())
                .map(String::trim).orElse(null);
    }

    private GraphCalendarClient graph() {
        if (graphApiClient == null) {
            graphApiClient = graphApiClientInstance.get();
        }
        return graphApiClient;
    }

    /**
     * The visiting address invitations should carry, or null when it is
     * unconfigured or deliberately blanked. Null-tolerant on the FIELD too:
     * unit tests build this service with bare {@code new}, where no
     * injection ever ran.
     */
    private String visitingAddress() {
        return visitingAddressSetting == null ? null : visitingAddressSetting.effectiveAddress();
    }

    public boolean isEnabled() {
        return calendarEnabled;
    }

    /**
     * What {@link #createEvent} handed to Graph and got back — the caller
     * persists it all so update/cancel address the right mailboxes and
     * the UI can offer the Teams link. {@code candidateEventId} is null
     * when no candidate event was created (no email, or its create
     * failed — the internal event stands alone, exactly the pre-split
     * shape).
     */
    public record CreatedEvent(String eventId, String organizer, String joinUrl,
                               String candidateEventId) { }

    /**
     * One Graph write failure, CLASSIFIED for the caller — the difference
     * between "queue a retry" and "tell a human now". Retryable = Graph
     * asked us to slow down (429), answered 5xx/408 (the 2026-08-24
     * candidate-invite 504), or the call never completed (timeouts, broken
     * connections). Everything else — a permanent 4xx, or a bug of our own
     * — retries identically forever, so it must go to a person instead.
     * {@code graphRequestId} is Graph's correlation id when one came back.
     */
    public record GraphWriteFailure(boolean retryable, String message, String graphRequestId) { }

    /**
     * What {@link #createEvent} actually did, failure classification
     * included. {@code created} is null when no internal event exists —
     * toggle off, no organizer, or the internal create failed (then
     * {@code internalFailure} says how). {@code candidateFailure} is set
     * when the internal event stands but the candidate's own event could
     * not be created; a candidate without an email sets neither (no
     * invitation was ever intended — the internal body says so).
     */
    public record CreateResult(CreatedEvent created,
                               GraphWriteFailure internalFailure,
                               GraphWriteFailure candidateFailure) {

        static CreateResult skipped() {
            return new CreateResult(null, null, null);
        }
    }

    /** A candidate-event write on its own (create or PATCH): the new/kept
     * event id on success, or the classified failure. */
    public record CandidateEventOutcome(String candidateEventId, GraphWriteFailure failure) { }

    /**
     * What {@link #updateEvent} did. {@code candidateUpdated} is true only
     * when a SPLIT row's candidate event was actually PATCHed — the signal
     * the timeline needs to say the candidate's invitation was re-issued.
     */
    public record UpdateResult(String joinUrl,
                               boolean candidateUpdated,
                               GraphWriteFailure candidateFailure) {

        public Optional<String> joinUrlIfPresent() {
            return Optional.ofNullable(joinUrl);
        }
    }

    /** What {@link #cancelEvent} did: the first classified failure among
     * the deletes, or null when every event is gone (404 counts as gone). */
    public record CancelResult(GraphWriteFailure failure) {

        public boolean allDeleted() {
            return failure == null;
        }
    }

    /**
     * Classify one Graph client exception (see {@link GraphWriteFailure}).
     * Walks the cause chain for the wrapped-timeout shapes the REST client
     * produces ({@code ProcessingException} around an {@code IOException}).
     * Package-private and pure so the DB-free tier that gates deploys pins
     * the 504-is-retryable rule directly.
     */
    static GraphWriteFailure classifyGraphFailure(Exception e) {
        if (e instanceof dk.trustworks.intranet.graph
                .GraphResponseExceptionMapper.GraphApiException graphError) {
            boolean retryable = graphError.isThrottled()
                    || graphError.isServerError()
                    || graphError.getStatusCode() == 408;
            return new GraphWriteFailure(retryable, graphError.getMessage(),
                    graphError.getRequestId());
        }
        if (e instanceof WebApplicationException webError && webError.getResponse() != null) {
            int status = webError.getResponse().getStatus();
            boolean retryable = status >= 500 || status == 429 || status == 408;
            return new GraphWriteFailure(retryable, e.getMessage(), null);
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof jakarta.ws.rs.ProcessingException) {
                return new GraphWriteFailure(true, e.getMessage(), null);
            }
        }
        // A surprise (our own bug included): retrying reruns the same code
        // on the same data — hand it to a person instead.
        return new GraphWriteFailure(false, e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""), null);
    }

    /**
     * The result of a free/busy sweep, with its own gaps admitted.
     * <p>
     * The distinction that matters — and the reason this is a record rather
     * than a bare map — is <em>absent</em> versus <em>unresolved</em>:
     * <ul>
     *   <li>a mailbox Graph answered WITHOUT is genuinely mailbox-less (a
     *       permanent, unremarkable condition — plenty of employees have no
     *       tenant mailbox). It is simply unknown;</li>
     *   <li>a mailbox in {@code unresolvedMailboxes} is one we never managed
     *       to ASK about — its batch was throttled, errored, or never got a
     *       concurrency permit. We know nothing, and we know that we were
     *       supposed to know something.</li>
     * </ul>
     * Both stay "unknown never counts as busy" for marking purposes. Only
     * the second may suppress a positive claim such as a suggested slot —
     * conflating them would disable suggestions forever for any team
     * containing one mailbox-less member.
     * <p>
     * Keys of {@code schedules} echo the addresses as Graph returned them;
     * {@code unresolvedMailboxes} are lowercased.
     */
    public record ScheduleProbe(
            Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> schedules,
            Set<String> unresolvedMailboxes) {

        /** True when every probed mailbox was actually asked about. */
        public boolean complete() {
            return unresolvedMailboxes.isEmpty();
        }

        /** True when any of {@code mailboxes} (any casing) went unasked. */
        public boolean anyUnresolved(List<String> mailboxes) {
            return mailboxes.stream()
                    .anyMatch(mailbox -> unresolvedMailboxes.contains(mailbox.toLowerCase(Locale.ROOT)));
        }

        static ScheduleProbe empty() {
            return new ScheduleProbe(Map.of(), Set.of());
        }
    }

    /**
     * Free/busy booleans plus the mailboxes the sweep never managed to ask
     * about — the {@link ScheduleProbe} shape for the collapsed Boolean
     * surfaces. A mailbox in {@code unresolvedMailboxes} is absent from
     * {@code freeByMailbox}; callers must render it unknown and may tell the
     * user the picture is incomplete, but must never render it free.
     */
    public record AvailabilityProbe(Map<String, Boolean> freeByMailbox,
                                    Set<String> unresolvedMailboxes) {

        public boolean complete() {
            return unresolvedMailboxes.isEmpty();
        }

        static AvailabilityProbe empty() {
            return new AvailabilityProbe(Map.of(), Set.of());
        }
    }

    /**
     * Ranked slots plus whether the availability behind them was fully read.
     * <p>
     * The two carry very different meanings for an empty list, and the UI
     * must be able to tell them apart: {@code availabilityComplete=true}
     * with no slots means "we looked at everyone's calendar and there is
     * genuinely nothing free"; {@code false} means "we could not read some
     * calendar, so we are not proposing anything". Reporting the second as
     * the first is precisely the silent gap this record exists to close.
     */
    public record SlotSuggestions(List<AvailabilitySlotSuggester.Slot> slots,
                                  boolean availabilityComplete) {

        static SlotSuggestions none(boolean complete) {
            return new SlotSuggestions(List.of(), complete);
        }
    }

    /**
     * Create the Outlook events for a newly scheduled interview (plan
     * Phase 6 two-event split): the INTERNAL event for interviewers +
     * room, and — when the candidate has an email — the candidate's OWN
     * event with a candidate-facing HTML body, carrying only them.
     * <p>
     * Never throws — scheduling must not fail on calendar trouble — but
     * failures come back CLASSIFIED so the caller can queue a retry or
     * alert a person instead of silently accepting the degrade. Both
     * creates carry a {@code transactionId} (the interview UUID, with a
     * {@code -candidate} suffix on the split event), so a retry of a
     * failure that actually succeeded behind a gateway (the 504 shape)
     * never double-books or double-invites.
     *
     * @return what happened; {@link CreateResult#created()} is null when
     *         the toggle is off, no organizer mailbox resolves, or the
     *         internal create failed
     */
    public CreateResult createEvent(RecruitmentInterview interview,
                                    RecruitmentCandidate candidate,
                                    RecruitmentPosition position) {
        if (!calendarEnabled) {
            return CreateResult.skipped();
        }
        String organizer = organizerMailbox(interview);
        if (organizer == null) {
            log.warnv("Graph calendar: no organizer mailbox resolvable for interview {0} — skipping",
                    interview.getUuid());
            return CreateResult.skipped();
        }
        GraphCalendarClient.CalendarEvent created;
        try {
            created = graph().createCalendarEvent(
                    organizer, buildInternalEvent(interview, candidate, position, true, false, organizer));
        } catch (Exception e) {
            GraphWriteFailure failure = classifyGraphFailure(e);
            log.warnv("Graph calendar create failed for interview {0}: {1} — proceeding without calendar event ({2})",
                    interview.getUuid(), e.getMessage(),
                    failure.retryable() ? "queued for retry" : "not retryable");
            return new CreateResult(null, failure, null);
        }
        if (created == null || created.id() == null) {
            return CreateResult.skipped();
        }
        String joinUrl = joinUrlOf(created);
        CandidateEventOutcome candidateOutcome =
                createCandidateEvent(interview, candidate, position, organizer, joinUrl);
        return new CreateResult(
                new CreatedEvent(created.id(), organizer, joinUrl,
                        candidateOutcome.candidateEventId()),
                null,
                candidateOutcome.failure());
    }

    /**
     * The candidate's own event, for an interview whose INTERNAL event
     * already exists — the repair path for a row where the inline create
     * degraded to internal-only (or a legacy/no-email row that has since
     * gained an address). Uses the stored join link; the create-time
     * {@code transactionId} makes replays safe.
     */
    public CandidateEventOutcome createCandidateEventFor(RecruitmentInterview interview,
                                                         RecruitmentCandidate candidate,
                                                         RecruitmentPosition position) {
        if (!calendarEnabled) {
            return new CandidateEventOutcome(null, null);
        }
        String organizer = organizerMailbox(interview);
        if (organizer == null) {
            return new CandidateEventOutcome(null, null);
        }
        return createCandidateEvent(interview, candidate, position, organizer,
                interview.getJoinUrl());
    }

    /**
     * Re-PATCH the candidate's event with the interview's CURRENT facts —
     * the repair path for a reschedule whose candidate half failed,
     * leaving the candidate holding an invitation with the OLD time.
     * Success = null failure.
     */
    public GraphWriteFailure patchCandidateEvent(RecruitmentInterview interview,
                                                 RecruitmentCandidate candidate,
                                                 RecruitmentPosition position) {
        if (!calendarEnabled || interview.getGraphCandidateEventId() == null) {
            return null;
        }
        String organizer = organizerMailbox(interview);
        if (organizer == null) {
            return null;
        }
        try {
            graph().updateCalendarEvent(candidateOrganizer(organizer),
                    interview.getGraphCandidateEventId(),
                    buildCandidateEvent(interview, candidate, position,
                            interview.getJoinUrl(), false));
            return null;
        } catch (Exception e) {
            GraphWriteFailure failure = classifyGraphFailure(e);
            log.warnv("Graph candidate-event re-PATCH failed for interview {0}: {1}",
                    interview.getUuid(), e.getMessage());
            return failure;
        }
    }

    /** The candidate event create, classified; not-applicable (no email)
     * reports neither an id nor a failure. */
    private CandidateEventOutcome createCandidateEvent(RecruitmentInterview interview,
                                                       RecruitmentCandidate candidate,
                                                       RecruitmentPosition position,
                                                       String internalOrganizer,
                                                       String joinUrl) {
        if (candidate == null || candidate.getEmail() == null || candidate.getEmail().isBlank()) {
            return new CandidateEventOutcome(null, null);
        }
        try {
            GraphCalendarClient.CalendarEvent created = graph().createCalendarEvent(
                    candidateOrganizer(internalOrganizer),
                    buildCandidateEvent(interview, candidate, position, joinUrl, true));
            return new CandidateEventOutcome(created != null ? created.id() : null, null);
        } catch (Exception e) {
            GraphWriteFailure failure = classifyGraphFailure(e);
            log.warnv("Graph candidate-event create failed for interview {0}: {1} — internal event stands alone ({2})",
                    interview.getUuid(), e.getMessage(),
                    failure.retryable() ? "queued for retry" : "not retryable");
            return new CandidateEventOutcome(null, failure);
        }
    }

    /**
     * Push a reschedule (new time/location/attendees, possibly a newly
     * enabled Teams meeting) to the existing Outlook event(s). Split rows
     * ({@code graph_candidate_event_id} set) update both events; legacy
     * single-event rows keep the candidate as an attendee of the one
     * event, exactly as before the split.
     *
     * @return the join link from the internal PATCH response when Graph
     *         included one (it may lag — a later read backfills), plus
     *         whether the candidate's own event was actually re-issued
     *         and, when it was not, the classified failure — a candidate
     *         holding an invitation with the OLD time is worse than one
     *         holding none, so that failure must never stay a WARN
     */
    public UpdateResult updateEvent(RecruitmentInterview interview,
                                    RecruitmentCandidate candidate,
                                    RecruitmentPosition position) {
        if (!calendarEnabled || interview.getGraphEventId() == null) {
            return new UpdateResult(null, false, null);
        }
        try {
            String organizer = organizerMailbox(interview);
            if (organizer == null) {
                return new UpdateResult(null, false, null);
            }
            boolean split = interview.getGraphCandidateEventId() != null;
            GraphCalendarClient.CalendarEvent updated = graph().updateCalendarEvent(
                    organizer, interview.getGraphEventId(),
                    buildInternalEvent(interview, candidate, position, false, !split, organizer));
            String joinUrl = updated != null ? joinUrlOf(updated) : null;
            if (split) {
                try {
                    graph().updateCalendarEvent(candidateOrganizer(organizer),
                            interview.getGraphCandidateEventId(),
                            buildCandidateEvent(interview, candidate, position,
                                    joinUrl != null ? joinUrl : interview.getJoinUrl(), false));
                    return new UpdateResult(joinUrl, true, null);
                } catch (Exception e) {
                    GraphWriteFailure failure = classifyGraphFailure(e);
                    log.warnv("Graph candidate-event update failed for interview {0}: {1} — candidate event may be stale ({2})",
                            interview.getUuid(), e.getMessage(),
                            failure.retryable() ? "queued for retry" : "not retryable");
                    return new UpdateResult(joinUrl, false, failure);
                }
            }
            return new UpdateResult(joinUrl, false, null);
        } catch (Exception e) {
            log.warnv("Graph calendar update failed for interview {0}: {1} — calendar may be stale",
                    interview.getUuid(), e.getMessage());
            // On a split row the internal PATCH failing means the candidate
            // PATCH never ran either — the candidate's invitation still
            // shows the OLD time. Report it so the repair sweep re-issues it.
            return new UpdateResult(null, false,
                    interview.getGraphCandidateEventId() != null
                            ? classifyGraphFailure(e)
                            : null);
        }
    }

    private static String joinUrlOf(GraphCalendarClient.CalendarEvent event) {
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
            GraphCalendarClient.CalendarEventDetails details = graph().getCalendarEventDetails(
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
            // Split rows: the candidate answers on THEIR event, not the
            // internal one — map the candidate only from there.
            boolean split = interview.getGraphCandidateEventId() != null;
            CalendarStatusResponse status = calendarStatus(emailByInterviewerUuid,
                    candidate != null ? candidate.getUuid() : null,
                    split ? null : (candidate != null ? candidate.getEmail() : null),
                    interview.getScheduledAt(), details);
            if (!split || candidate == null || candidate.getEmail() == null) {
                return status;
            }
            try {
                GraphCalendarClient.CalendarEventDetails candidateDetails =
                        graph().getCalendarEventDetails(candidateOrganizer(organizer),
                                interview.getGraphCandidateEventId(),
                                "start,attendees,onlineMeeting");
                CalendarStatusResponse candidateStatus = calendarStatus(Map.of(),
                        candidate.getUuid(), candidate.getEmail(),
                        interview.getScheduledAt(), candidateDetails);
                List<CalendarStatusResponse.Rsvp> combined = new ArrayList<>(status.rsvps());
                combined.addAll(candidateStatus.rsvps());
                return new CalendarStatusResponse(true, List.copyOf(combined),
                        status.drifted(), status.outlookStart());
            } catch (Exception e) {
                log.warnv("Graph candidate-event status read failed for interview {0}: {1} — interviewers only",
                        interview.getUuid(), e.getMessage());
                return status;
            }
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
     * <p>
     * Every assigned interviewer gets a row. One who matches no attendee
     * line is reported MISSING — never invited — rather than omitted, so
     * an attendee-list defect is visible instead of silent. Callers pass
     * an empty interviewer map for the candidate event, which therefore
     * yields no MISSING rows.
     */
    static CalendarStatusResponse calendarStatus(Map<String, String> emailByInterviewerUuid,
                                                 String candidateUuid,
                                                 String candidateEmail,
                                                 LocalDateTime scheduledAt,
                                                 GraphCalendarClient.CalendarEventDetails details) {
        Map<String, String> interviewerUuidByEmail = new HashMap<>();
        emailByInterviewerUuid.forEach((uuid, email) ->
                interviewerUuidByEmail.put(email.toLowerCase(Locale.ROOT), uuid));
        String candidateEmailLower = candidateEmail != null && !candidateEmail.isBlank()
                ? candidateEmail.toLowerCase(Locale.ROOT)
                : null;

        List<CalendarStatusResponse.Rsvp> rsvps = new ArrayList<>();
        Set<String> placedInterviewerUuids = new HashSet<>();
        if (details.attendees() != null) {
            for (GraphCalendarClient.CalendarEventDetails.EventAttendee attendee : details.attendees()) {
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
                    placedInterviewerUuids.add(interviewerUuid);
                } else if (candidateEmailLower != null && candidateEmailLower.equals(email)) {
                    rsvps.add(new CalendarStatusResponse.Rsvp(
                            "CANDIDATE", candidateUuid, response));
                }
                // else: the room's auto-response or an unplaceable address — ignored.
            }
        }
        // An assigned interviewer who is on NO attendee line has not "not
        // answered" — they were never invited, and every other surface
        // (Slack kit DM, My interviews, the tab itself) is meanwhile telling
        // them they are booked. Report that as its own state instead of as
        // an absent row: silent omission is how the V492 attendee drop
        // survived unnoticed in production.
        emailByInterviewerUuid.keySet().stream()
                .filter(uuid -> !placedInterviewerUuids.contains(uuid))
                .forEach(uuid -> rsvps.add(new CalendarStatusResponse.Rsvp(
                        "INTERVIEWER", uuid, "MISSING")));

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

    /** Cancel the Outlook event(s) — attendees get cancellations; the
     * candidate event (when split) goes with the internal one. 404 =
     * already gone, fine. A failed delete is reported classified: a
     * candidate keeping a live invitation to a CANCELLED interview will
     * show up at the door. */
    public CancelResult cancelEvent(RecruitmentInterview interview) {
        if (!calendarEnabled || interview.getGraphEventId() == null) {
            return new CancelResult(null);
        }
        String organizer = organizerMailbox(interview);
        if (organizer == null) {
            return new CancelResult(null);
        }
        GraphWriteFailure internalFailure =
                deleteQuietly(organizer, interview.getGraphEventId(), interview);
        GraphWriteFailure candidateFailure = null;
        if (interview.getGraphCandidateEventId() != null) {
            candidateFailure = deleteQuietly(candidateOrganizer(organizer),
                    interview.getGraphCandidateEventId(), interview);
        }
        // The candidate-facing failure outranks the internal one.
        return new CancelResult(candidateFailure != null ? candidateFailure : internalFailure);
    }

    /** Delete one event; 404 = already gone = success (null). */
    private GraphWriteFailure deleteQuietly(String organizer, String eventId,
                                            RecruitmentInterview interview) {
        try {
            graph().deleteCalendarEvent(organizer, eventId);
            return null;
        } catch (Exception e) {
            if (isGraphNotFound(e)) {
                return null;
            }
            log.warnv("Graph calendar delete failed for interview {0}: {1}",
                    interview.getUuid(), e.getMessage());
            return classifyGraphFailure(e);
        }
    }

    /**
     * The mailbox the CANDIDATE event lives under: the shared organizer
     * (D1, {@code career@trustworks.dk}) — a candidate invitation should
     * come from the company, not a person — falling back to the internal
     * organizer while the config is empty.
     */
    private String candidateOrganizer(String internalOrganizer) {
        String organizer = configuredOrganizer();
        return organizer != null ? organizer : internalOrganizer;
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
        List<MeetingRoomsResponse.MeetingRoom> rooms = allGraphRooms().rooms();
        if (rooms.isEmpty()) {
            return rooms;
        }
        if (start == null || rooms.isEmpty()) {
            return rooms;
        }
        Map<String, Boolean> freeByRoom = mailboxAvailability(
                rooms.stream().map(MeetingRoomsResponse.MeetingRoom::emailAddress).toList(),
                start, durationMinutes).freeByMailbox();
        return rooms.stream()
                .map(room -> new MeetingRoomsResponse.MeetingRoom(
                        room.displayName(), room.emailAddress(),
                        room.capacity(), room.building(),
                        freeByRoom.get(room.emailAddress())))
                .toList();
    }

    /**
     * Every room the tenant has, following {@code @odata.nextLink} to the
     * end. Graph pages the places collection, and the old single unpaged
     * call meant rooms past the first page existed but could never be
     * suggested — invisible, with no error anywhere to say so.
     * <p>
     * Failures degrade to the pages already collected rather than to
     * nothing: a throttled continuation should cost the tail of the list,
     * not the whole room picker. {@link #ROOM_PAGE_LIMIT} is a runaway
     * guard, not an expected bound — hitting it is logged.
     */
    private RoomLookup allGraphRooms() {
        List<MeetingRoomsResponse.MeetingRoom> rooms = new ArrayList<>();
        String skipToken = null;
        int page = 0;
        boolean complete = true;
        do {
            GraphCalendarClient.RoomCollectionResponse response;
            try {
                response = graph().listRoomsPaged(ROOM_PAGE_SIZE, skipToken);
            } catch (Exception e) {
                log.warnv("Graph rooms lookup failed on page {0}: {1} — continuing with {2} room(s)",
                        page, e.getMessage(), rooms.size());
                complete = false;
                break;
            }
            if (response == null || response.value() == null) {
                // No page at all on the FIRST request is a failed lookup, not
                // an empty tenant; a null continuation page is a truncation.
                complete = false;
                break;
            }
            response.value().stream()
                    .filter(room -> room.emailAddress() != null && !room.emailAddress().isBlank())
                    .map(room -> new MeetingRoomsResponse.MeetingRoom(
                            room.displayName(), room.emailAddress(),
                            room.capacity(), room.building(), null))
                    .forEach(rooms::add);
            String nextLink = response.odataNextLink();
            skipToken = parseSkipToken(nextLink);
            if (nextLink != null && !nextLink.isBlank() && skipToken == null) {
                // Graph says there is more but names the continuation in a way
                // we do not read. Truncating silently here is how a room list
                // quietly stops being the whole list.
                log.warnv("Graph rooms @odata.nextLink carried no readable continuation — list truncated at {0} room(s)",
                        rooms.size());
                complete = false;
            }
            page++;
            if (skipToken != null && page >= ROOM_PAGE_LIMIT) {
                log.warnv("Graph rooms pagination hit the {0}-page guard with {1} room(s) — list truncated",
                        ROOM_PAGE_LIMIT, rooms.size());
                complete = false;
                break;
            }
        } while (skipToken != null);
        return new RoomLookup(List.copyOf(rooms), complete);
    }

    /**
     * The tenant's rooms plus whether that list is the WHOLE list.
     * <p>
     * The distinction is the difference between "this tenant has no rooms"
     * and "we could not ask" — indistinguishable from an empty list alone,
     * and the settings page states one of them as fact.
     */
    public record RoomLookup(List<MeetingRoomsResponse.MeetingRoom> rooms, boolean complete) {
    }

    /**
     * As {@link #listRooms()}, but reporting whether the Graph lookup
     * actually completed. Used by the room-policy settings surface, which
     * must not render a failed lookup as "the tenant has no rooms".
     */
    public RoomLookup roomLookup() {
        if (!calendarEnabled) {
            return new RoomLookup(List.of(), false);
        }
        return allGraphRooms();
    }

    /**
     * The {@code $skiptoken} out of an {@code @odata.nextLink}, or null when
     * there is no next page (or the link is unparseable, which stops
     * pagination rather than looping). Mirrors the migration crawler's
     * helper — same Graph convention, same posture.
     */
    static String parseSkipToken(String nextLink) {
        if (nextLink == null || nextLink.isBlank()) {
            return null;
        }
        try {
            String query = java.net.URI.create(nextLink).getRawQuery();
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = java.net.URLDecoder.decode(pair.substring(0, eq),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (key.equalsIgnoreCase("$skiptoken") || key.equalsIgnoreCase("skiptoken")) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            log.warnv("Could not parse rooms @odata.nextLink — stopping pagination: {0}",
                    e.getMessage());
        }
        return null;
    }

    /**
     * The rooms AUTOMATION may book, best-first — {@link #listRooms()}
     * filtered to the admin's enabled set and ordered by priority (V513).
     * Used by the two automatic planners only; the manual room picker keeps
     * using {@link #listRooms()} and sees every room, because disabling a
     * room is a statement about the AI, not about people.
     */
    public List<MeetingRoomsResponse.MeetingRoom> bookableRoomsInPriorityOrder() {
        return roomPolicyService.enabledRoomsInPriorityOrder(listRooms());
    }

    /**
     * Free/busy per interviewer mailbox for the interview slot — the same
     * strict rule and Graph call as rooms. Empty map when the toggle is
     * off; a mailbox missing from the result means "unknown" (callers
     * show the person unmarked, never as busy). Strictly free/busy — no
     * event details are requested or returned.
     */
    public AvailabilityProbe interviewerAvailability(List<String> emails,
                                                     LocalDateTime start,
                                                     int durationMinutes) {
        if (!calendarEnabled || emails.isEmpty()) {
            return AvailabilityProbe.empty();
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
    private AvailabilityProbe mailboxAvailability(List<String> mailboxes,
                                                  LocalDateTime start,
                                                  int durationMinutes) {
        Map<String, Boolean> result = new HashMap<>();
        ScheduleProbe probe =
                mailboxWindowSchedules(mailboxes, start, start.plusMinutes(durationMinutes));
        probe.schedules().forEach((mailbox, schedule) -> {
            if (schedule.availabilityView() != null) {
                result.put(mailbox,
                        schedule.availabilityView().chars().allMatch(c -> c == '0'));
            }
        });
        return new AvailabilityProbe(result, probe.unresolvedMailboxes());
    }

    /**
     * One day's availability grid: 48 15-minute cells over the 07:00–19:00
     * Europe/Copenhagen probe window, per mailbox (person or room), plus
     * working hours. Strictly free/busy digits — no event details are ever
     * requested. Empty when the toggle is off. Keys echo the probed
     * addresses as Graph returns them — compare case-insensitively.
     */
    public ScheduleProbe daySchedule(List<String> mailboxes, LocalDate date) {
        if (!calendarEnabled || mailboxes.isEmpty()) {
            return ScheduleProbe.empty();
        }
        return mailboxWindowSchedules(mailboxes,
                date.atTime(AvailabilitySlotSuggester.DAY_WINDOW_START),
                date.atTime(AvailabilitySlotSuggester.DAY_WINDOW_END));
    }

    /**
     * Raw schedules for an arbitrary date window (Method B slot
     * planning): digit index 0 = {@code from} at 07:00, digits running
     * CONTINUOUSLY (nights and weekends included) to {@code to} at
     * 19:00 — the {@link MultiSlotPlanner} anchor convention, identical
     * to {@link #suggestedSlots}' use of the digit string.
     * <p>
     * Long windows are probed in ≤10-day {@code getSchedule} chunks
     * (the base plan's Graph-429 pacing rule) and stitched per mailbox.
     * A failed or short chunk TRUNCATES that mailbox's view at the last
     * good digit rather than padding: truncated digits count as free
     * for interviewers (unknown never counts as busy) and disqualify
     * rooms (a suggested room is a promise) — exactly the
     * {@code viewFree} lenient/strict posture. Working hours come from
     * the first chunk that carries them.
     * <p>
     * Empty when the toggle is off.
     */
    public ScheduleProbe windowSchedules(List<String> mailboxes, LocalDate from, LocalDate to) {
        if (!calendarEnabled || mailboxes.isEmpty() || to.isBefore(from)) {
            return ScheduleProbe.empty();
        }
        LocalDateTime windowEnd = to.atTime(AvailabilitySlotSuggester.DAY_WINDOW_END);
        Map<String, StringBuilder> views = new HashMap<>();
        Map<String, AvailabilitySlotSuggester.WorkingHours> hours = new HashMap<>();
        Set<String> truncated = new HashSet<>();
        // A mailbox whose chunk was never asked about — as opposed to one
        // Graph answered without. Accumulated across chunks: one throttled
        // chunk makes the whole stitched view untrustworthy for that mailbox.
        Set<String> unresolved = new HashSet<>();
        // ONE budget for every chunk. A 60-day window is 7 chunks; giving
        // each its own allowance would multiply the bound sevenfold — with an
        // outbox transaction open and the 1-minute sweep tick being dropped
        // (concurrentExecution = SKIP) for the whole duration.
        SweepBudget budget = new SweepBudget();

        LocalDateTime chunkStart = from.atTime(AvailabilitySlotSuggester.DAY_WINDOW_START);
        while (chunkStart.isBefore(windowEnd)) {
            LocalDateTime chunkEnd = chunkStart.plusDays(10);
            if (chunkEnd.isAfter(windowEnd)) {
                chunkEnd = windowEnd;
            }
            int expectedDigits = (int) (java.time.Duration.between(chunkStart, chunkEnd)
                    .toMinutes() / AvailabilitySlotSuggester.INTERVAL_MINUTES);
            ScheduleProbe chunkProbe =
                    mailboxWindowSchedules(mailboxes, chunkStart, chunkEnd, budget);
            Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> chunk =
                    chunkProbe.schedules();
            unresolved.addAll(chunkProbe.unresolvedMailboxes());
            for (String mailbox : mailboxes) {
                String key = chunk.keySet().stream()
                        .filter(k -> k.equalsIgnoreCase(mailbox))
                        .findFirst().orElse(null);
                AvailabilitySlotSuggester.MailboxWindowSchedule schedule =
                        key != null ? chunk.get(key) : null;
                String lower = mailbox.toLowerCase(Locale.ROOT);
                if (schedule != null && schedule.workingHours() != null) {
                    hours.putIfAbsent(lower, schedule.workingHours());
                }
                if (truncated.contains(lower)) {
                    continue;
                }
                String view = schedule != null ? schedule.availabilityView() : null;
                if (view == null) {
                    truncated.add(lower);
                    continue;
                }
                views.computeIfAbsent(lower, k -> new StringBuilder()).append(view);
                if (view.length() < expectedDigits) {
                    truncated.add(lower);
                }
            }
            chunkStart = chunkEnd;
        }

        Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> result = new HashMap<>();
        for (String mailbox : mailboxes) {
            String lower = mailbox.toLowerCase(Locale.ROOT);
            StringBuilder view = views.get(lower);
            AvailabilitySlotSuggester.WorkingHours workingHours = hours.get(lower);
            if (view == null && workingHours == null) {
                continue; // wholly unknown mailbox — absent, never busy
            }
            result.put(lower, new AvailabilitySlotSuggester.MailboxWindowSchedule(
                    view != null && view.length() > 0 ? view.toString() : null,
                    workingHours));
        }
        return new ScheduleProbe(result, Set.copyOf(unresolved));
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
    public SlotSuggestions suggestedSlots(List<String> interviewerEmails,
                                          int durationMinutes,
                                          LocalDate from,
                                          int headcount,
                                          LocalDateTime notBefore) {
        if (!calendarEnabled) {
            return SlotSuggestions.none(true);
        }
        // The admin's enabled set, best-first (V513). Only these rooms are
        // probed and only these can be suggested — the manual picker still
        // gets the full list from listRooms().
        List<MeetingRoomsResponse.MeetingRoom> rooms = bookableRoomsInPriorityOrder();
        List<String> mailboxes = new ArrayList<>(interviewerEmails);
        rooms.forEach(room -> mailboxes.add(room.emailAddress()));
        if (mailboxes.isEmpty()) {
            return SlotSuggestions.none(true);
        }
        ScheduleProbe probe = mailboxWindowSchedules(mailboxes,
                from.atTime(AvailabilitySlotSuggester.DAY_WINDOW_START),
                lastBusinessDay(from, AvailabilitySlotSuggester.BUSINESS_DAYS)
                        .atTime(AvailabilitySlotSuggester.DAY_WINDOW_END));
        if (probe.schedules().isEmpty()) {
            return SlotSuggestions.none(false);
        }
        // A suggestion chip is a positive claim — the scheduler clicks it and
        // the time is set. Proposing against an interviewer we never managed
        // to ASK about is the silent gap that made a throttled lookup look
        // like a free calendar (production 2026-08-15): the suggester's
        // "unknown never counts as busy" rule is right for MARKING people and
        // wrong for PROMISING a time. Note this suppresses only the
        // unresolved case — an interviewer Graph answered without simply has
        // no mailbox, which must never disable the feature.
        if (probe.anyUnresolved(interviewerEmails)) {
            log.warnv("Graph free/busy incomplete for {0} of {1} chosen interviewer(s) — suggesting nothing rather than guessing",
                    interviewerEmails.stream()
                            .filter(email -> probe.unresolvedMailboxes()
                                    .contains(email.toLowerCase(Locale.ROOT)))
                            .count(),
                    interviewerEmails.size());
            return SlotSuggestions.none(false);
        }
        Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> byLowercase = new HashMap<>();
        probe.schedules().forEach((mailbox, schedule) ->
                byLowercase.put(mailbox.toLowerCase(Locale.ROOT), schedule));
        List<AvailabilitySlotSuggester.Slot> slots = AvailabilitySlotSuggester.suggest(
                from,
                byLowercase,
                interviewerEmails.stream().map(email -> email.toLowerCase(Locale.ROOT)).toList(),
                rooms.stream()
                        .map(room -> new AvailabilitySlotSuggester.RoomOption(
                                room.emailAddress().toLowerCase(Locale.ROOT),
                                room.displayName(), room.capacity()))
                        .toList(),
                durationMinutes, headcount, notBefore);
        // Rooms may still be unresolved here — that costs a room on a slot,
        // never a wrong time, because rooms are held to the strict rule.
        return new SlotSuggestions(slots, probe.complete());
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
    private ScheduleProbe mailboxWindowSchedules(
            List<String> mailboxes, LocalDateTime windowStart, LocalDateTime windowEnd) {
        return mailboxWindowSchedules(mailboxes, windowStart, windowEnd, new SweepBudget());
    }

    /**
     * As above, spending a budget the CALLER owns — so a multi-chunk scan
     * ({@link #windowSchedules}) blocks for its budget in total rather than
     * for its budget per chunk. A 60-day Method B window is 7 chunks; a
     * per-chunk budget would multiply the documented bound sevenfold while
     * an outbox transaction is open.
     */
    private ScheduleProbe mailboxWindowSchedules(
            List<String> mailboxes, LocalDateTime windowStart, LocalDateTime windowEnd,
            SweepBudget budget) {
        Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> result = new HashMap<>();
        Set<String> unresolved = new HashSet<>();
        int batches = 0;
        int failedBatches = 0;
        for (int i = 0; i < mailboxes.size(); i += GET_SCHEDULE_BATCH) {
            List<String> batch = mailboxes.subList(i,
                    Math.min(i + GET_SCHEDULE_BATCH, mailboxes.size()));
            batches++;
            boolean resolved = false;
            for (String caller : callerCandidates(batch)) {
                BatchOutcome outcome = probeBatch(caller, batch, windowStart, windowEnd, budget);
                if (outcome.schedules() != null) {
                    result.putAll(outcome.schedules());
                    resolved = true;
                    break;
                }
                if (outcome.mailboxLess()) {
                    // A permanent property of the ADDRESS: remember it, and
                    // let the ladder move on — a different mailbox is the
                    // only possible remedy.
                    mailboxLessCallers.add(caller.toLowerCase(Locale.ROOT));
                    continue;
                }
                if (outcome.throttled()) {
                    // A property of OUR call rate, not of the address.
                    // Escalating to another mailbox would relocate the
                    // overload onto an innocent one — which is exactly how
                    // the 2026-08-15 burst spread from adam.hoppe to
                    // alberte.bang. Stop this batch instead; it is reported
                    // as unresolved, and unresolved is never read as free.
                    break;
                }
            }
            if (!resolved) {
                failedBatches++;
                batch.forEach(mailbox -> unresolved.add(mailbox.toLowerCase(Locale.ROOT)));
            }
        }
        if (failedBatches > 0) {
            log.warnv("Graph free/busy: {0} of {1} batch(es) unresolved — {2} mailbox(es) reported as unknown, not free",
                    failedBatches, batches, unresolved.size());
        }
        return new ScheduleProbe(result, Set.copyOf(unresolved));
    }

    /**
     * Caller mailboxes to try for one batch, starting at a ROTATING offset.
     * <p>
     * It used to start at the batch's own first address and then stick to
     * whichever mailbox answered first ({@code provenCaller}), reusing it
     * for every later batch. That concentrated an entire sweep — 7-8
     * {@code getSchedule} calls over the ~140-mailbox roster — onto one
     * mailbox, and because the roster arrives ordered by username it was
     * always the SAME mailbox across every concurrent request. A handful of
     * concurrent scheduling dialogs was therefore enough to put more than
     * Microsoft's 4 concurrent requests on adam.hoppe@trustworks.dk and earn
     * a 429 {@code ApplicationThrottled} (production 2026-08-15).
     * <p>
     * Rotating the anchor spreads a sweep across as many mailboxes as it has
     * batches, and the shared cursor staggers concurrent sweeps against each
     * other. Addresses already known to be mailbox-less are skipped, which
     * is what {@code provenCaller} was really buying.
     * <p>
     * Still bounded by {@link #CALLER_ATTEMPTS} — a wholly broken Graph must
     * not turn one sweep into a retry storm.
     */
    private List<String> callerCandidates(List<String> batch) {
        if (batch.isEmpty()) {
            return List.of();
        }
        int start = Math.floorMod(callerCursor.getAndIncrement(), batch.size());
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < batch.size() && candidates.size() < CALLER_ATTEMPTS; i++) {
            String mailbox = batch.get((start + i) % batch.size());
            if (!mailboxLessCallers.contains(mailbox.toLowerCase(Locale.ROOT))
                    && !candidates.contains(mailbox)) {
                candidates.add(mailbox);
            }
        }
        if (candidates.isEmpty()) {
            // Every address in this batch is known mailbox-less. Ask as the
            // first one anyway: "known bad" is a cache, not a certainty, and
            // one 404 is cheaper than silently reporting the batch unknown.
            candidates.add(batch.get(start));
        }
        return candidates;
    }

    /**
     * The three ways one batch probe can end. {@code schedules} non-null is
     * success; the two booleans classify the failure so the caller knows
     * whether trying a DIFFERENT mailbox could possibly help.
     */
    private record BatchOutcome(
            Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> schedules,
            boolean mailboxLess,
            boolean throttled,
            Integer retryAfterSeconds) {

        static BatchOutcome ok(Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> schedules) {
            return new BatchOutcome(schedules, false, false, null);
        }

        /** The caller address is not a mailbox in the tenant — try another. */
        static BatchOutcome mailboxLessCaller() {
            return new BatchOutcome(null, true, false, null);
        }

        /** Graph is rate-limiting us — waiting helps, switching mailbox does not. */
        static BatchOutcome throttledFor(Integer retryAfterSeconds) {
            return new BatchOutcome(null, false, true, retryAfterSeconds);
        }

        /** Anything else (5xx, timeout, surprise) — another caller may work. */
        static BatchOutcome failed() {
            return new BatchOutcome(null, false, false, null);
        }
    }

    /**
     * One {@code getSchedule} call at 15-minute resolution, under the
     * per-mailbox concurrency permit and with bounded 429 backoff.
     * <p>
     * A 429 is retried ONCE against the same caller after honouring
     * {@code Retry-After} (capped, and drawn from a sweep-wide budget), then
     * given up: it is a statement about our call rate, so the only useful
     * response is to wait or to stop, never to re-ask as somebody else.
     *
     * @return the outcome — never throws; a failed lookup is reported, not
     *         raised, because scheduling must not break on calendar trouble
     */
    private BatchOutcome probeBatch(
            String caller,
            List<String> batch,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            SweepBudget budget) {
        BatchOutcome first = probeBatchOnce(caller, batch, windowStart, windowEnd, budget);
        if (!first.throttled()) {
            return first;
        }
        long wait = budget.take(throttleWaitMillis(first.retryAfterSeconds()));
        if (wait <= 0) {
            log.warnv("Graph free/busy throttled asking as {0} and the sweep's blocking budget is spent — batch unresolved",
                    caller);
            return first;
        }
        sleeper.accept(wait);
        return probeBatchOnce(caller, batch, windowStart, windowEnd, budget);
    }

    /** Graph's requested wait, capped; its default when it asked for none. */
    private static long throttleWaitMillis(Integer retryAfterSeconds) {
        if (retryAfterSeconds == null || retryAfterSeconds <= 0) {
            return THROTTLE_DEFAULT_WAIT_MS;
        }
        return Math.min(retryAfterSeconds * 1000L, THROTTLE_MAX_WAIT_MS);
    }

    private BatchOutcome probeBatchOnce(
            String caller,
            List<String> batch,
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            SweepBudget budget) {
        // The wait comes out of the sweep's blocking budget: with ~8 batches
        // on a 3-rung ladder an unbudgeted per-probe wait would be paid up to
        // 24 times over. A spent budget still ATTEMPTS the acquire, it just
        // does not wait for one.
        if (!mailboxLimiter.tryAcquire(caller, budget.take(mailboxLimiter.permitWaitMillis()))) {
            // We never asked, so we know nothing — and knowing nothing must
            // read as unknown, never as free.
            log.warnv("Graph free/busy: no concurrency permit for {0} within the wait — batch left unresolved",
                    caller);
            return BatchOutcome.failed();
        }
        try {
            GraphCalendarClient.ScheduleCollectionResponse response = graph().getSchedule(
                    caller,
                    new GraphCalendarClient.ScheduleRequest(
                            batch,
                            new CalendarEventRequest.DateTimeTimeZone(
                                    windowStart.toString(), EVENT_TIME_ZONE),
                            new CalendarEventRequest.DateTimeTimeZone(
                                    windowEnd.toString(), EVENT_TIME_ZONE),
                            AvailabilitySlotSuggester.INTERVAL_MINUTES));
            if (response == null || response.value() == null) {
                return BatchOutcome.ok(Map.of());
            }
            Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> batchResult = new HashMap<>();
            for (GraphCalendarClient.ScheduleCollectionResponse.ScheduleInformation info : response.value()) {
                if (info.scheduleId() == null) {
                    continue;
                }
                batchResult.put(info.scheduleId(),
                        new AvailabilitySlotSuggester.MailboxWindowSchedule(
                                info.availabilityView(), toWorkingHours(info.workingHours())));
            }
            return BatchOutcome.ok(batchResult);
        } catch (Exception e) {
            log.warnv("Graph free/busy lookup failed asking as {0}: {1}", caller, e.getMessage());
            if (isGraphThrottled(e)) {
                return BatchOutcome.throttledFor(graphRetryAfterSeconds(e));
            }
            if (isGraphNotFound(e)) {
                return BatchOutcome.mailboxLessCaller();
            }
            return BatchOutcome.failed();
        } finally {
            mailboxLimiter.release(caller);
        }
    }

    /** Graph's 429 {@code ApplicationThrottled}, whatever shape it arrives in. */
    static boolean isGraphThrottled(Exception e) {
        if (e instanceof dk.trustworks.intranet.graph
                .GraphResponseExceptionMapper.GraphApiException graphError) {
            return graphError.isThrottled();
        }
        if (e instanceof WebApplicationException webError) {
            return webError.getResponse() != null
                    && webError.getResponse().getStatus() == 429;
        }
        return false;
    }

    /** The {@code Retry-After} Graph asked for, when the client preserved it. */
    static Integer graphRetryAfterSeconds(Exception e) {
        if (e instanceof dk.trustworks.intranet.graph
                .GraphResponseExceptionMapper.GraphApiException graphError) {
            return graphError.getRetryAfterSeconds();
        }
        return null;
    }

    /**
     * Normalize Graph working hours ({@code "08:00:00.0000000"}, lowercase
     * day names) into the suggester's shape. Unparseable parts degrade to
     * null (= unconstrained) — working hours refine suggestions, they must
     * never break availability itself.
     */
    static AvailabilitySlotSuggester.WorkingHours toWorkingHours(
            GraphCalendarClient.ScheduleCollectionResponse.ScheduleInformation.WorkingHours workingHours) {
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

    // ---- Method B placeholder holds (plan §9.3, D5/D12) --------------------

    /**
     * Create one attendee-less hold event by direct write into
     * {@code mailbox}'s default calendar (D5: no attendees ⇒ no
     * invitation mail; verified by the Phase 7.5 spike against user AND
     * room mailboxes). {@code showAs: tentative}, {@code sensitivity:
     * private} (D3), no reminder pop-ups, {@code transactionId} =
     * the hold uuid so a replay never double-books.
     * <p>
     * THROWS on failure, unlike the Method A best-effort methods: the
     * caller is an outbox executor whose retry/backoff/dead-letter IS
     * the failure posture.
     *
     * @return the created Graph event id
     */
    public String createHoldEvent(String mailbox, String subject, String bodyText,
                                  LocalDateTime start, LocalDateTime end,
                                  String transactionId) {
        GraphCalendarClient.CalendarEvent created = graph().createCalendarEvent(mailbox,
                new CalendarEventRequest(
                        subject,
                        new CalendarEventRequest.ItemBody("text", bodyText),
                        new CalendarEventRequest.DateTimeTimeZone(start.toString(), EVENT_TIME_ZONE),
                        new CalendarEventRequest.DateTimeTimeZone(end.toString(), EVENT_TIME_ZONE),
                        null,
                        List.of(),
                        null,
                        null,
                        List.of("Recruitment"),
                        null,
                        "private",
                        transactionId,
                        Boolean.FALSE,
                        "tentative",
                        Boolean.FALSE));
        if (created == null || created.id() == null) {
            throw new IllegalStateException("Graph returned no event id for hold " + transactionId);
        }
        return created.id();
    }

    /**
     * Delete a hold event silently (no attendees ⇒ no cancellation
     * mail). 404 = already gone = success; anything else throws so the
     * outbox retries (spec §21.5: a hold that cannot be deleted keeps a
     * cleanup warning on the request).
     */
    public void deleteHoldEvent(String mailbox, String eventId) {
        try {
            graph().deleteCalendarEvent(mailbox, eventId);
        } catch (Exception e) {
            if (!isGraphNotFound(e)) {
                throw e;
            }
        }
    }

    /**
     * The Graph client's 404, whatever shape it arrives in (F18,
     * production 2026-08-15): the REST client's registered
     * {@link dk.trustworks.intranet.graph.GraphResponseExceptionMapper}
     * throws its own {@code GraphApiException} (a plain
     * RuntimeException carrying the status), so a catch on
     * {@code WebApplicationException} NEVER sees Graph 404s — the
     * reconciliation probe logged every deleted hold as a failed probe
     * and MISSING detection was dead. Both shapes are recognized here.
     */
    static boolean isGraphNotFound(Exception e) {
        if (e instanceof dk.trustworks.intranet.graph
                .GraphResponseExceptionMapper.GraphApiException graphError) {
            return graphError.isNotFound();
        }
        if (e instanceof WebApplicationException webError) {
            return webError.getResponse() != null
                    && webError.getResponse().getStatus() == 404;
        }
        return false;
    }

    /**
     * The busy/tentative event ids overlapping {@code [start, end)} in a
     * mailbox — the Method B recheck source (plan §9.3): the caller
     * excludes its own hold ids; anything left is a conflict. Ignored:
     * events marked {@code free}; CANCELLED events still parked on the
     * calendar ("Annulleret: …" keeps a non-free {@code showAs} — a
     * meeting that is not happening must not block a slot, F1b); and
     * events that merely TOUCH the window boundary (an event ending
     * exactly at the slot start overlaps nothing — enforced here so
     * Graph's own boundary semantics cannot reject back-to-back slots,
     * F1c). THROWS on Graph failure — a recheck that cannot see the
     * calendar must not pass.
     */
    public List<String> busyEventIdsInWindow(String mailbox, LocalDateTime start,
                                             LocalDateTime end) {
        // F19 (production 2026-08-15): calendarView's startDateTime/
        // endDateTime are read as UTC when they carry no offset — the
        // Prefer header shapes only the RESPONSE times. Bare wall-clock
        // params therefore probed a window two hours late (CEST): a
        // 15.00 slot was rechecked against 17.00–17.45, real 15.00
        // meetings were invisible, and phantom conflicts appeared from
        // meetings two hours after the slot (the actual cause of the
        // 08-14 round-hour rejections first blamed on boundary
        // semantics). Explicit Europe/Copenhagen offsets pin the window.
        GraphCalendarClient.CalendarViewResponse response = graph().calendarView(
                mailbox, graphWindowParam(start), graphWindowParam(end),
                "id,showAs,isCancelled,start,end", 100);
        if (response == null || response.value() == null) {
            return List.of();
        }
        return response.value().stream()
                .filter(event -> event.id() != null)
                .filter(event -> !"free".equalsIgnoreCase(
                        event.showAs() == null ? "" : event.showAs()))
                .filter(event -> !Boolean.TRUE.equals(event.isCancelled()))
                .filter(event -> strictlyOverlaps(event, start, end))
                .map(GraphCalendarClient.CalendarViewResponse.CalendarViewEvent::id)
                .toList();
    }

    /**
     * True when the event's interval and {@code [start, end)} share
     * actual time — boundary contact is not overlap. Unparseable or
     * missing event bounds count as overlapping (the conservative
     * reading: never silently pass a conflict we cannot place).
     */
    static boolean strictlyOverlaps(
            GraphCalendarClient.CalendarViewResponse.CalendarViewEvent event,
            LocalDateTime start, LocalDateTime end) {
        LocalDateTime eventStart = parseGraphDateTime(event.start());
        LocalDateTime eventEnd = parseGraphDateTime(event.end());
        if (eventStart == null || eventEnd == null) {
            return true;
        }
        return eventStart.isBefore(end) && eventEnd.isAfter(start);
    }

    /** A Copenhagen wall-clock instant as an OFFSET-carrying ISO string
     * ({@code 2026-08-26T15:00:00+02:00}, DST-aware) — the only param
     * shape Graph reads timezone-correctly (F19). */
    static String graphWindowParam(LocalDateTime wallClock) {
        return wallClock.atZone(java.time.ZoneId.of("Europe/Copenhagen"))
                .format(java.time.format.DateTimeFormatter
                        .ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }

    /** Graph answers e.g. {@code 2026-08-25T14:00:00.0000000} (wall
     * clock in the Prefer header's zone). Null on absence or surprise. */
    static LocalDateTime parseGraphDateTime(
            GraphCalendarClient.CalendarViewResponse.GraphDateTime value) {
        if (value == null || value.dateTime() == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.dateTime());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether a hold's Graph event still exists — the reconciliation
     * probe (plan §9.4). False ONLY on a definitive 404; any other
     * failure throws (an unreachable Graph must not read as a deleted
     * hold).
     */
    public boolean holdEventExists(String mailbox, String eventId) {
        try {
            graph().getCalendarEventDetails(mailbox, eventId, "id");
            return true;
        } catch (Exception e) {
            if (isGraphNotFound(e)) {
                return false;
            }
            throw e;
        }
    }

    // ---- Shaping -----------------------------------------------------------

    /**
     * The INTERNAL event body (interviewers + room). {@code create} gates
     * the create-only {@code transactionId} (Graph rejects it on PATCH) —
     * the interview UUID, so a retry-stormed create never double-books.
     * <p>
     * {@code includeCandidate} is the LEGACY single-event mode: updates of
     * rows created before the split (no candidate event exists) keep the
     * candidate as an attendee and the candidate-safe body/subject —
     * position named nowhere. Split-mode internal events carry no
     * candidate, so the position and focus areas return to where the
     * interviewers can use them.
     */
    /**
     * The calendar subject/ICS summary for one interview — the only place
     * the kinds are spelled out for a calendar. A ROUND names its number;
     * an INFORMAL chat is Airtable's <em>uformel snak</em>; an OFFER
     * meeting is a plain <em>samtale</em>, matching the Danish wording the
     * candidate already gets in the invitation body ("at møde dig til
     * samtale") — it carries no round number, so it must never fall
     * through to the ROUND branch and render "Interview null".
     */
    private static String interviewSubject(RecruitmentInterview interview, String candidateName) {
        return switch (interview.getKind()) {
            case INFORMAL -> "Uformel snak: %s".formatted(candidateName);
            case OFFER -> "Samtale: %s".formatted(candidateName);
            case ROUND -> "Interview %d: %s".formatted(interview.getRound(), candidateName);
        };
    }

    private CalendarEventRequest buildInternalEvent(RecruitmentInterview interview,
                                                    RecruitmentCandidate candidate,
                                                    RecruitmentPosition position,
                                                    boolean create,
                                                    boolean includeCandidate,
                                                    String organizer) {
        Objects.requireNonNull(interview.getScheduledAt(), "scheduledAt must be set before calendar sync");
        boolean candidateInvited = includeCandidate
                && candidate != null
                && candidate.getEmail() != null && !candidate.getEmail().isBlank();
        String subject = interviewSubject(interview, candidateName(candidate));
        if (!includeCandidate && position != null && position.getTitle() != null) {
            // Interviewers-only surface — naming the position helps them
            // and can no longer leak to the candidate.
            subject = subject + " — " + position.getTitle();
        }

        // Resolved once: the attendee list and the candidate-facing name list
        // are the same roster read two ways, and each resolve is a DB hit.
        List<Interviewer> roster = resolveInterviewers(interview.getInterviewerUuids());
        List<CalendarEventRequest.Attendee> attendees = new ArrayList<>(
                interviewerAttendees(roster, organizer));
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

        String body = includeCandidate
                ? invitationBody(interview, candidate, candidateInvited,
                        invitationDetails(interview, roster))
                : internalBody(position, candidate, organizer);

        // Teams fields are one-way: TRUE turns the meeting online (works on
        // both create and PATCH — verified in this tenant, Phase 0.3 spike);
        // FALSE is never sent, so an existing Teams meeting is not silently
        // stripped by an unrelated reschedule.
        boolean teams = interview.isOnlineMeeting();
        return new CalendarEventRequest(
                subject,
                new CalendarEventRequest.ItemBody("text", body),
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
                Boolean.TRUE,
                null,
                null);
    }

    /**
     * One interviewer reduced to what an invitation needs: their mailbox
     * and the name to show beside it.
     */
    record Interviewer(String email, String name) {
    }

    /**
     * How an interviewer UUID becomes a mailbox identity. A replaceable
     * field rather than a direct call — exactly like {@link #graphApiClient}
     * — so the DB-free tier that gates deploys can build a MULTI-interviewer
     * attendee list. {@code User.findById} is Panache-enhanced and throws
     * {@code implementationInjectionMissing} outside Quarkus, which
     * {@code createEvent}'s catch-all would swallow into a silent no-op:
     * that is precisely why the pre-fix single-interviewer fixtures were
     * the only shape the gate could express, and why the drop shipped.
     */
    java.util.function.Function<String, Interviewer> interviewerResolver = uuid -> {
        User user = User.findById(uuid);
        // A mailbox-less user still has a NAME, and since the invitation
        // names the panel to the candidate (so they can type a host into
        // the reception iPad) they must not be dropped this early. The
        // attendee list narrows on the mailbox where it needs one.
        return user == null ? null : new Interviewer(mailboxOf(user), displayName(user));
    };

    /**
     * Resolve assigned interviewer UUIDs to identities, in roster order,
     * dropping only the ones we cannot place at all (no user row).
     * <p>
     * The result is deliberately NOT filtered on the mailbox: it feeds both
     * the attendee list — which skips mailbox-less entries itself, see
     * {@link #interviewerAttendees} — and the candidate-facing name list,
     * which does not care whether we could email the person.
     */
    private List<Interviewer> resolveInterviewers(List<String> interviewerUuids) {
        if (interviewerUuids == null) {
            return List.of();
        }
        List<Interviewer> resolved = new ArrayList<>();
        for (String uuid : interviewerUuids) {
            Interviewer interviewer = interviewerResolver.apply(uuid);
            if (interviewer != null) {
                resolved.add(interviewer);
            }
        }
        return resolved;
    }

    /**
     * The facts an invitation needs that are neither a calendar field nor
     * part of the renderer's standard candidate/position vocabulary: who the
     * candidate is meeting, and — for a PHYSICAL interview only — where to
     * go and what to do on arrival.
     * <p>
     * Every component is a non-null string, empty when it does not apply.
     * That is not tidiness: these values land in the extras map handed to
     * {@link RecruitmentEmailRenderer}, and a null there used to mean an NPE
     * inside {@code createEvent}'s catch-all — the candidate would get NO
     * invitation at all and the only trace would be a WARN.
     */
    record InvitationDetails(String interviewerNames,
                             String visitingAddress,
                             String arrivalInstructions) {

        /** Nothing to add — an online interview, or a caller with no roster. */
        static final InvitationDetails NONE = new InvitationDetails("", "", "");

        InvitationDetails {
            interviewerNames = interviewerNames == null ? "" : interviewerNames;
            visitingAddress = visitingAddress == null ? "" : visitingAddress;
            arrivalInstructions = arrivalInstructions == null ? "" : arrivalInstructions;
        }
    }

    /**
     * Build the invitation details for one interview. Static and pure —
     * the roster and the configured address are resolved by the caller, for
     * the same reason {@link #interviewerResolver} is a field: Panache
     * throws outside Quarkus and the DB-free tier gates deploys.
     * <p>
     * The physical/online split is taken from
     * {@code RecruitmentInterview.onlineMeeting} (V492) and from nothing
     * else. {@code location} is free text — "Microsoft Teams", a room name,
     * or whatever a recruiter typed — so no string test on it can tell a
     * hybrid from a Teams-only meeting.
     */
    static InvitationDetails invitationDetails(RecruitmentInterview interview,
                                               List<Interviewer> roster,
                                               String visitingAddress) {
        String names = interviewerNames(roster);
        boolean physical = interview != null && !interview.isOnlineMeeting();
        String address = physical && visitingAddress != null && !visitingAddress.isBlank()
                ? visitingAddress.trim()
                : "";
        return new InvitationDetails(names, address, arrivalInstructions(address));
    }

    /**
     * The panel, named in roster order and joined the Danish way — "A",
     * "A og B", "A, B og C". There is no lead interviewer in the data model
     * and this does not invent one.
     * <p>
     * Null and blank names are skipped rather than rendered:
     * {@link #displayName} returns null for a half-filled user row on
     * purpose, and {@code String.join} over that list would print "null".
     */
    static String interviewerNames(List<Interviewer> roster) {
        if (roster == null || roster.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (Interviewer interviewer : roster) {
            String name = interviewer == null || interviewer.name() == null
                    ? null
                    : interviewer.name().trim();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return "";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1))
                + " og " + names.get(names.size() - 1);
    }

    /**
     * What to do on arrival, Danish, one sentence pair. It names the three
     * things the reception iPad ({@code /guest}) asks for — the visitor's
     * name, their company, and the employee they are meeting — because a
     * candidate standing in front of it with no warning is exactly the
     * situation this text exists to remove. The host name they need is the
     * one {@link #interviewerNames} printed higher up in the same body.
     * <p>
     * Empty in, empty out: no address means no arrival instruction, which
     * is what an online interview and the blanked-address opt-out both want.
     */
    static String arrivalInstructions(String visitingAddress) {
        if (visitingAddress == null || visitingAddress.isBlank()) {
            return "";
        }
        return "Du finder os på " + visitingAddress.trim()
                + ". Når du ankommer, taster du dit navn, dit firma og navnet på den,"
                + " du skal møde, ind på iPad'en i receptionen — så får vi besked om,"
                + " at du er kommet.";
    }

    /** The invitation details for this interview, from the live roster and
     * the configured visiting address. */
    private InvitationDetails invitationDetails(RecruitmentInterview interview,
                                                List<Interviewer> roster) {
        return invitationDetails(interview, roster, visitingAddress());
    }

    /**
     * The interviewer attendee list: everyone except whoever IS the
     * organizer. Outlook derives the organizer from the mailbox hosting the
     * event, so re-listing them as an attendee duplicates them on the
     * invitation.
     * <p>
     * Matching is on mailbox identity, case-insensitively — NOT on list
     * position. The positional form ({@code for i = 1}) was correct only
     * while the organizer was by definition {@code interviewerUuids[0]};
     * V492 moved new events to the shared {@code career@trustworks.dk}
     * mailbox and the skip silently began dropping a real person, and
     * invited nobody at all when a single interviewer was assigned. Legacy
     * rows whose stored {@code graph_organizer} IS an interviewer still
     * exclude exactly that one, so nothing double-invites.
     * <p>
     * Pure and package-private on purpose: the DB-free tier that gates
     * deploys pins it directly, the same convention as {@link #internalBody},
     * {@link #invitationBody} and {@link #calendarStatus}.
     *
     * @param interviewers already-resolved mailbox identities, in roster order
     * @param organizerMailbox the mailbox the event lives under; {@code null}
     *                         excludes nobody
     */
    static List<CalendarEventRequest.Attendee> interviewerAttendees(
            List<Interviewer> interviewers, String organizerMailbox) {
        if (interviewers == null || interviewers.isEmpty()) {
            return List.of();
        }
        List<CalendarEventRequest.Attendee> attendees = new ArrayList<>();
        for (Interviewer interviewer : interviewers) {
            String email = interviewer == null || interviewer.email() == null
                    ? null
                    : interviewer.email().trim();
            if (email == null || email.isEmpty()) {
                continue;
            }
            // Trim BOTH sides: a stored mailbox with stray whitespace would
            // otherwise fail to match the organizer and get listed twice —
            // once as organizer, once as attendee.
            if (organizerMailbox != null && email.equalsIgnoreCase(organizerMailbox.trim())) {
                continue;
            }
            // Name as well as address: an external mail client cannot
            // resolve a Trustworks address against our directory, so a
            // nameless attendee shows up as a raw firstname.lastname@… .
            attendees.add(required(email, interviewer.name()));
        }
        return attendees;
    }

    /**
     * The split-mode internal note: kit pointer, position, focus areas,
     * and — the whole point of the closing paragraph — where the
     * candidate went.
     * <p>
     * This body is read by interviewers inside Outlook, where the split
     * is invisible: they see themselves and a room and nothing else, and
     * reasonably conclude the candidate was never invited. Worse, the
     * candidate's accept/decline is mailed to the shared organizer
     * mailbox, so an interviewer watching their own inbox for an answer
     * waits forever. Both facts are stated here rather than left to be
     * rediscovered. The no-email branch is the one that matters most: it
     * is the only case where no invitation actually exists, and Outlook
     * looks exactly the same as when one does.
     *
     * @param organizer the mailbox the events are addressed under, named
     *                  so the reader knows where the RSVP landed; omitted
     *                  from the text when unknown
     */
    static String internalBody(RecruitmentPosition position,
                               RecruitmentCandidate candidate,
                               String organizer) {
        StringBuilder body = new StringBuilder(
                "Scheduled via the Trustworks intranet — see /recruitment/interviews for the interview kit.");
        if (position != null && position.getTitle() != null) {
            body.append("\n\nPosition: ").append(position.getTitle());
            if (position.getScorecardTemplate() != null
                    && !position.getScorecardTemplate().isEmpty()) {
                body.append("\nFocus areas: ");
                body.append(String.join(", ", position.getScorecardTemplate().stream()
                        .map(attribute -> attribute.label())
                        .toList()));
            }
        }
        body.append("\n\n").append(candidateInvitationNote(candidate, organizer));
        return body.toString();
    }

    /**
     * The closing paragraph of {@link #internalBody} — split out so the
     * DB-free tier can pin both branches on their own.
     */
    static String candidateInvitationNote(RecruitmentCandidate candidate, String organizer) {
        String email = candidate != null && candidate.getEmail() != null
                ? candidate.getEmail().trim()
                : "";
        if (email.isEmpty()) {
            return "The candidate has NO email address in the intranet, so no invitation was sent to them"
                    + " — this meeting is booked with the room and the interviewers only."
                    + " Add their address on the candidate page and save the interview again,"
                    + " or invite them by hand.";
        }
        String mailbox = organizer != null && !organizer.isBlank() ? organizer.trim() : null;
        return "The candidate is invited on their own separate event and is deliberately not on this one: "
                + email + "."
                + "\nTheir answer will not reach your inbox"
                + (mailbox != null ? " — RSVP replies go to " + mailbox : "")
                + ". See the Outlook line on the candidate's Interviews tab for whether they accepted.";
    }

    /**
     * The CANDIDATE event (plan Phase 6): only the candidate attends; the
     * body is the HR-editable {@code INTERVIEW_CANDIDATE_INVITATION}
     * template rendered per candidate/position (HTML, sanitizer-mandatory
     * — stored HTML goes straight to Outlook) with a built-in fallback.
     * Never a Teams meeting of its own — that would mint a SECOND meeting;
     * the internal event's join link is appended to the body instead.
     */
    private CalendarEventRequest buildCandidateEvent(RecruitmentInterview interview,
                                                     RecruitmentCandidate candidate,
                                                     RecruitmentPosition position,
                                                     String joinUrl,
                                                     boolean create) {
        Objects.requireNonNull(interview.getScheduledAt(), "scheduledAt must be set before calendar sync");
        CandidateInvitation invitation = candidateInvitation(
                interview, candidate, position, joinUrl, candidateTemplate(),
                invitationDetails(interview, resolveInterviewers(interview.getInterviewerUuids())));
        return new CalendarEventRequest(
                invitation.subject(),
                new CalendarEventRequest.ItemBody("html", invitation.htmlBody()),
                new CalendarEventRequest.DateTimeTimeZone(
                        interview.getScheduledAt().toString(), EVENT_TIME_ZONE),
                new CalendarEventRequest.DateTimeTimeZone(
                        interview.getScheduledAt().plusMinutes(interview.getDurationMinutes()).toString(),
                        EVENT_TIME_ZONE),
                interview.getLocation() != null
                        ? new CalendarEventRequest.EventLocation(interview.getLocation())
                        : null,
                List.of(required(candidate.getEmail(), candidateName(candidate))),
                null,
                null,
                List.of("Recruitment"),
                15,
                "private",
                create ? interview.getUuid() + "-candidate" : null,
                Boolean.TRUE,
                null,
                null);
    }

    /** The template row, or null when missing/inactive/unreadable. */
    RecruitmentEmailTemplate candidateTemplate() {
        try {
            RecruitmentEmailTemplate template = RecruitmentEmailTemplate
                    .find("templateKey", "INTERVIEW_CANDIDATE_INVITATION").firstResult();
            return template != null && template.isActive() ? template : null;
        } catch (Exception e) {
            log.warnv("Candidate invitation template unreadable: {0} — using the built-in body",
                    e.getMessage());
            return null;
        }
    }

    record CandidateInvitation(String subject, String htmlBody) { }

    /**
     * Render the candidate invitation — pure given the template row and the
     * resolved {@code details}, so the DB-free tier pins it. Template path:
     * merge fields (candidate, position, and the interview extras below),
     * sanitizer-mandatory on the way out. Fallback path: the plain
     * invitation text, HTML-ified. A known join link is appended as a link
     * block either way.
     *
     * @param details who the candidate is meeting and, for a physical
     *                interview, where and what to do on arrival — resolved
     *                by the instance side because names come from Panache;
     *                {@code null} is tolerated and means "nothing to add"
     */
    static CandidateInvitation candidateInvitation(RecruitmentInterview interview,
                                                   RecruitmentCandidate candidate,
                                                   RecruitmentPosition position,
                                                   String joinUrl,
                                                   RecruitmentEmailTemplate template,
                                                   InvitationDetails details) {
        InvitationDetails resolved = details == null ? InvitationDetails.NONE : details;
        String subject;
        String html;
        if (template != null) {
            // A LinkedHashMap, NOT Map.of: Map.of throws NPE on a null value,
            // and this runs inside createEvent's catch-all — one unnamed
            // interviewer would have meant the candidate got no invitation
            // at all, visible only as a WARN. Every value below is non-null
            // by construction; the map type keeps it that way if one stops
            // being.
            Map<String, String> extras = new LinkedHashMap<>();
            extras.put("interview_date", interview.getScheduledAt()
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            extras.put("interview_time", interview.getScheduledAt()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
            extras.put("interview_location", interview.getLocation() != null
                    ? interview.getLocation() : "Trustworks");
            // These three must be present on EVERY path, empty included: an
            // unknown token is left verbatim by the renderer and the *_link
            // send gate never runs here, so a missing key would mail the
            // candidate a literal "{{interviewer_names}}".
            extras.put("interviewer_names", resolved.interviewerNames());
            extras.put("visiting_address", resolved.visitingAddress());
            extras.put("arrival_instructions", resolved.arrivalInstructions());
            RecruitmentEmailRenderer.Rendered rendered = RecruitmentEmailRenderer.render(
                    template.getSubject(), template.getBody(), candidate, position, extras,
                    template.getBodyFormat());
            subject = rendered.subject();
            html = template.getBodyFormat() == RecruitmentEmailBodyFormat.HTML
                    ? rendered.body()
                    : escapeHtml(rendered.body()).replace("\n", "<br>");
        } else {
            // Deliberately two-way, not three: an OFFER meeting shares the
            // neutral "Samtale hos Trustworks" with the rounds. The
            // candidate-facing surface never names the pipeline phase.
            subject = interview.getKind() == RecruitmentInterviewKind.INFORMAL
                    ? "Uformel snak hos Trustworks"
                    : "Samtale hos Trustworks";
            html = escapeHtml(invitationBody(interview, candidate, true, resolved))
                    .replace("\n", "<br>");
        }
        // The sanitizer is mandatory: stored HTML goes straight to Outlook.
        html = RecruitmentEmailHtmlSanitizer.clean(html);
        html = dropEmptyParagraphs(html);
        if (joinUrl != null && !joinUrl.isBlank() && joinUrl.startsWith("https://")) {
            // Appended AFTER sanitizing: this block is ours, and the href
            // is attribute-escaped — the sanitizer would strip the anchor.
            html = html + "<p><strong>Microsoft Teams:</strong> <a href=\""
                    + escapeHtml(joinUrl) + "\">Deltag i mødet</a></p>";
        }
        return new CandidateInvitation(subject, html);
    }

    /** A paragraph left holding nothing after the merge. */
    private static final java.util.regex.Pattern EMPTY_PARAGRAPH =
            java.util.regex.Pattern.compile("<p>(?:\\s|&nbsp;|<br\\s*/?>)*</p>");

    /**
     * Drop the paragraphs the merge left empty.
     * <p>
     * {@code {{arrival_instructions}}} and {@code {{visiting_address}}}
     * resolve to an EMPTY STRING on an online interview — they must resolve,
     * because an unresolved token reaches the candidate as literal braces —
     * so a paragraph holding one survives the merge as {@code <p></p>} and
     * Outlook renders it as a stray blank line on every Teams invitation.
     * <p>
     * Runs on our own sanitized output, after jsoup has balanced and
     * normalised the markup, so the match is exact rather than a guess at
     * whatever the template author typed.
     */
    static String dropEmptyParagraphs(String html) {
        return html == null ? null : EMPTY_PARAGRAPH.matcher(html).replaceAll("");
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * The manual-mode .ics fallback (plan Phase 6): the interview as an
     * iCalendar invitation — works with the Graph toggle OFF, that being
     * the point. Attendees are the interviewers + the candidate (when
     * they have an email); the organizer follows the same resolution as
     * events, so the file round-trips into the mailbox a later Graph
     * event would live in.
     */
    public String icsFor(RecruitmentInterview interview, RecruitmentCandidate candidate) {
        Objects.requireNonNull(interview.getScheduledAt(), "scheduledAt must be set for an invitation");
        List<InterviewIcsWriter.IcsAttendee> attendees = new ArrayList<>();
        // Every interviewer, including whoever organizes: iCalendar lists the
        // ORGANIZER property and the ATTENDEE lines independently, so unlike a
        // Graph event there is nobody to exclude here.
        List<Interviewer> roster = resolveInterviewers(interview.getInterviewerUuids());
        for (Interviewer interviewer : roster) {
            // An iCalendar ATTENDEE line is a mailto: URI — someone we could
            // name but not place has no line to write, so they ride on the
            // body's name list instead of producing "MAILTO:null".
            if (interviewer.email() == null || interviewer.email().isBlank()) {
                continue;
            }
            attendees.add(new InterviewIcsWriter.IcsAttendee(
                    interviewer.email(), interviewer.name()));
        }
        boolean candidateInvited = candidate != null
                && candidate.getEmail() != null && !candidate.getEmail().isBlank();
        if (candidateInvited) {
            attendees.add(new InterviewIcsWriter.IcsAttendee(
                    candidate.getEmail(), candidateName(candidate)));
        }
        String summary = interviewSubject(interview, candidateName(candidate));
        return InterviewIcsWriter.write(
                interview.getUuid() + "@trustworks.dk",
                interview.getScheduledAt(),
                interview.getDurationMinutes(),
                summary,
                interview.getLocation(),
                invitationBody(interview, candidate, candidateInvited,
                        invitationDetails(interview, roster)),
                organizerMailbox(interview),
                attendees,
                LocalDateTime.now(java.time.ZoneOffset.UTC));
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
     *
     * @param details who the candidate is meeting and, for a physical
     *                interview, where and what to do on arrival; each part
     *                is omitted from the text when it is empty, and
     *                {@code null} is tolerated as "nothing to add"
     */
    static String invitationBody(RecruitmentInterview interview,
                                 RecruitmentCandidate candidate,
                                 boolean candidateInvited,
                                 InvitationDetails details) {
        if (!candidateInvited) {
            return "Scheduled via the Trustworks intranet — see /recruitment/interviews for the interview kit.";
        }
        InvitationDetails resolved = details == null ? InvitationDetails.NONE : details;
        // Danish addresses people by first name; without one, greet
        // namelessly rather than "Kære <surname>", which reads wrong.
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName().trim();
        String greeting = first.isEmpty() ? "Hej" : "Kære " + first;
        // Two-way by design: an OFFER meeting takes the same neutral
        // wording as a round — the candidate is told when and where, not
        // which phase of our pipeline they are in.
        String occasion = interview.getKind() == RecruitmentInterviewKind.INFORMAL
                ? "en uformel snak med dig"
                : "at møde dig til samtale";
        StringBuilder body = new StringBuilder(greeting)
                .append("\n\nVi glæder os til ").append(occasion).append(" hos Trustworks.");
        // Time and place ARE the invitation's own fields and are not
        // repeated here — repeating them only invites drift when the event
        // is updated. The participants are NOT: since the V493 split the
        // candidate's event carries only the candidate (buildCandidateEvent
        // invites List.of(candidate)), so there is no attendee list for them
        // to read and this body is the only place the panel can be named.
        // The reception iPad then asks them who they are visiting.
        if (!resolved.interviewerNames().isEmpty()) {
            body.append("\n\nDu skal møde ").append(resolved.interviewerNames()).append(".");
        }
        if (!resolved.arrivalInstructions().isEmpty()) {
            body.append("\n\n").append(resolved.arrivalInstructions());
        }
        return body.append("\n\nEr du forhindret, eller har du spørgsmål inden vi ses,"
                        + " er du velkommen til at svare på denne invitation.")
                .append("\n\nMed venlig hilsen\nTrustworks")
                .toString();
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
        String organizer = configuredOrganizer();
        if (organizer != null) {
            return organizer;
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
