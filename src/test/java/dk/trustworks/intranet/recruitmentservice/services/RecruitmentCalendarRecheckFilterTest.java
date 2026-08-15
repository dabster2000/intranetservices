package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.sharepoint.client.GraphApiClient.CalendarViewResponse.CalendarViewEvent;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient.CalendarViewResponse.GraphDateTime;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Method B recheck's event filters (F1b/F1c, 2026-08-14): a slot
 * must not be rejected by a meeting that merely ENDS where the slot
 * starts (back-to-back is the normal calendar shape — every observed
 * production rejection fell on a round hour), and boundary semantics
 * are enforced HERE so whatever Graph's {@code calendarView} window
 * inclusion rules are, they cannot leak through. Unplaceable events
 * stay conservative: they count as conflicts.
 */
class RecruitmentCalendarRecheckFilterTest {

    private static final LocalDateTime SLOT_START = LocalDateTime.of(2026, 8, 25, 16, 0);
    private static final LocalDateTime SLOT_END = SLOT_START.plusMinutes(45);

    private static CalendarViewEvent event(LocalDateTime start, LocalDateTime end) {
        return new CalendarViewEvent("evt", "busy", false,
                new GraphDateTime(start.toString(), "Europe/Copenhagen"),
                new GraphDateTime(end.toString(), "Europe/Copenhagen"));
    }

    @Test
    void eventEndingExactlyAtSlotStart_isNotAConflict() {
        // The F1c shape: a 15:00–16:00 meeting before a 16:00 slot.
        assertFalse(RecruitmentCalendarService.strictlyOverlaps(
                event(SLOT_START.minusHours(1), SLOT_START), SLOT_START, SLOT_END));
    }

    @Test
    void eventStartingExactlyAtSlotEnd_isNotAConflict() {
        assertFalse(RecruitmentCalendarService.strictlyOverlaps(
                event(SLOT_END, SLOT_END.plusHours(1)), SLOT_START, SLOT_END));
    }

    @Test
    void genuineOverlap_isAConflict() {
        assertTrue(RecruitmentCalendarService.strictlyOverlaps(
                event(SLOT_START.minusMinutes(30), SLOT_START.plusMinutes(15)),
                SLOT_START, SLOT_END));
    }

    @Test
    void unparseableBounds_stayConservative() {
        assertTrue(RecruitmentCalendarService.strictlyOverlaps(
                new CalendarViewEvent("evt", "busy", false, null, null),
                SLOT_START, SLOT_END));
        assertTrue(RecruitmentCalendarService.strictlyOverlaps(
                new CalendarViewEvent("evt", "busy", false,
                        new GraphDateTime("not-a-datetime", null),
                        new GraphDateTime("also-not", null)),
                SLOT_START, SLOT_END));
    }

    @Test
    void graphFractionalSeconds_parse() {
        // Graph answers e.g. 2026-08-25T14:00:00.0000000 under the
        // Prefer-timezone header.
        assertTrue(RecruitmentCalendarService.strictlyOverlaps(
                new CalendarViewEvent("evt", "busy", false,
                        new GraphDateTime("2026-08-25T16:00:00.0000000", "Europe/Copenhagen"),
                        new GraphDateTime("2026-08-25T17:00:00.0000000", "Europe/Copenhagen")),
                SLOT_START, SLOT_END));
    }

    // ---- F18: Graph 404 recognition, whatever exception shape ------------

    @Test
    void graphNotFound_recognisesBothExceptionShapes() {
        // The REST client's registered mapper throws SharePointException
        // (a plain RuntimeException) — a WebApplicationException catch
        // never sees Graph 404s, which killed MISSING-hold detection in
        // production (F18, 2026-08-15).
        assertTrue(RecruitmentCalendarService.isGraphNotFound(
                new dk.trustworks.intranet.sharepoint.client
                        .GraphResponseExceptionMapper.SharePointException("gone", 404)));
        assertFalse(RecruitmentCalendarService.isGraphNotFound(
                new dk.trustworks.intranet.sharepoint.client
                        .GraphResponseExceptionMapper.SharePointException("denied", 403)));
        assertTrue(RecruitmentCalendarService.isGraphNotFound(
                new jakarta.ws.rs.WebApplicationException(
                        jakarta.ws.rs.core.Response.status(404).build())));
        assertFalse(RecruitmentCalendarService.isGraphNotFound(
                new jakarta.ws.rs.WebApplicationException(
                        jakarta.ws.rs.core.Response.status(500).build())));
        assertFalse(RecruitmentCalendarService.isGraphNotFound(
                new RuntimeException("connection reset")));
    }
}
