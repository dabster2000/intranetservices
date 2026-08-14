package dk.trustworks.intranet.recruitmentservice.services;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ranks interview slot candidates from Graph {@code getSchedule} data
 * (interview scheduling plan Phase 1). Server-side on purpose:
 * {@code findMeetingTimes} is delegated-permission only and our Graph
 * integration is app-only client credentials, so the intersection is
 * computed here from raw {@code availabilityView} digit strings.
 * <p>
 * A slot qualifies when every interviewer is free for its whole span,
 * it lies inside the intersection of the interviewers' working hours,
 * and it falls on a weekday. Candidate starts step on 30-minute
 * boundaries inside the 07:00–19:00 probe window. Each slot carries the
 * smallest free room that seats the headcount, when one exists —
 * a missing room never disqualifies the slot.
 * <p>
 * Unknown availability follows the house free/busy posture ("unknown
 * never counts as busy"): an interviewer mailbox absent from the
 * schedule map — no tenant mailbox, or their batch failed — does not
 * block suggestions, and digits beyond a truncated view count as free.
 * Rooms are held to the opposite rule: a room is only suggested when its
 * schedule is known and free, because a suggested room reads as a
 * booking promise.
 * <p>
 * Working hours are interpreted as wall-clock in the probe window's own
 * zone (Europe/Copenhagen). Graph reports them in each mailbox's own
 * time zone as a Windows zone name; translating those is deliberately
 * out of scope — every Trustworks mailbox lives in the Copenhagen zone,
 * and a rare mismatch only skews which slots get *suggested*, never what
 * the availability grid shows.
 * <p>
 * Pure and CDI-free so it is covered by plain unit tests in the DB-free
 * tier that gates deploys.
 */
public final class AvailabilitySlotSuggester {

    /** First cell of every probed day — digit index 0 of a day view. */
    public static final LocalTime DAY_WINDOW_START = LocalTime.of(7, 0);
    /** Exclusive end of the probe window: every slot must END by here. */
    public static final LocalTime DAY_WINDOW_END = LocalTime.of(19, 0);
    /** One availability digit covers this many minutes. */
    public static final int INTERVAL_MINUTES = 15;
    /** Candidate starts step on this boundary. */
    static final int STEP_MINUTES = 30;
    /** How many business days to scan from the requested day. */
    static final int BUSINESS_DAYS = 10;
    /** At most this many suggestions per day (earliest win). */
    static final int MAX_PER_DAY = 5;

    private AvailabilitySlotSuggester() {
    }

    /**
     * One mailbox's schedule for the probe window: the raw
     * {@code availabilityView} digits (index 0 = the window start) and the
     * mailbox's working hours. Either part may be null (= unknown).
     */
    public record MailboxWindowSchedule(String availabilityView, WorkingHours workingHours) {
    }

    /**
     * Working hours normalized from Graph: which weekdays, and the
     * wall-clock span. {@code timeZoneName} is carried for display only —
     * see the class note on zone interpretation.
     */
    public record WorkingHours(Set<DayOfWeek> days, LocalTime start, LocalTime end,
                               String timeZoneName) {
    }

    /** A bookable room candidate (capacity nullable = unknown, never fits). */
    public record RoomOption(String email, String displayName, Integer capacity) {
    }

    /** One ranked suggestion; room fields are null when no room fits. */
    public record Slot(LocalDateTime start, int durationMinutes,
                       String roomEmail, String roomDisplayName) {
    }

    /**
     * Compute ranked slot suggestions.
     *
     * @param fromDay           first day to consider; also anchors digit
     *                          index 0 of every availability view at
     *                          {@code fromDay} 07:00
     * @param schedules         per-mailbox schedules for interviewers AND
     *                          rooms, keyed by lowercase mailbox address,
     *                          all sharing that same digit anchor
     * @param interviewerEmails the interviewer mailboxes that must be free
     *                          (lowercase; absent from {@code schedules} =
     *                          unknown = does not block)
     * @param rooms             the bookable rooms to offer alongside slots
     * @param durationMinutes   slot length
     * @param headcount         people to seat (interviewers + candidate)
     * @param notBefore         suggestions strictly before this moment are
     *                          skipped (pass "now" — kept as a parameter so
     *                          the class stays deterministic under test)
     */
    public static List<Slot> suggest(LocalDate fromDay,
                                     Map<String, MailboxWindowSchedule> schedules,
                                     List<String> interviewerEmails,
                                     List<RoomOption> rooms,
                                     int durationMinutes,
                                     int headcount,
                                     LocalDateTime notBefore) {
        LocalDateTime windowStart = fromDay.atTime(DAY_WINDOW_START);
        List<Slot> slots = new ArrayList<>();
        LocalDate day = fromDay;
        int businessDaysSeen = 0;
        while (businessDaysSeen < BUSINESS_DAYS) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY
                    || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                day = day.plusDays(1);
                continue;
            }
            businessDaysSeen++;
            int found = 0;
            LocalDateTime slotStart = day.atTime(DAY_WINDOW_START);
            LocalDateTime lastStart = day.atTime(DAY_WINDOW_END).minusMinutes(durationMinutes);
            while (found < MAX_PER_DAY && !slotStart.isAfter(lastStart)) {
                if (!slotStart.isBefore(notBefore)
                        && allInterviewersAvailable(windowStart, schedules,
                                interviewerEmails, slotStart, durationMinutes)) {
                    RoomOption room = smallestFittingFreeRoom(windowStart, schedules,
                            rooms, slotStart, durationMinutes, headcount);
                    slots.add(new Slot(slotStart, durationMinutes,
                            room != null ? room.email() : null,
                            room != null ? room.displayName() : null));
                    found++;
                }
                slotStart = slotStart.plusMinutes(STEP_MINUTES);
            }
            day = day.plusDays(1);
        }
        return slots;
    }

    private static boolean allInterviewersAvailable(LocalDateTime windowStart,
                                                    Map<String, MailboxWindowSchedule> schedules,
                                                    List<String> interviewerEmails,
                                                    LocalDateTime slotStart,
                                                    int durationMinutes) {
        for (String email : interviewerEmails) {
            MailboxWindowSchedule schedule = schedules.get(email);
            if (schedule == null) {
                continue; // unknown never counts as busy
            }
            if (!withinWorkingHours(schedule.workingHours(), slotStart, durationMinutes)) {
                return false;
            }
            if (!viewFree(windowStart, schedule.availabilityView(), slotStart, durationMinutes,
                    true)) {
                return false;
            }
        }
        return true;
    }

    private static RoomOption smallestFittingFreeRoom(LocalDateTime windowStart,
                                                      Map<String, MailboxWindowSchedule> schedules,
                                                      List<RoomOption> rooms,
                                                      LocalDateTime slotStart,
                                                      int durationMinutes,
                                                      int headcount) {
        return rooms.stream()
                .filter(room -> room.capacity() != null && room.capacity() >= headcount)
                .filter(room -> {
                    MailboxWindowSchedule schedule = schedules.get(room.email());
                    // Rooms need a KNOWN free schedule — a suggested room
                    // reads as a promise, unknown must not make one.
                    return schedule != null && schedule.availabilityView() != null
                            && viewFree(windowStart, schedule.availabilityView(),
                                    slotStart, durationMinutes, false);
                })
                .min(Comparator.comparingInt(RoomOption::capacity))
                .orElse(null);
    }

    /**
     * True when every digit covering {@code [slotStart, slotStart+duration)}
     * is "0". Digits outside the view's range follow the caller's posture:
     * {@code lenient} treats them as free (interviewers — unknown never
     * counts as busy), otherwise they disqualify (rooms).
     * <p>
     * Package-private: {@code MultiSlotPlanner} (Method B) scans with the
     * same digit semantics against the same window anchor.
     */
    static boolean viewFree(LocalDateTime windowStart, String availabilityView,
                                    LocalDateTime slotStart, int durationMinutes,
                                    boolean lenient) {
        if (availabilityView == null || availabilityView.isEmpty()) {
            return lenient;
        }
        long offsetMinutes = Duration.between(windowStart, slotStart).toMinutes();
        if (offsetMinutes < 0) {
            return lenient;
        }
        int firstCell = (int) (offsetMinutes / INTERVAL_MINUTES);
        int cells = (durationMinutes + INTERVAL_MINUTES - 1) / INTERVAL_MINUTES;
        for (int cell = firstCell; cell < firstCell + cells; cell++) {
            if (cell >= availabilityView.length()) {
                return lenient;
            }
            if (availabilityView.charAt(cell) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the slot lies inside the mailbox's working hours: the day
     * is a working day and {@code [start, start+duration]} fits the
     * wall-clock span. Null or half-known working hours never constrain.
     * <p>
     * Package-private: shared with {@code MultiSlotPlanner} (Method B).
     */
    static boolean withinWorkingHours(WorkingHours workingHours,
                                              LocalDateTime slotStart, int durationMinutes) {
        if (workingHours == null) {
            return true;
        }
        if (workingHours.days() != null && !workingHours.days().isEmpty()
                && !workingHours.days().contains(slotStart.getDayOfWeek())) {
            return false;
        }
        LocalTime slotEnd = slotStart.toLocalTime().plusMinutes(durationMinutes);
        boolean crossesMidnight = slotEnd.isBefore(slotStart.toLocalTime());
        if (crossesMidnight) {
            return false;
        }
        if (workingHours.start() != null && slotStart.toLocalTime().isBefore(workingHours.start())) {
            return false;
        }
        return workingHours.end() == null || !slotEnd.isAfter(workingHours.end());
    }
}
