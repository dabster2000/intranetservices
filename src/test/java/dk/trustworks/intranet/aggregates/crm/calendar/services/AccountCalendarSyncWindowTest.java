package dk.trustworks.intranet.aggregates.crm.calendar.services;

import dk.trustworks.intranet.graph.GraphCalendarClient;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clock side of the calendar sync: which window a mailbox is read over, how its bounds
 * reach Graph, and which events are "not yet".
 *
 * <p>This class exists because the sync read ninety days AHEAD for one release and stored
 * what it found as history. On 2026-09-14, 209 of the 415 meeting rows in production were
 * for meetings that had not happened, "last contact" on several accounts was a date in
 * December, and the Ældre Sagen timeline was a daily standup repeated into the future. So
 * three things are pinned here: the window never reaches past the run's clock, the bounds
 * go to Graph in the zone Graph reads them in, and an event is kept only once it has ended
 * — on its own clock, not the JVM's.
 *
 * <p>Fast tier — no Quarkus boot, no Graph, no database. Everything under test is a pure
 * static on {@link AccountCalendarSyncService} or {@link CalendarTime}.
 */
class AccountCalendarSyncWindowTest {

    /** 02:20 in Copenhagen on a summer night — the nightly job's own slot — is 00:20 UTC. */
    private static final Instant SUMMER_RUN = Instant.parse("2026-07-01T00:20:00Z");

    // ------------------------------------------------------------------------
    // The window
    // ------------------------------------------------------------------------

    @Test
    void theWindowClosesAtTheRunsClockAndNeverReachesAhead() {
        var incremental = AccountCalendarSyncService.ReadWindow.of(SUMMER_RUN, false);
        var full = AccountCalendarSyncService.ReadWindow.of(SUMMER_RUN, true);

        assertEquals(SUMMER_RUN, incremental.to());
        assertEquals(SUMMER_RUN, full.to());
        assertEquals(SUMMER_RUN.minus(Duration.ofDays(AccountCalendarSyncService.INCREMENTAL_BACK_DAYS)),
                incremental.from());
        assertEquals(SUMMER_RUN.minus(Duration.ofDays(AccountCalendarSyncService.FULL_READ_BACK_DAYS)),
                full.from());
        assertFalse(incremental.full());
        assertTrue(full.full());
    }

    /**
     * Graph reads a bound with no offset as UTC and the Prefer header does not change that
     * — the documentation says so. The first version formatted the JVM's local wall clock,
     * which was only right because the container runs on UTC.
     */
    @Test
    void theBoundsGoToGraphAsUtcWallClockWithNoOffset() {
        var window = AccountCalendarSyncService.ReadWindow.of(SUMMER_RUN, false);

        assertEquals("2026-07-01T00:20:00", window.graphTo());
        assertEquals("2026-06-17T00:20:00", window.graphFrom());
    }

    /** The same bounds, as the reconcile compares them against {@code occurred_at}: Copenhagen. */
    @Test
    void theBoundsReachTheReconcileOnTheCalendarsOwnWallClock() {
        var window = AccountCalendarSyncService.ReadWindow.of(SUMMER_RUN, false);

        assertEquals(LocalDateTime.of(2026, 7, 1, 2, 20), window.toWallClock());
        assertEquals(LocalDateTime.of(2026, 6, 17, 2, 20), window.fromWallClock());
    }

    // ------------------------------------------------------------------------
    // Which night is a full night
    // ------------------------------------------------------------------------

    @Test
    void aFullReadIsDueWhenForcedOnAFirstRunForABackfillOrOnSunday() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        LocalDate sunday = monday.with(DayOfWeek.SUNDAY);
        assertEquals(DayOfWeek.MONDAY, monday.getDayOfWeek());

