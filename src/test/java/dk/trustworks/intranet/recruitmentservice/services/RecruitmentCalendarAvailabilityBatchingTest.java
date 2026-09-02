package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.graph.GraphCalendarClient;
import dk.trustworks.intranet.graph.GraphMailboxConcurrencyLimiter;
import dk.trustworks.intranet.graph.GraphResponseExceptionMapper.GraphApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Free/busy batching, regression cover for the production defect found
 * 2026-08-11: interviewer availability came back marked for only the first
 * 20 of ~140 potential interviewers.
 * <p>
 * Two causes, both fixed in
 * {@link RecruitmentCalendarService#mailboxAvailability}: the caller mailbox
 * in the {@code getSchedule} URL was each batch's own first address (an
 * employee with no tenant mailbox 404s it), and the catch sat outside the
 * batch loop (so that 404 also skipped every later batch).
 * <p>
 * Plain unit test — no Quarkus boot: the availability path touches nothing
 * but the mocked Graph client, so it belongs in the DB-free tier that gates
 * deploys.
 */
class RecruitmentCalendarAvailabilityBatchingTest {

    private static final LocalDateTime SLOT = LocalDateTime.of(2026, 8, 1, 10, 0);

    private RecruitmentCalendarService service;
    private GraphCalendarClient graph;

    private List<Long> slept;

    @BeforeEach
    void setUp() {
        graph = mock(GraphCalendarClient.class);
        service = new RecruitmentCalendarService();
        service.graphApiClient = graph;
        service.calendarEnabled = true;
        // @Inject fields are null under a bare `new` — the limiter is on the
        // hot path of every probe, so it has to be supplied here.
        service.mailboxLimiter = new GraphMailboxConcurrencyLimiter(2, 50);
        // Ditto the room policy (V513): suggestedSlots asks it which rooms
        // automation may book. These tests are about mailbox batching, so the
        // policy is a pass-through that enables every room Graph returns —
        // the pre-V513 behaviour, which is what their assertions describe.
        service.roomPolicyService = new RecruitmentMeetingRoomPolicyService() {
            @Override
            public java.util.List<dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomsResponse.MeetingRoom>
                    enabledRoomsInPriorityOrder(
                            java.util.List<dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomsResponse.MeetingRoom> graphRooms) {
                return graphRooms == null ? java.util.List.of() : graphRooms;
            }
        };
        // The caller anchor rotates and the cursor is shared across requests;
        // pin it so batch→caller mapping is deterministic in tests.
        service.callerCursor.set(0);
        slept = new ArrayList<>();
        service.sleeper = slept::add;
    }

    @Test
    void mailboxLessCallerAtBatchHead_costsNothing_everyoneStillResolves() {
        // 45 interviewers = 3 batches. u20 heads batch 2 and has no mailbox
        // in the tenant: asking AS u20 404s. Before the 2026-08-11 fix that
        // blanked batches 2 AND 3 — 20 of 45 marked.
        List<String> mailboxes = mailboxes(45);
        echoSchedulesUnlessCallerIs("u20@trustworks.dk");

        var result = service.interviewerAvailability(mailboxes, SLOT, 60);

        assertEquals(45, result.freeByMailbox().size(),
                "every probed mailbox resolves, invalid caller or not");
        assertTrue(result.freeByMailbox().values().stream().allMatch(Boolean::booleanValue));
        assertTrue(result.complete(), "nothing went unasked");
        verify(graph, never()).getSchedule(eq("u20@trustworks.dk"), any());
    }

    @Test
    void mailboxLessFirstCaller_fallsBackToTheNextMailbox() {
        // The invalid mailbox is the anchor for batch 1, so the ladder must
        // step to the next address — a 404 says the ADDRESS is unusable, and
        // another address is the only possible remedy.
        List<String> mailboxes = mailboxes(45);
        echoSchedulesUnlessCallerIs("u0@trustworks.dk");

        var result = service.interviewerAvailability(mailboxes, SLOT, 60);

        assertEquals(45, result.freeByMailbox().size());
        assertTrue(result.complete());
        verify(graph, times(1)).getSchedule(eq("u0@trustworks.dk"), any());
        verify(graph, times(1)).getSchedule(eq("u1@trustworks.dk"), any());
    }

