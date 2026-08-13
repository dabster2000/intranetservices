package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.CalendarStatusResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateInterviewsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.DebriefResponse;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewCreateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewResponse;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewScheduleRequest;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewScorecardsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.InterviewerAvailabilityResponse;
import dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.MyInterviewsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ScheduleGridResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ScorecardSubmitRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SuggestedSlotsResponse;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCalendarService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentInterviewService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST entry point for the interview loop (ATS plan §P11, spec §5.3/§6.2):
 * interview scheduling, blind scorecards, debrief and the interviewer's own
 * list. Thin by convention: flag gate → actor resolution →
 * visibility/decision check → delegate to
 * {@link RecruitmentInterviewService}.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Scheduling mutations require {@code recruitment:write} + decision
 *       rights on the position; scorecard submission and the "mine" list
 *       require {@code recruitment:interview} (the interviewer scope, P1) —
 *       the caller must be an ASSIGNED interviewer (per-candidate
 *       assignment, spec §7.2), enforced in the service.</li>
 *   <li>Reads answer 404 for invisible rows (never 403). An assigned
 *       interviewer may read their interview's surfaces even without
 *       position visibility — the P11 interviewer grant.</li>
 *   <li>Feature flag {@code recruitment.interviews.enabled} (core flag 2):
 *       off + non-admin caller → 404, same convention as the sibling
 *       resources; admins bypass for dark testing.</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"recruitment:read"})
public class RecruitmentInterviewResource {

    private static final String ADMIN_WILDCARD = "admin:*";
    private static final int LOCATION_MAX_LENGTH = 200;
    private static final int ROOM_EMAIL_MAX_LENGTH = 255;
    private static final int DURATION_MIN_MINUTES = 15;
    private static final int DURATION_MAX_MINUTES = 480;
    private static final int DEFAULT_DURATION_MINUTES = 60;

    /** Mirrors the FE interviewer dropdown (useEmployedUsers): who can be
     * picked as an interviewer, and therefore whose free/busy is probed. */
    private static final String[] INTERVIEWER_STATUSES =
            {"ACTIVE", "PAID_LEAVE", "MATERNITY_LEAVE", "NON_PAY_LEAVE"};
    private static final String[] INTERVIEWER_TYPES = {"CONSULTANT", "STAFF", "STUDENT"};

    /** Bound on the schedule-grid / suggested-slots probe set: 20 is one
     * Graph {@code getSchedule} batch — no dialog picks more people. */
    private static final int MAX_GRID_INTERVIEWERS = 20;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    RecruitmentVisibility visibility;

    @Inject
    RecruitmentInterviewService interviewService;

    @Inject
    RecruitmentCalendarService calendarService;

    @Inject
    UserService userService;

    // ---- Scheduling ------------------------------------------------------------

    /** Create (= schedule) an interview on an application. */
    @POST
    @Path("/applications/{uuid}/interviews")
    @RolesAllowed({"recruitment:write"})
    public Response create(@PathParam("uuid") UUID applicationUuid,
                           InterviewCreateRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        if (request.kind() == null) {
            throw badRequest("kind is required — ROUND or INFORMAL");
        }
        if (request.scheduledAt() == null) {
            throw badRequest("scheduledAt is required — interviews are scheduled with a time");
        }
        requireLocationWithinLimit(request.location());
        requireRoomEmailValid(request.roomEmail());
        requireDurationValid(request.durationMinutes());
        UUID actor = currentActor();
        RecruitmentApplication application = requireVisibleApplication(applicationUuid, actor);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);
        RecruitmentCandidate candidate = requireCandidate(application.getCandidateUuid());