        assertFalse(AccountCalendarSyncService.fullReadDue(false, false, false, monday), "an ordinary night");
        assertTrue(AccountCalendarSyncService.fullReadDue(true, false, false, monday), "forced by the trigger");
        assertTrue(AccountCalendarSyncService.fullReadDue(false, true, false, monday), "a mailbox with no rows");
        assertTrue(AccountCalendarSyncService.fullReadDue(false, false, true, monday), "rows without an identity");
        assertTrue(AccountCalendarSyncService.fullReadDue(false, false, false, sunday), "the weekly night");
        assertFalse(AccountCalendarSyncService.fullReadDue(false, false, false, null));
    }

    // ------------------------------------------------------------------------
    // Has it ended
    // ------------------------------------------------------------------------

    @Test
    void anEventThatEndedBeforeTheRunHasEnded() {
        assertTrue(AccountCalendarSyncService.hasEnded(
                event("2026-06-30T15:00:00", "2026-06-30T16:00:00", "Europe/Copenhagen"), SUMMER_RUN));
    }

    /** Overlapping the window is not the same as being over: the next run sees it whole. */
    @Test
    void anEventStillRunningAtTheRunsClockHasNot() {
        Instant runStart = Instant.parse("2026-07-01T13:30:00Z");
        var event = event("2026-07-01T15:00:00", "2026-07-01T16:00:00", "Europe/Copenhagen"); // 13:00–14:00 UTC

        assertFalse(AccountCalendarSyncService.hasEnded(event, runStart));
        assertTrue(AccountCalendarSyncService.hasEnded(event, Instant.parse("2026-07-01T14:00:00Z")),
                "the moment it ends it is over — the comparison is not strict");
    }

    @Test
    void anEventThatHasNotStartedHasNot() {
        assertFalse(AccountCalendarSyncService.hasEnded(
                event("2026-12-11T15:00:00", "2026-12-11T15:15:00", "Europe/Copenhagen"), SUMMER_RUN));
    }

    @Test
    void anEventWithNoEndIsJudgedOnItsStart() {
        var started = new GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent(
                "x", null, "singleInstance", null, false,
                time("2026-06-30T15:00:00", "Europe/Copenhagen"), null, null, List.of());
        var notYet = new GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent(
                "y", null, "singleInstance", null, false,
                time("2026-07-02T15:00:00", "Europe/Copenhagen"), null, null, List.of());
        var neither = new GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent(
                "z", null, "singleInstance", null, false, null, null, null, List.of());

        assertTrue(AccountCalendarSyncService.hasEnded(started, SUMMER_RUN));
        assertFalse(AccountCalendarSyncService.hasEnded(notYet, SUMMER_RUN));
        assertFalse(AccountCalendarSyncService.hasEnded(neither, SUMMER_RUN), "cannot be shown to have ended");
        assertFalse(AccountCalendarSyncService.hasEnded(null, SUMMER_RUN));
        assertFalse(AccountCalendarSyncService.hasEnded(started, null));
    }

    /**
     * The event's own zone decides, so the JVM's zone cannot. A meeting that ended at 15:30
     * Copenhagen has ended at 13:30 UTC; read as UTC wall clock it would still be running
     * for two hours, which is exactly the window in which a meeting would sit on the
     * timeline while people were still in it.
     */
    @Test
    void theEventsOwnZoneDecidesWhetherItHasEnded() {
        var event = event("2026-07-01T15:00:00", "2026-07-01T15:30:00", "Europe/Copenhagen");

        assertFalse(AccountCalendarSyncService.hasEnded(event, Instant.parse("2026-07-01T13:29:00Z")));
        assertTrue(AccountCalendarSyncService.hasEnded(event, Instant.parse("2026-07-01T13:31:00Z")));
        assertEquals(Instant.parse("2026-07-01T13:30:00Z"), AccountCalendarSyncService.instantOf(event.end()));
    }

    @Test
    void aZoneGraphNamesInAFormJavaDoesNotReadFallsBackToCopenhagen() {
        assertEquals(CalendarTime.ZONE, CalendarTime.zoneOf(null));
        assertEquals(CalendarTime.ZONE, CalendarTime.zoneOf("  "));
        assertEquals(CalendarTime.ZONE, CalendarTime.zoneOf("Romance Standard Time"));
        assertEquals(ZoneId.of("Europe/Copenhagen"), CalendarTime.zoneOf("Europe/Copenhagen"));
        assertEquals(ZoneOffset.UTC.normalized(), CalendarTime.zoneOf("UTC").normalized());
    }

    /** The one clock every {@code occurred_at} comparison is made on. */
    @Test
    void theCalendarClockIsCopenhagenWallClock() {
        assertEquals(LocalDateTime.of(2026, 7, 1, 2, 20), CalendarTime.wallClock(SUMMER_RUN));
        assertEquals(LocalDateTime.of(2026, 1, 15, 3, 20), CalendarTime.wallClock(Instant.parse("2026-01-15T02:20:00Z")),
                "and in winter the offset is one hour, not two");
    }

    // ------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------

    private static GraphCalendarClient.CalendarViewResponse.GraphDateTime time(String value, String zone) {
        return new GraphCalendarClient.CalendarViewResponse.GraphDateTime(value, zone);
    }

    private static GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent event(String start, String end, String zone) {
        return new GraphCalendarClient.AttendeeViewResponse.AttendeeViewEvent(
                "evt", "ical-evt", "singleInstance", null, false, time(start, zone), time(end, zone), null, List.of());
    }
}
