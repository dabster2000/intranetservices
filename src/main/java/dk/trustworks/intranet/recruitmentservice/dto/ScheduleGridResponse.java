package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Envelope of {@code GET /recruitment/interviews/schedule-grid} — one
 * day's availability grid for the scheduling dialog's assistant pane:
 * per chosen interviewer (and the chosen room) a digit string of
 * {@code intervalMinutes}-wide cells starting at {@code dayStart}, plus
 * working hours for out-of-hours shading. Strictly free/busy digits —
 * no event subjects or details ever ride here, the same privacy stance
 * as the interviewer-availability booleans. Empty {@code entries} when
 * the Graph calendar toggle is off (the UI hides the pane).
 */
public record ScheduleGridResponse(
        LocalDate date,
        int intervalMinutes,
        String dayStart,
        List<GridEntry> entries,
        boolean availabilityComplete
) {

    /**
     * One row of the grid. {@code id} is the interviewer's user UUID for
     * {@code kind=USER} rows and the room mailbox for {@code kind=ROOM}
     * rows. {@code availabilityView} is one digit per cell ("0" = free,
     * "1" tentative, "2" busy, "3" out-of-office, "4" working elsewhere);
     * {@code null} = unknown (no mailbox or the lookup failed) — render
     * unmarked, never busy. {@code workingHours} is {@code null} when
     * Graph did not report any.
     */
    public record GridEntry(
            String id,
            String kind,
            String availabilityView,
            WorkingHours workingHours
    ) {
    }

    /**
     * Working hours as reported by Graph, normalized: lowercase weekday
     * names, times as {@code HH:mm} wall-clock in {@code timeZone} (a
     * Windows zone name — Trustworks mailboxes are all Copenhagen).
     */
    public record WorkingHours(
            List<String> daysOfWeek,
            String startTime,
            String endTime,
            String timeZone
    ) {
    }
}