    @Test
    void callerAnchorRotatesAcrossBatches_ratherThanPinningOneMailbox() {
        // The 2026-08-15 MailboxConcurrency incident: every batch of a sweep
        // was asked AS the first mailbox that answered, so one request put 7-8
        // sequential calls on a single mailbox — and because the roster is
        // ordered by username, EVERY concurrent request picked the same one.
        // Microsoft caps concurrency at 4 per mailbox; a few concurrent
        // scheduling dialogs were enough to earn a 429.
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation ->
                echo(invocation.getArgument(1)));

        service.interviewerAvailability(mailboxes(60), SLOT, 60);

        ArgumentCaptor<String> callers = ArgumentCaptor.forClass(String.class);
        verify(graph, times(3)).getSchedule(callers.capture(), any());
        assertEquals(3, java.util.Set.copyOf(callers.getAllValues()).size(),
                "three batches must not share one anchor mailbox");
    }

    @Test
    void callerAnchorRotatesAcrossRequests_soConcurrentSweepsDoNotCollide() {
        // The cursor is shared, so the NEXT request starts somewhere else —
        // this is what stops several dialogs open at once from stacking their
        // calls onto the same mailbox.
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation ->
                echo(invocation.getArgument(1)));

        service.interviewerAvailability(mailboxes(20), SLOT, 60);
        service.interviewerAvailability(mailboxes(20), SLOT, 60);

        ArgumentCaptor<String> callers = ArgumentCaptor.forClass(String.class);
        verify(graph, times(2)).getSchedule(callers.capture(), any());
        assertNotEquals(callers.getAllValues().get(0), callers.getAllValues().get(1),
                "two sweeps of the same roster must not both anchor on the same mailbox");
    }

    @Test
    void knownMailboxLessCaller_isNotAskedAgainOnALaterSweep() {
        // Rotation would otherwise re-discover the same dead addresses on
        // every sweep — the one thing the old sticky caller was good at.
        echoSchedulesUnlessCallerIs("u0@trustworks.dk");

        service.interviewerAvailability(mailboxes(20), SLOT, 60);
        service.callerCursor.set(0); // aim the next sweep at u0 again
        service.interviewerAvailability(mailboxes(20), SLOT, 60);

        verify(graph, times(1)).getSchedule(eq("u0@trustworks.dk"), any());
    }

    @Test
    void oneUnresolvableBatch_doesNotTakeDownTheBatchesAfterIt() {
        // Batch 2 fails whoever asks (a 503 on that call, say). Batches 1
        // and 3 must still come back — the old catch-outside-the-loop lost
        // everything from the first failure onward.
        List<String> mailboxes = mailboxes(45);
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation -> {
            GraphCalendarClient.ScheduleRequest request = invocation.getArgument(1);
            if (request.schedules().contains("u20@trustworks.dk")) {
                throw new RuntimeException("Graph API error 503 Service Unavailable");
            }
            return echo(request);
        });

        var result = service.interviewerAvailability(mailboxes, SLOT, 60);

        assertEquals(25, result.freeByMailbox().size(), "batch 1 (20) + batch 3 (5) survive");
        assertEquals(Boolean.TRUE, result.freeByMailbox().get("u0@trustworks.dk"));
        assertEquals(Boolean.TRUE, result.freeByMailbox().get("u44@trustworks.dk"),
                "the batch AFTER the failure");
        assertNull(result.freeByMailbox().get("u20@trustworks.dk"),
                "the failed batch is unknown, not busy");
        // ...and it says so, rather than letting the gap pass for an empty
        // calendar.
        assertFalse(result.complete());
        assertTrue(result.unresolvedMailboxes().contains("u20@trustworks.dk"));
        assertFalse(result.unresolvedMailboxes().contains("u0@trustworks.dk"),
                "a batch that answered is not unresolved");
        // Bounded retries: 3 caller attempts on the failing batch, one each
        // on the two that work.
        verify(graph, times(RecruitmentCalendarService.CALLER_ATTEMPTS + 2))
                .getSchedule(anyString(), any());
    }

    @Test
    void everyBatchBroken_yieldsEmpty_neverThrows() {
        when(graph.getSchedule(anyString(), any()))
                .thenThrow(new RuntimeException("Graph API error 403 Forbidden"));

        var result = service.interviewerAvailability(mailboxes(45), SLOT, 60);

        assertTrue(result.freeByMailbox().isEmpty());
        assertFalse(result.complete(), "wholly unread is reported as unread");
    }

    // ---- Graph 429 MailboxConcurrency (production 2026-08-15) ------------------

    @Test
    void throttled429_doesNotEscalateToAnotherCallerMailbox() {
        // THE incident regression test. A 429 says nothing about the address
        // we asked as — only about our own call rate. The old ladder treated
        // it like a bad mailbox and immediately re-asked as the next address,
        // with no delay, which RELOCATED the overload instead of shedding it:
        // exactly the observed 7 failures on adam.hoppe then 3 on alberte.bang.
        when(graph.getSchedule(anyString(), any())).thenThrow(throttled(null));

        var result = service.interviewerAvailability(mailboxes(20), SLOT, 60);

        ArgumentCaptor<String> callers = ArgumentCaptor.forClass(String.class);
        verify(graph, times(2)).getSchedule(callers.capture(), any());
        assertEquals(1, java.util.Set.copyOf(callers.getAllValues()).size(),
                "one caller, retried once — the ladder must not advance on a throttle");
        assertTrue(result.freeByMailbox().isEmpty());
        assertFalse(result.complete(), "throttled is unknown-and-flagged, never free");
    }

    @Test
    void throttled429_honoursRetryAfter_cappedSoNoRequestHangs() {
        when(graph.getSchedule(anyString(), any())).thenThrow(throttled(3600));

        service.interviewerAvailability(mailboxes(20), SLOT, 60);

        assertEquals(List.of(RecruitmentCalendarService.THROTTLE_MAX_WAIT_MS), slept,
                "Graph may ask for an hour; a request behind a 60s idle timeout cannot give it");
    }

    @Test
    void throttled429_withoutRetryAfter_usesTheDefaultWait() {
        when(graph.getSchedule(anyString(), any())).thenThrow(throttled(null));

        service.interviewerAvailability(mailboxes(20), SLOT, 60);

        assertEquals(List.of(RecruitmentCalendarService.THROTTLE_DEFAULT_WAIT_MS), slept);
    }

    @Test
    void blockingBudget_isSharedAcrossTheWholeSweep() {
        // 7 batches x a 5s wait each would walk a single request into the
        // ALB's 60s idle timeout. The budget is per sweep, not per batch.
        when(graph.getSchedule(anyString(), any())).thenThrow(throttled(5));

        service.interviewerAvailability(mailboxes(140), SLOT, 60);

        assertTrue(slept.stream().mapToLong(Long::longValue).sum()
                        <= RecruitmentCalendarService.BLOCKING_BUDGET_MS,
                "total backoff must stay inside the sweep budget, got " + slept);
    }

    @Test
    void blockingBudget_alsoCoversPermitWaits_notJustBackoff() {
        // Permit waits are the OTHER blocking term, and they scale as
        // batches x ladder rungs (~24 for the full roster). Budgeting only
        // the 429 backoff would bound one term and still blow the sum: 8
        // batches x 3 rungs x 1s = 24s of pure waiting before a single Graph
        // round-trip is counted.
        // Record the waits the sweep ASKS for rather than serving them: the
        // claim under test is budget arithmetic, and making the test actually
        // block for 10s to prove it would be its own small crime.
        List<Long> requestedWaits = new ArrayList<>();
        service.mailboxLimiter = new GraphMailboxConcurrencyLimiter(1, 1000) {
            @Override
            public boolean tryAcquire(String mailbox, long waitMillis) {
                requestedWaits.add(waitMillis);
                return false; // permanently saturated
            }
        };

        var result = service.interviewerAvailability(mailboxes(140), SLOT, 60);

        assertTrue(result.freeByMailbox().isEmpty());
        assertFalse(result.complete());
        verify(graph, never()).getSchedule(anyString(), any());
        assertTrue(requestedWaits.size() >= 14,
                "the sweep really does attempt many probes: " + requestedWaits.size());
        assertTrue(requestedWaits.stream().mapToLong(Long::longValue).sum()
                        <= RecruitmentCalendarService.BLOCKING_BUDGET_MS,
                "unbudgeted this is batches x rungs x 1000ms; asked for " + requestedWaits);
    }

    @Test
    void windowSchedules_sharesOneBudgetAcrossChunks_andFlagsUnresolvedMailboxes() {
        // Method B scans in 10-day chunks. A per-chunk budget would multiply
        // the bound by the chunk count while an outbox transaction is open
        // and the 1-minute sweep tick is being skipped.
        when(graph.getSchedule(anyString(), any())).thenThrow(throttled(5));

        var probe = service.windowSchedules(List.of("u0@trustworks.dk", "u1@trustworks.dk"),
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 10, 16)); // ~60 days = 7 chunks

        assertTrue(probe.schedules().isEmpty());
        assertTrue(probe.unresolvedMailboxes().contains("u0@trustworks.dk"),
                "a mailbox whose chunk was throttled is unresolved, not merely absent");
        assertTrue(probe.anyUnresolved(List.of("U0@Trustworks.dk")),
                "anyUnresolved compares case-insensitively");
        assertTrue(slept.stream().mapToLong(Long::longValue).sum()
                        <= RecruitmentCalendarService.BLOCKING_BUDGET_MS,
                "one budget for all chunks, not one per chunk; slept " + slept);
    }

    @Test
    void windowSchedules_healthyMailboxes_areNotFlaggedUnresolved() {
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation ->
                echo(invocation.getArgument(1)));

        var probe = service.windowSchedules(List.of("u0@trustworks.dk"),
                LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 21));

        assertTrue(probe.complete(), "nothing went unasked");
        assertFalse(probe.schedules().isEmpty());
    }

    @Test
    void throttledInterviewer_suppressesSuggestions_ratherThanProposingBlind() {
        // The silent gap itself: a throttled interviewer used to reach the
        // suggester as an absent schedule, and absent means "unknown never
        // counts as busy" — so we proposed times against a calendar we had
        // never actually read.
        when(graph.listRoomsPaged(any(), any())).thenReturn(new GraphCalendarClient.RoomCollectionResponse(List.of()));
        when(graph.getSchedule(anyString(), any())).thenThrow(throttled(null));

        var suggestions = service.suggestedSlots(List.of("u0@trustworks.dk"), 60,
                LocalDate.of(2026, 8, 17), 2, LocalDateTime.of(2026, 8, 17, 7, 0));

        assertTrue(suggestions.slots().isEmpty());
        assertFalse(suggestions.availabilityComplete(),
                "an empty chip row must be distinguishable from a genuinely full fortnight");
    }

    @Test
    void mailboxLessInterviewer_stillSuggests_becauseAbsentIsNotUnresolved() {
        // Guard against over-correcting: Graph ANSWERING without a mailbox is
        // a permanent, ordinary condition (plenty of employees have none). If
        // that suppressed suggestions, any team with one such member would
        // lose the feature forever.
        when(graph.listRoomsPaged(any(), any())).thenReturn(new GraphCalendarClient.RoomCollectionResponse(List.of()));
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation -> {
            GraphCalendarClient.ScheduleRequest request = invocation.getArgument(1);
            return new GraphCalendarClient.ScheduleCollectionResponse(request.schedules().stream()
                    .filter(mailbox -> !mailbox.equals("u1@trustworks.dk"))
                    .map(mailbox -> new GraphCalendarClient.ScheduleCollectionResponse
                            .ScheduleInformation(mailbox, "0".repeat(15 * 24 * 4), null))
                    .toList());
        });

        var suggestions = service.suggestedSlots(
                List.of("u0@trustworks.dk", "u1@trustworks.dk"), 60,
                LocalDate.of(2026, 8, 17), 3, LocalDateTime.of(2026, 8, 17, 7, 0));

        assertFalse(suggestions.slots().isEmpty(), "a mailbox-less colleague must not kill suggestions");
        assertTrue(suggestions.availabilityComplete());
    }

    @Test
    void noConcurrencyPermit_isUnknownNotFree_andNeverCallsGraph() {
        service.mailboxLimiter = new GraphMailboxConcurrencyLimiter(0, 1);
        when(graph.listRoomsPaged(any(), any())).thenReturn(new GraphCalendarClient.RoomCollectionResponse(List.of()));

        var availability = service.interviewerAvailability(mailboxes(20), SLOT, 60);
        var suggestions = service.suggestedSlots(List.of("u0@trustworks.dk"), 60,
                LocalDate.of(2026, 8, 17), 2, LocalDateTime.of(2026, 8, 17, 7, 0));

        assertTrue(availability.freeByMailbox().isEmpty());
        assertFalse(availability.complete());
        assertTrue(suggestions.slots().isEmpty(), "a saturated limiter must not become a blind proposal");
        assertFalse(suggestions.availabilityComplete());
        verify(graph, never()).getSchedule(anyString(), any());
    }

    // ---- Raw schedules (day grid + suggestions, plan Phase 1) ------------------

    @Test
    void daySchedule_returnsRawDigitsAndWorkingHours_over48CellDay() {
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation -> {
            GraphCalendarClient.ScheduleRequest request = invocation.getArgument(1);
            return new GraphCalendarClient.ScheduleCollectionResponse(request.schedules().stream()
                    .map(mailbox -> new GraphCalendarClient.ScheduleCollectionResponse.ScheduleInformation(
                            mailbox, "02".repeat(24),
                            new GraphCalendarClient.ScheduleCollectionResponse.ScheduleInformation.WorkingHours(
                                    List.of("monday", "tuesday", "not-a-day"),
                                    "08:30:00.0000000", "16:00:00.0000000",
                                    new GraphCalendarClient.ScheduleCollectionResponse.ScheduleInformation
                                            .WorkingHours.TimeZoneName("Romance Standard Time"))))
                    .toList());
        });

        Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> schedules =
                service.daySchedule(List.of("u0@trustworks.dk"), LocalDate.of(2026, 8, 17))
                        .schedules();

        AvailabilitySlotSuggester.MailboxWindowSchedule schedule = schedules.get("u0@trustworks.dk");
        assertEquals("02".repeat(24), schedule.availabilityView(),
                "digits ride raw — no Boolean collapse");
        assertEquals(java.util.Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                schedule.workingHours().days(), "unknown day tokens are skipped, never fatal");
        assertEquals(LocalTime.of(8, 30), schedule.workingHours().start());
        assertEquals(LocalTime.of(16, 0), schedule.workingHours().end());
        assertEquals("Romance Standard Time", schedule.workingHours().timeZoneName());

        // The probe window is the grid contract: 07:00–19:00 on the day,
        // 15-minute cells.
        ArgumentCaptor<GraphCalendarClient.ScheduleRequest> body =
                ArgumentCaptor.forClass(GraphCalendarClient.ScheduleRequest.class);
        verify(graph).getSchedule(anyString(), body.capture());
        assertEquals("2026-08-17T07:00", body.getValue().startTime().dateTime());
        assertEquals("2026-08-17T19:00", body.getValue().endTime().dateTime());
        assertEquals(15, body.getValue().availabilityViewInterval());
    }

    @Test
    void daySchedule_toggleOff_returnsEmpty_neverTouchesGraph() {
        service.calendarEnabled = false;
        assertTrue(service.daySchedule(List.of("u0@trustworks.dk"),
                LocalDate.of(2026, 8, 17)).schedules().isEmpty());
        verifyNoInteractions(graph);
    }

    @Test
    void suggestedSlots_fetchesOneMultiDayWindow_notOneCallPerDay() {
        // The ALB idle-timeout guard: a 10-business-day scan must be ONE
        // getSchedule window per 20-mailbox batch.
        when(graph.listRoomsPaged(any(), any())).thenReturn(new GraphCalendarClient.RoomCollectionResponse(List.of(
                new GraphCalendarClient.RoomCollectionResponse.Room(
                        "place-1", "HQ meeting room 2", "room-hq2@trustworks.dk", 8, "HQ"))));
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation ->
                echo(invocation.getArgument(1)));

        // Monday 2026-08-17 → the 10th business day is Friday 2026-08-28.
        List<AvailabilitySlotSuggester.Slot> slots = service.suggestedSlots(
                List.of("u0@trustworks.dk", "u1@trustworks.dk"), 60,
                LocalDate.of(2026, 8, 17), 3, LocalDateTime.of(2026, 8, 17, 7, 0)).slots();

        ArgumentCaptor<GraphCalendarClient.ScheduleRequest> body =
                ArgumentCaptor.forClass(GraphCalendarClient.ScheduleRequest.class);
        verify(graph, times(1)).getSchedule(anyString(), body.capture());
        assertEquals("2026-08-17T07:00", body.getValue().startTime().dateTime());
        assertEquals("2026-08-28T19:00", body.getValue().endTime().dateTime());
        assertEquals(List.of("u0@trustworks.dk", "u1@trustworks.dk", "room-hq2@trustworks.dk"),
                body.getValue().schedules(), "interviewers and rooms share the batch");

        assertEquals(50, slots.size(), "echo marks everything free: 5/day × 10 business days");
        assertEquals("room-hq2@trustworks.dk", slots.get(0).roomEmail(),
                "the free 8-seat room is suggested for a headcount of 3");
    }

    @Test
    void suggestedSlots_graphFullyDown_returnsEmpty_notBlindSuggestions() {
        when(graph.listRoomsPaged(any(), any())).thenThrow(new RuntimeException("Graph API error 503"));
        when(graph.getSchedule(anyString(), any()))
                .thenThrow(new RuntimeException("Graph API error 503"));

        var suggestions = service.suggestedSlots(List.of("u0@trustworks.dk"), 60,
                LocalDate.of(2026, 8, 17), 2, LocalDateTime.of(2026, 8, 17, 7, 0));
        assertTrue(suggestions.slots().isEmpty());
        assertFalse(suggestions.availabilityComplete());
    }

    @Test
    void lastBusinessDay_countsWeekdaysOnly() {
        assertEquals(LocalDate.of(2026, 8, 28), RecruitmentCalendarService.lastBusinessDay(
                LocalDate.of(2026, 8, 17), 10), "Mon + 10 business days ends Friday week 2");
        assertEquals(LocalDate.of(2026, 8, 28), RecruitmentCalendarService.lastBusinessDay(
                LocalDate.of(2026, 8, 15), 10), "a Saturday start rolls to the same scan");
        assertEquals(LocalDate.of(2026, 8, 17), RecruitmentCalendarService.lastBusinessDay(
                LocalDate.of(2026, 8, 17), 1));
    }

    // ---- Helpers ---------------------------------------------------------------

    /** The shape a 429 arrives in: the Graph client's mapper throws its own
     * GraphApiException, never a WebApplicationException (F18). */
    private static GraphApiException throttled(Integer retryAfterSeconds) {
        return new GraphApiException(
                "Graph API error 429: Application is over its MailboxConcurrency limit",
                429, retryAfterSeconds);
    }

    private static List<String> mailboxes(int count) {
        List<String> mailboxes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            mailboxes.add("u" + i + "@trustworks.dk");
        }
        return mailboxes;
    }

    // ---- Room pagination -------------------------------------------------------
    //
    // These live in the DB-free tier deliberately: the sibling
    // RecruitmentCalendarServiceTest is a @QuarkusTest and so is NOT run by the
    // deploy gate, which would leave the pagination fix uncovered where it
    // matters.

    @Test
    void rooms_followsOdataNextLink_soPageTwoRoomsAreNotInvisible() {
        // The unpaged call saw only page one: rooms past it existed in the
        // tenant, could never be suggested, and nothing anywhere said so.
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any()))
                .thenReturn(new GraphCalendarClient.RoomCollectionResponse(
                        List.of(new GraphCalendarClient.RoomCollectionResponse.Room(
                                "p1", "Room One", "one@trustworks.dk", 4, "HQ")),
                        "https://graph.microsoft.com/v1.0/places/microsoft.graph.room?$skiptoken=TOKEN2"))
                .thenReturn(new GraphCalendarClient.RoomCollectionResponse(
                        List.of(new GraphCalendarClient.RoomCollectionResponse.Room(
                                "p2", "Room Two", "two@trustworks.dk", 8, "HQ"))));

        var rooms = service.listRooms();

        assertEquals(2, rooms.size(), "both pages are collected");
        assertEquals("one@trustworks.dk", rooms.get(0).emailAddress());
        assertEquals("two@trustworks.dk", rooms.get(1).emailAddress());
        verify(graph).listRoomsPaged(any(), eq((String) null));
        verify(graph).listRoomsPaged(any(), eq("TOKEN2"));
    }

    @Test
    void rooms_continuationFailure_keepsThePagesAlreadyCollected() {
        // A throttled continuation should cost the tail of the list, not the
        // whole room picker.
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any()))
                .thenReturn(new GraphCalendarClient.RoomCollectionResponse(
                        List.of(new GraphCalendarClient.RoomCollectionResponse.Room(
                                "p1", "Room One", "one@trustworks.dk", 4, "HQ")),
                        "https://graph.microsoft.com/v1.0/places/microsoft.graph.room?$skiptoken=TOKEN2"))
                .thenThrow(new RuntimeException("Graph 429: throttled"));

        var rooms = service.listRooms();

        assertEquals(1, rooms.size(), "page one survives the failed continuation");
        assertEquals("one@trustworks.dk", rooms.get(0).emailAddress());
    }

    @Test
    void roomLookup_reportsIncomplete_whenGraphFails_soEmptyIsNotReadAsNoRooms() {
        // "The tenant has no rooms" and "we could not ask" are the same empty
        // list. The settings page states the first as fact, so the difference
        // has to survive to the caller.
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any()))
                .thenThrow(new RuntimeException("Graph 503"));

        var lookup = service.roomLookup();

        assertTrue(lookup.rooms().isEmpty());
        assertFalse(lookup.complete(), "a failed lookup is not an empty tenant");
    }

    @Test
    void roomLookup_reportsComplete_whenGraphAnswersEveryPage() {
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any())).thenReturn(
                new GraphCalendarClient.RoomCollectionResponse(List.of(
                        new GraphCalendarClient.RoomCollectionResponse.Room(
                                "p1", "Room One", "one@trustworks.dk", 4, "HQ"))));

        var lookup = service.roomLookup();

        assertEquals(1, lookup.rooms().size());
        assertTrue(lookup.complete());
    }

    @Test
    void roomLookup_reportsIncomplete_whenTheContinuationIsUnreadable() {
        // Graph says there is more but names the continuation in a form we do
        // not parse. Truncating silently is how a room list quietly stops
        // being the whole list.
        service.calendarEnabled = true;
        when(graph.listRoomsPaged(any(), any())).thenReturn(
                new GraphCalendarClient.RoomCollectionResponse(
                        List.of(new GraphCalendarClient.RoomCollectionResponse.Room(
                                "p1", "Room One", "one@trustworks.dk", 4, "HQ")),
                        "https://graph.microsoft.com/v1.0/places?$unknowncursor=ABC"));

        var lookup = service.roomLookup();

        assertEquals(1, lookup.rooms().size());
        assertFalse(lookup.complete(), "an unread continuation is a truncation, not a complete list");
    }

    @Test
    void parseSkipToken_readsTheContinuationOrStopsCleanly() {
        assertEquals("ABC123", RecruitmentCalendarService.parseSkipToken(
                "https://graph.microsoft.com/v1.0/places/microsoft.graph.room?$skiptoken=ABC123"));
        assertEquals("ABC123", RecruitmentCalendarService.parseSkipToken(
                "https://graph.microsoft.com/v1.0/places?$top=100&skiptoken=ABC123"));
        assertNull(RecruitmentCalendarService.parseSkipToken(null));
        assertNull(RecruitmentCalendarService.parseSkipToken(""));
        assertNull(RecruitmentCalendarService.parseSkipToken(
                "https://graph.microsoft.com/v1.0/places/microsoft.graph.room"));
    }

    /**
     * Graph echoes every requested address as free — except when asked AS
     * {@code invalidCaller}, which answers the 404 a mailbox-less user gets.
     * <p>
     * The 404 is a {@link GraphApiException}, not a bare RuntimeException:
     * the Graph REST client's registered mapper throws its own type, so that
     * IS the shape production sees (F18). Faking it as a plain
     * RuntimeException hides the status code and makes the address-specific
     * handling untestable.
     */
    private void echoSchedulesUnlessCallerIs(String invalidCaller) {
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation -> {
            String caller = invocation.getArgument(0);
            if (invalidCaller.equals(caller)) {
                throw new GraphApiException("Graph API error 404 Not Found: "
                        + "{\"error\":{\"code\":\"ErrorInvalidUser\"}}", 404);
            }
            return echo(invocation.getArgument(1));
        });
    }

    private static GraphCalendarClient.ScheduleCollectionResponse echo(
            GraphCalendarClient.ScheduleRequest request) {
        // Free everywhere, and long enough to cover any probe window this
        // test file asks for (rooms demand known coverage — see
        // AvailabilitySlotSuggester — so a too-short view would skew tests).
        return new GraphCalendarClient.ScheduleCollectionResponse(
                request.schedules().stream()
                        .map(mailbox -> new GraphCalendarClient.ScheduleCollectionResponse
                                .ScheduleInformation(mailbox, "0".repeat(15 * 24 * 4), null))
                        .toList());
    }
}