        RecruitmentInterview interview =
                interviewService.create(application, position, candidate, request, actor);
        return Response.created(URI.create("/recruitment/interviews/" + interview.getUuid()))
                .entity(interviewService.scorecardsFor(actor.toString(), interview,
                        application, position))
                .build();
    }

    /** Reschedule (new time mandatory; location/interviewers optional). */
    @POST
    @Path("/interviews/{uuid}/schedule")
    @RolesAllowed({"recruitment:write"})
    public InterviewScorecardsResponse reschedule(@PathParam("uuid") UUID interviewUuid,
                                                  InterviewScheduleRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        if (request.scheduledAt() == null) {
            throw badRequest("scheduledAt is required");
        }
        requireLocationWithinLimit(request.location());
        requireRoomEmailValid(request.roomEmail());
        requireDurationValid(request.durationMinutes());
        UUID actor = currentActor();
        RecruitmentInterview interview = requireVisibleInterview(interviewUuid, actor);
        RecruitmentApplication application = applicationOf(interview);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);
        RecruitmentCandidate candidate = requireCandidate(application.getCandidateUuid());

        interviewService.reschedule(interview, application, position, candidate, request, actor);
        return interviewService.scorecardsFor(actor.toString(), interview, application, position);
    }

    /** Cancel — the Outlook event (when synced) is cancelled too. */
    @POST
    @Path("/interviews/{uuid}/cancel")
    @RolesAllowed({"recruitment:write"})
    public Response cancel(@PathParam("uuid") UUID interviewUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentInterview interview = requireVisibleInterview(interviewUuid, actor);
        RecruitmentApplication application = applicationOf(interview);
        RecruitmentPosition position = positionOf(application);
        requireDecisionRights(position, actor);

        interviewService.cancel(interview, application, position, actor);
        return Response.noContent().build();
    }

    // ---- Scorecards ---------------------------------------------------------------

    /** Submit the caller's OWN blind scorecard (assigned interviewers only). */
    @POST
    @Path("/interviews/{uuid}/scorecards")
    @RolesAllowed({"recruitment:interview"})
    public InterviewScorecardsResponse submitScorecard(@PathParam("uuid") UUID interviewUuid,
                                                       ScorecardSubmitRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        UUID actor = currentActor();
        RecruitmentInterview interview = requireVisibleInterview(interviewUuid, actor);
        RecruitmentApplication application = applicationOf(interview);
        RecruitmentPosition position = positionOf(application);

        interviewService.submitScorecard(interview, application, position, request, actor);
        return interviewService.scorecardsFor(actor.toString(), interview, application, position);
    }

    /** The blind-filtered scorecard view of one interview. */
    @GET
    @Path("/interviews/{uuid}/scorecards")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public InterviewScorecardsResponse scorecards(@PathParam("uuid") UUID interviewUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentInterview interview = requireVisibleInterview(interviewUuid, actor);
        RecruitmentApplication application = applicationOf(interview);
        RecruitmentPosition position = positionOf(application);
        return interviewService.scorecardsFor(actor.toString(), interview, application, position);
    }

    /** The per-round debrief for an application (side-by-side unlock rules inside). */
    @GET
    @Path("/applications/{uuid}/debrief")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public DebriefResponse debrief(@PathParam("uuid") UUID applicationUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentApplication application = requireApplicationForInterviewRead(applicationUuid, actor);
        RecruitmentPosition position = positionOf(application);
        return interviewService.debrief(actor.toString(), application, position);
    }

    // ---- Lists ----------------------------------------------------------------------

    /** All interviews across the candidate's applications the caller may see. */
    @GET
    @Path("/candidates/{uuid}/interviews")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public CandidateInterviewsResponse listForCandidate(@PathParam("uuid") UUID candidateUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null
                || !visibility.canReadCandidateProfile(actor.toString(), candidate)) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return interviewService.listForCandidate(actor.toString(), candidate);
    }

    /** The caller's own upcoming + recent interviews with the kit. */
    @GET
    @Path("/interviews/mine")
    @RolesAllowed({"recruitment:interview"})
    public MyInterviewsResponse mine() {
        enforceFlag();
        UUID actor = currentActor();
        return interviewService.listMine(actor.toString());
    }

    /**
     * The bookable meeting rooms for the scheduling dialog's room picker.
     * Empty (never an error) when the Graph calendar toggle is off or the
     * lookup fails — the UI hides the picker. Write-tier: only schedulers
     * need the list.
     * <p>
     * Optional {@code start} (ISO local datetime, wall-clock
     * Europe/Copenhagen): when present, each room carries its free/busy
     * state for the interview slot beginning there — the picker then
     * offers only free rooms. Optional {@code durationMinutes} (15..480,
     * default 60) sets the slot length. Invalid values → 400.
     */
    @GET
    @Path("/interviews/rooms")
    @RolesAllowed({"recruitment:write"})
    public MeetingRoomsResponse rooms(@QueryParam("start") String start,
                                      @QueryParam("durationMinutes") Integer durationMinutes) {
        enforceFlag();
        LocalDateTime slotStart = parseOptionalStart(start);
        int duration = requireDurationValid(durationMinutes);
        List<MeetingRoomsResponse.MeetingRoom> rooms = calendarService.listRooms(slotStart, duration);
        return new MeetingRoomsResponse(rooms, rooms.size());
    }

    /**
     * Outlook free/busy per potential interviewer for one interview slot
     * — the interviewer picker's availability markers. The probed set
     * mirrors the FE dropdown: currently employed CONSULTANT/STAFF/
     * STUDENT users. Strictly free/busy booleans — event details are
     * never requested from Graph and never ride here. Empty (never an
     * error) when the Graph calendar toggle is off; a {@code null}
     * {@code available} means unknown (no mailbox or lookup failure) and
     * must render unmarked, not busy. Write-tier: only schedulers need it.
     * <p>
     * {@code start} (ISO local datetime, wall-clock Europe/Copenhagen) is
     * required; optional {@code durationMinutes} (15..480, default 60)
     * sets the slot length. Invalid values → 400.
     */
    @GET
    @Path("/interviews/interviewer-availability")
    @RolesAllowed({"recruitment:write"})
    public InterviewerAvailabilityResponse interviewerAvailability(
            @QueryParam("start") String start,
            @QueryParam("durationMinutes") Integer durationMinutes) {
        enforceFlag();
        LocalDateTime slotStart = parseOptionalStart(start);
        if (slotStart == null) {
            throw badRequest("start is required — availability is per interview slot");
        }
        int duration = requireDurationValid(durationMinutes);
        if (!calendarService.isEnabled()) {
            return new InterviewerAvailabilityResponse(List.of(), 0);
        }

        List<User> users = userService.findUsersByDateAndStatusListAndTypes(
                LocalDate.now(), INTERVIEWER_STATUSES, INTERVIEWER_TYPES, true);
        List<String> emails = users.stream()
                .map(User::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .toList();
        // Graph echoes the requested address as scheduleId; compare
        // case-insensitively to be safe.
        Map<String, Boolean> freeByEmail = new java.util.HashMap<>();
        calendarService.interviewerAvailability(emails, slotStart, duration)
                .forEach((email, free) -> freeByEmail.put(email.toLowerCase(Locale.ROOT), free));

        List<InterviewerAvailabilityResponse.InterviewerAvailability> availability = users.stream()
                .map(user -> new InterviewerAvailabilityResponse.InterviewerAvailability(
                        user.getUuid(),
                        user.getEmail() == null ? null
                                : freeByEmail.get(user.getEmail().toLowerCase(Locale.ROOT))))
                .toList();
        return new InterviewerAvailabilityResponse(availability, availability.size());
    }

    /**
     * The manual-mode invitation file (plan Phase 6): the interview as a
     * downloadable iCalendar REQUEST — the fallback whenever Graph writes
     * are off or an interview has no Outlook event. Same authz posture as
     * every other interview read surface: the interview UUID is NOT a
     * capability token — role + per-viewer visibility are enforced, and
     * invisible rows answer 404. The filename derives from the UUID
     * alone, so the Content-Disposition header cannot carry injected text.
     */
    @GET
    @Path("/interviews/{uuid}/ics")
    @Produces("text/calendar")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public Response ics(@PathParam("uuid") UUID interviewUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentInterview interview = requireVisibleInterview(interviewUuid, actor);
        if (interview.getScheduledAt() == null) {
            throw badRequest("This interview has no time yet — nothing to invite to");
        }
        RecruitmentApplication application = applicationOf(interview);
        RecruitmentCandidate candidate =
                RecruitmentCandidate.findById(application.getCandidateUuid());
        String ics = calendarService.icsFor(interview, candidate);
        return Response.ok(ics, "text/calendar; charset=utf-8")
                .header("Content-Disposition",
                        "attachment; filename=\"interview-" + interview.getUuid() + ".ics\"")
                .build();
    }

    /**
     * The Outlook event's live RSVP + drift status (plan Phase 5): who
     * has accepted/declined, and whether the event was moved in Outlook
     * behind the intranet's back. On-demand only — the UI asks when a
     * dialog opens or a row expands; there are no webhooks. Unknown
     * ({@code known=false}, empty rsvps) when the interview has no
     * Outlook event, the Graph toggle is off, or the read failed.
     * Read-tier + the same per-viewer visibility rule as every other
     * interview read surface (assigned interviewer OR position access).
     */
    @GET
    @Path("/interviews/{uuid}/calendar-status")
    @RolesAllowed({"recruitment:read", "recruitment:interview"})
    public CalendarStatusResponse calendarStatus(@PathParam("uuid") UUID interviewUuid) {
        enforceFlag();
        UUID actor = currentActor();
        RecruitmentInterview interview = requireVisibleInterview(interviewUuid, actor);
        RecruitmentApplication application = applicationOf(interview);
        RecruitmentCandidate candidate =
                RecruitmentCandidate.findById(application.getCandidateUuid());
        return calendarService.eventStatus(interview, candidate);
    }

    /**
     * One day's availability grid for the scheduling dialog's assistant
     * pane: one row per chosen interviewer plus the chosen room, 48
     * 15-minute cells over 07:00–19:00 Europe/Copenhagen. Strictly
     * free/busy digits + working hours — event details are never
     * requested from Graph and never ride here (same privacy stance as
     * the availability booleans). Empty {@code entries} (never an error)
     * when the Graph calendar toggle is off. Write-tier: only schedulers
     * need it.
     * <p>
     * {@code date} (ISO date) is required; {@code interviewerUuids} is a
     * comma-separated list of user UUIDs (at most
     * {@value #MAX_GRID_INTERVIEWERS}); {@code roomEmail} optionally adds
     * the room's row. Invalid values → 400.
     */
    @GET
    @Path("/interviews/schedule-grid")
    @RolesAllowed({"recruitment:write"})
    public ScheduleGridResponse scheduleGrid(@QueryParam("date") String date,
                                             @QueryParam("interviewerUuids") String interviewerUuids,
                                             @QueryParam("roomEmail") String roomEmail) {
        enforceFlag();
        LocalDate day = parseRequiredDate(date);
        List<String> uuids = parseInterviewerUuids(interviewerUuids, false);
        requireRoomEmailValid(roomEmail);
        String dayStart = AvailabilitySlotSuggester.DAY_WINDOW_START.format(HH_MM);
        if (!calendarService.isEnabled()) {
            return new ScheduleGridResponse(day,
                    AvailabilitySlotSuggester.INTERVAL_MINUTES, dayStart, List.of());
        }

        Map<String, String> emailByUuid = new LinkedHashMap<>();
        for (String uuid : uuids) {
            User user = User.findById(uuid);
            emailByUuid.put(uuid, user != null ? user.getEmail() : null);
        }
        List<String> mailboxes = new ArrayList<>(emailByUuid.values().stream()
                .filter(email -> email != null && !email.isBlank())
                .toList());
        boolean roomRequested = roomEmail != null && !roomEmail.isBlank();
        if (roomRequested) {
            mailboxes.add(roomEmail);
        }

        // Graph echoes the requested address as scheduleId; compare
        // case-insensitively to be safe.
        Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> byEmail =
                new java.util.HashMap<>();
        calendarService.daySchedule(mailboxes, day)
                .forEach((email, schedule) -> byEmail.put(email.toLowerCase(Locale.ROOT), schedule));

        List<ScheduleGridResponse.GridEntry> entries = new ArrayList<>();
        emailByUuid.forEach((uuid, email) -> {
            AvailabilitySlotSuggester.MailboxWindowSchedule schedule =
                    email == null || email.isBlank()
                            ? null
                            : byEmail.get(email.toLowerCase(Locale.ROOT));
            entries.add(new ScheduleGridResponse.GridEntry(uuid, "USER",
                    schedule != null ? schedule.availabilityView() : null,
                    toWorkingHoursDto(schedule != null ? schedule.workingHours() : null)));
        });
        if (roomRequested) {
            AvailabilitySlotSuggester.MailboxWindowSchedule schedule =
                    byEmail.get(roomEmail.toLowerCase(Locale.ROOT));
            entries.add(new ScheduleGridResponse.GridEntry(roomEmail, "ROOM",
                    schedule != null ? schedule.availabilityView() : null,
                    toWorkingHoursDto(schedule != null ? schedule.workingHours() : null)));
        }
        return new ScheduleGridResponse(day,
                AvailabilitySlotSuggester.INTERVAL_MINUTES, dayStart, entries);
    }

    /**
     * Ranked slot suggestions for the chosen interviewers — the dialog's
     * suggestion chips. Every suggested slot has all chosen interviewers
     * free, lies inside their working-hours intersection, on a weekday;
     * it carries the smallest free room seating everyone when one exists.
     * Empty (never an error) when the Graph calendar toggle is off or
     * availability could not be read — the chips row is simply absent,
     * same degrade posture as the room picker. Write-tier: only
     * schedulers need it.
     * <p>
     * {@code interviewerUuids} (comma-separated user UUIDs, at most
     * {@value #MAX_GRID_INTERVIEWERS}) is required; optional
     * {@code durationMinutes} (15..480, default 60) and {@code from}
     * (ISO date, default today). Invalid values → 400.
     */
    @GET
    @Path("/interviews/suggested-slots")
    @RolesAllowed({"recruitment:write"})
    public SuggestedSlotsResponse suggestedSlots(
            @QueryParam("interviewerUuids") String interviewerUuids,
            @QueryParam("durationMinutes") Integer durationMinutes,
            @QueryParam("from") String from) {
        enforceFlag();
        List<String> uuids = parseInterviewerUuids(interviewerUuids, true);
        int duration = requireDurationValid(durationMinutes);
        LocalDate fromDay = parseOptionalDate(from);
        if (!calendarService.isEnabled()) {
            return new SuggestedSlotsResponse(List.of());
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Copenhagen"));
        if (fromDay == null) {
            fromDay = now.toLocalDate();
        }
        List<String> emails = uuids.stream()
                .map(uuid -> (User) User.findById(uuid))
                .map(user -> user != null ? user.getEmail() : null)
                .filter(email -> email != null && !email.isBlank())
                .toList();
        // The candidate joins the interviewers in the room.
        int headcount = uuids.size() + 1;
        List<AvailabilitySlotSuggester.Slot> slots =
                calendarService.suggestedSlots(emails, duration, fromDay, headcount, now);
        return new SuggestedSlotsResponse(slots.stream()
                .map(slot -> new SuggestedSlotsResponse.SuggestedSlot(
                        slot.start(), slot.durationMinutes(),
                        slot.roomEmail(), slot.roomDisplayName()))
                .toList());
    }

    // ---- Helpers ----------------------------------------------------------------------

    private RecruitmentCandidate requireCandidate(String candidateUuid) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        if (candidate == null) {
            // FK makes this unreachable; defensive all the same.
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return candidate;
    }

    /**
     * Resolve the application and apply the per-viewer visibility rule
     * (position semantics — same helper as the sibling application
     * resource): an existing-but-invisible application answers the same
     * 404 as a nonexistent one.
     */
    private RecruitmentApplication requireVisibleApplication(UUID applicationUuid, UUID viewer) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(applicationUuid.toString());
        if (application == null
                || !visibility.canReadApplication(viewer.toString(), application)) {
            throw new NotFoundException("Application not found: " + applicationUuid);
        }
        return application;
    }

    /**
     * Resolve an interview the viewer may access: assigned interviewer OR
     * position-visible application. Invisible rows answer the same 404 as
     * nonexistent ones.
     */
    private RecruitmentInterview requireVisibleInterview(UUID interviewUuid, UUID viewer) {
        RecruitmentInterview interview = RecruitmentInterview.findById(interviewUuid.toString());
        if (interview == null) {
            throw new NotFoundException("Interview not found: " + interviewUuid);
        }
        RecruitmentApplication application = applicationOf(interview);
        if (!interviewService.canAccessInterview(viewer.toString(), interview, application)) {
            throw new NotFoundException("Interview not found: " + interviewUuid);
        }
        return interview;
    }

    /**
     * Resolve the application for interview-read surfaces (debrief): the
     * position rule OR an interview assignment on this application.
     */
    private RecruitmentApplication requireApplicationForInterviewRead(UUID applicationUuid,
                                                                      UUID viewer) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(applicationUuid.toString());
        if (application == null) {
            throw new NotFoundException("Application not found: " + applicationUuid);
        }
        if (visibility.canReadApplication(viewer.toString(), application)) {
            return application;
        }
        boolean assignedOnAny = RecruitmentInterview
                .<RecruitmentInterview>list("applicationUuid", application.getUuid())
                .stream()
                .anyMatch(i -> i.isActive() && i.isAssigned(viewer.toString()));
        if (!assignedOnAny) {
            throw new NotFoundException("Application not found: " + applicationUuid);
        }
        return application;
    }

    private RecruitmentApplication applicationOf(RecruitmentInterview interview) {
        RecruitmentApplication application =
                RecruitmentApplication.findById(interview.getApplicationUuid());
        if (application == null) {
            // FK makes this unreachable; defensive all the same.
            throw new NotFoundException("Application not found: " + interview.getApplicationUuid());
        }
        return application;
    }

    private RecruitmentPosition positionOf(RecruitmentApplication application) {
        RecruitmentPosition position = RecruitmentPosition.findById(application.getPositionUuid());
        if (position == null) {
            throw new NotFoundException("Position not found: " + application.getPositionUuid());
        }
        return position;
    }

    /**
     * Scheduling mutations require decision rights on the position (spec
     * §7.2) — same tier as stage moves: admin/recruiter everywhere,
     * teamlead/hiring owner on their own positions, circle OWNER/RECRUITER
     * on partner track. Assigned interviewers do NOT reschedule.
     */
    private void requireDecisionRights(RecruitmentPosition position, UUID actor) {
        if (!visibility.canDecideOnApplication(actor.toString(), position)) {
            throw new WebApplicationException(
                    "Only the recruiter, the hiring owner or the position's teamlead may schedule interviews",
                    Response.Status.FORBIDDEN);
        }
    }

    private static WebApplicationException badRequest(String message) {
        return new WebApplicationException(message, Response.Status.BAD_REQUEST);
    }

    /** Explicit server-side cap — bean validation is inert (house rule). */
    private static void requireLocationWithinLimit(String location) {
        if (location != null && location.length() > LOCATION_MAX_LENGTH) {
            throw badRequest("location must be at most " + LOCATION_MAX_LENGTH + " characters");
        }
    }

    /**
     * Room mailbox sanity — length cap plus a minimal address shape.
     * Blank is allowed: on reschedule it means "clear the booking".
     */
    private static void requireRoomEmailValid(String roomEmail) {
        if (roomEmail == null || roomEmail.isBlank()) {
            return;
        }
        if (roomEmail.length() > ROOM_EMAIL_MAX_LENGTH) {
            throw badRequest("roomEmail must be at most " + ROOM_EMAIL_MAX_LENGTH + " characters");
        }
        if (!roomEmail.contains("@")) {
            throw badRequest("roomEmail must be a room mailbox address");
        }
    }

    /**
     * Duration sanity (create/reschedule bodies and the free/busy query
     * params share the rule): null falls back to the 60-minute default;
     * anything outside 15..480 → 400. The UI offers 30/60/90/120/240.
     *
     * @return the effective duration in minutes
     */
    private static int requireDurationValid(Integer durationMinutes) {
        if (durationMinutes == null) {
            return DEFAULT_DURATION_MINUTES;
        }
        if (durationMinutes < DURATION_MIN_MINUTES || durationMinutes > DURATION_MAX_MINUTES) {
            throw badRequest("durationMinutes must be between " + DURATION_MIN_MINUTES
                    + " and " + DURATION_MAX_MINUTES);
        }
        return durationMinutes;
    }

    /** Blank/absent → null; anything else must parse as ISO local datetime. */
    private static LocalDateTime parseOptionalStart(String start) {
        if (start == null || start.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(start);
        } catch (DateTimeParseException e) {
            throw new WebApplicationException("start must be an ISO local datetime", 400);
        }
    }

    /** Blank/absent → null; anything else must parse as an ISO date. */
    private static LocalDate parseOptionalDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw badRequest("date must be an ISO date (yyyy-MM-dd)");
        }
    }

    private static LocalDate parseRequiredDate(String date) {
        LocalDate parsed = parseOptionalDate(date);
        if (parsed == null) {
            throw badRequest("date is required (ISO date, yyyy-MM-dd)");
        }
        return parsed;
    }

    /**
     * The {@code interviewerUuids} query param: comma-separated user
     * UUIDs, deduplicated in order. Malformed UUIDs and oversized lists →
     * 400; an empty list → 400 only when {@code required}.
     */
    private static List<String> parseInterviewerUuids(String interviewerUuids, boolean required) {
        LinkedHashSet<String> uuids = new LinkedHashSet<>();
        if (interviewerUuids != null && !interviewerUuids.isBlank()) {
            for (String token : interviewerUuids.split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    uuids.add(UUID.fromString(trimmed).toString());
                } catch (IllegalArgumentException e) {
                    throw badRequest("interviewerUuids must be comma-separated user UUIDs");
                }
            }
        }
        if (required && uuids.isEmpty()) {
            throw badRequest("interviewerUuids is required");
        }
        if (uuids.size() > MAX_GRID_INTERVIEWERS) {
            throw badRequest("interviewerUuids must list at most " + MAX_GRID_INTERVIEWERS + " users");
        }
        return List.copyOf(uuids);
    }

    /** Suggester working hours → DTO shape (lowercase weekday names, HH:mm). */
    private static ScheduleGridResponse.WorkingHours toWorkingHoursDto(
            AvailabilitySlotSuggester.WorkingHours workingHours) {
        if (workingHours == null) {
            return null;
        }
        List<String> days = workingHours.days() == null ? null
                : workingHours.days().stream()
                        .sorted()
                        .map(day -> day.name().toLowerCase(Locale.ROOT))
                        .toList();
        return new ScheduleGridResponse.WorkingHours(
                days,
                workingHours.start() != null ? workingHours.start().format(HH_MM) : null,
                workingHours.end() != null ? workingHours.end().format(HH_MM) : null,
                workingHours.timeZoneName());
    }

    /**
     * Block the request when {@code recruitment.interviews.enabled} is off,
     * unless the caller holds {@code admin:*} (core-flag admin bypass —
     * dark testing). 404, not 503: the feature's existence must not leak.
     */
    private void enforceFlag() {
        if (featureFlag.isInterviewsEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    /**
     * Resolve the acting user from {@code X-Requested-By} (set by
     * {@code HeaderInterceptor}). 400 when absent — every rule on this
     * resource is per-user.
     */
    private UUID currentActor() {
        String userUuid = requestHeaderHolder.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By header is required",
                    Response.Status.BAD_REQUEST);
        }
        try {
            return UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "X-Requested-By is not a valid UUID",
                    Response.Status.BAD_REQUEST);
        }
    }
}
