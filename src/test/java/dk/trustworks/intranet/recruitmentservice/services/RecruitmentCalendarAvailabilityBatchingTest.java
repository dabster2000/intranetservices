package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
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
    private GraphApiClient graph;

    @BeforeEach
    void setUp() {
        graph = mock(GraphApiClient.class);
        service = new RecruitmentCalendarService();
        service.graphApiClient = graph;
        service.calendarEnabled = true;
    }

    @Test
    void mailboxLessCallerAtBatchHead_costsNothing_everyoneStillResolves() {
        // 45 interviewers = 3 batches. u20 heads batch 2 and has no mailbox
        // in the tenant: asking AS u20 404s. Before the fix that blanked
        // batches 2 AND 3 — 20 of 45 marked.
        List<String> mailboxes = mailboxes(45);
        echoSchedulesUnlessCallerIs("u20@trustworks.dk");

        Map<String, Boolean> result = service.interviewerAvailability(mailboxes, SLOT, 60);

        assertEquals(45, result.size(), "every probed mailbox resolves, invalid caller or not");
        assertTrue(result.values().stream().allMatch(Boolean::booleanValue));
        // Batch 1 proves u0 as a caller; batches 2 and 3 reuse it, so the
        // mailbox-less user is never asked as caller at all.
        verify(graph, times(3)).getSchedule(eq("u0@trustworks.dk"), any());
        verify(graph, never()).getSchedule(eq("u20@trustworks.dk"), any());
    }

    @Test
    void mailboxLessFirstCaller_fallsBackToTheNextMailbox() {
        // The invalid mailbox heads batch 1, so there is no proven caller to
        // fall back on — the next address in the batch must take over.
        List<String> mailboxes = mailboxes(45);
        echoSchedulesUnlessCallerIs("u0@trustworks.dk");

        Map<String, Boolean> result = service.interviewerAvailability(mailboxes, SLOT, 60);

        assertEquals(45, result.size());
        verify(graph, times(1)).getSchedule(eq("u0@trustworks.dk"), any());
        verify(graph, times(3)).getSchedule(eq("u1@trustworks.dk"), any());
    }

    @Test
    void oneUnresolvableBatch_doesNotTakeDownTheBatchesAfterIt() {
        // Batch 2 fails whoever asks (a 503 on that call, say). Batches 1
        // and 3 must still come back — the old catch-outside-the-loop lost
        // everything from the first failure onward.
        List<String> mailboxes = mailboxes(45);
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation -> {
            GraphApiClient.ScheduleRequest request = invocation.getArgument(1);
            if (request.schedules().contains("u20@trustworks.dk")) {
                throw new RuntimeException("Graph API error 503 Service Unavailable");
            }
            return echo(request);
        });

        Map<String, Boolean> result = service.interviewerAvailability(mailboxes, SLOT, 60);

        assertEquals(25, result.size(), "batch 1 (20) + batch 3 (5) survive");
        assertEquals(Boolean.TRUE, result.get("u0@trustworks.dk"));
        assertEquals(Boolean.TRUE, result.get("u44@trustworks.dk"), "the batch AFTER the failure");
        assertNull(result.get("u20@trustworks.dk"), "the failed batch is unknown, not busy");
        // Bounded retries: 3 caller attempts on the failing batch, one each
        // on the two that work.
        verify(graph, times(RecruitmentCalendarService.CALLER_ATTEMPTS + 2))
                .getSchedule(anyString(), any());
    }

    @Test
    void everyBatchBroken_yieldsEmpty_neverThrows() {
        when(graph.getSchedule(anyString(), any()))
                .thenThrow(new RuntimeException("Graph API error 403 Forbidden"));

        assertTrue(service.interviewerAvailability(mailboxes(45), SLOT, 60).isEmpty());
    }

    // ---- Raw schedules (day grid + suggestions, plan Phase 1) ------------------

    @Test
    void daySchedule_returnsRawDigitsAndWorkingHours_over48CellDay() {
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation -> {
            GraphApiClient.ScheduleRequest request = invocation.getArgument(1);
            return new GraphApiClient.ScheduleCollectionResponse(request.schedules().stream()
                    .map(mailbox -> new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation(
                            mailbox, "02".repeat(24),
                            new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation.WorkingHours(
                                    List.of("monday", "tuesday", "not-a-day"),
                                    "08:30:00.0000000", "16:00:00.0000000",
                                    new GraphApiClient.ScheduleCollectionResponse.ScheduleInformation
                                            .WorkingHours.TimeZoneName("Romance Standard Time"))))
                    .toList());
        });

        Map<String, AvailabilitySlotSuggester.MailboxWindowSchedule> schedules =
                service.daySchedule(List.of("u0@trustworks.dk"), LocalDate.of(2026, 8, 17));

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
        ArgumentCaptor<GraphApiClient.ScheduleRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.ScheduleRequest.class);
        verify(graph).getSchedule(anyString(), body.capture());
        assertEquals("2026-08-17T07:00", body.getValue().startTime().dateTime());
        assertEquals("2026-08-17T19:00", body.getValue().endTime().dateTime());
        assertEquals(15, body.getValue().availabilityViewInterval());
    }

    @Test
    void daySchedule_toggleOff_returnsEmpty_neverTouchesGraph() {
        service.calendarEnabled = false;
        assertTrue(service.daySchedule(List.of("u0@trustworks.dk"),
                LocalDate.of(2026, 8, 17)).isEmpty());
        verifyNoInteractions(graph);
    }

    @Test
    void suggestedSlots_fetchesOneMultiDayWindow_notOneCallPerDay() {
        // The ALB idle-timeout guard: a 10-business-day scan must be ONE
        // getSchedule window per 20-mailbox batch.
        when(graph.listRooms()).thenReturn(new GraphApiClient.RoomCollectionResponse(List.of(
                new GraphApiClient.RoomCollectionResponse.Room(
                        "place-1", "HQ meeting room 2", "room-hq2@trustworks.dk", 8, "HQ"))));
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation ->
                echo(invocation.getArgument(1)));

        // Monday 2026-08-17 → the 10th business day is Friday 2026-08-28.
        List<AvailabilitySlotSuggester.Slot> slots = service.suggestedSlots(
                List.of("u0@trustworks.dk", "u1@trustworks.dk"), 60,
                LocalDate.of(2026, 8, 17), 3, LocalDateTime.of(2026, 8, 17, 7, 0));

        ArgumentCaptor<GraphApiClient.ScheduleRequest> body =
                ArgumentCaptor.forClass(GraphApiClient.ScheduleRequest.class);
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
        when(graph.listRooms()).thenThrow(new RuntimeException("Graph API error 503"));
        when(graph.getSchedule(anyString(), any()))
                .thenThrow(new RuntimeException("Graph API error 503"));

        assertTrue(service.suggestedSlots(List.of("u0@trustworks.dk"), 60,
                LocalDate.of(2026, 8, 17), 2, LocalDateTime.of(2026, 8, 17, 7, 0)).isEmpty());
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

    private static List<String> mailboxes(int count) {
        List<String> mailboxes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            mailboxes.add("u" + i + "@trustworks.dk");
        }
        return mailboxes;
    }

    /**
     * Graph echoes every requested address as free — except when asked AS
     * {@code invalidCaller}, which answers the 404 a mailbox-less user gets.
     */
    private void echoSchedulesUnlessCallerIs(String invalidCaller) {
        when(graph.getSchedule(anyString(), any())).thenAnswer(invocation -> {
            String caller = invocation.getArgument(0);
            if (invalidCaller.equals(caller)) {
                throw new RuntimeException("Graph API error 404 Not Found: "
                        + "{\"error\":{\"code\":\"ErrorInvalidUser\"}}");
            }
            return echo(invocation.getArgument(1));
        });
    }

    private static GraphApiClient.ScheduleCollectionResponse echo(
            GraphApiClient.ScheduleRequest request) {
        // Free everywhere, and long enough to cover any probe window this
        // test file asks for (rooms demand known coverage — see
        // AvailabilitySlotSuggester — so a too-short view would skew tests).
        return new GraphApiClient.ScheduleCollectionResponse(
                request.schedules().stream()
                        .map(mailbox -> new GraphApiClient.ScheduleCollectionResponse
                                .ScheduleInformation(mailbox, "0".repeat(15 * 24 * 4), null))
                        .toList());
    }
}
