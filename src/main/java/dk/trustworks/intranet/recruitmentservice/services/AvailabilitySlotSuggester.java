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
 * boundaries inside the 07:00–19:00 probe window. Of the qualifying
 * starts, the {@value #MAX_PER_DAY} offered per day are chosen to SPREAD
 * across the bookable day and to favour the hours interviews actually
 * get booked in — see {@link #spreadAcrossDay}. Each slot carries the
 * highest-preference free room that seats the headcount, when one exists
 * ({@link MeetingRoomPicker} — the caller passes {@code rooms} already
 * filtered to the admin-enabled set and ordered by priority) — a missing
 * room never disqualifies the slot.
 * <p>
 * Unknown availability follows the house free/busy posture ("unknown
 * never counts as busy"): an interviewer mailbox absent from the
 * schedule map — no tenant mailbox, or their batch failed — does not
 * block suggestions, and digits beyond a truncated view count as free.
 * Rooms are held to the opposite rule: a room is only suggested when its
 * schedule is known and free, because a suggested room reads as a
 * booking promise. Room capacity is a floor, not a ranking key — see
 * {@link MeetingRoomPicker}.
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
    /** At most this many suggestions per day. */
    static final int MAX_PER_DAY = 5;
    /**
     * The spacing a suggestion "wants" from the ones already picked for
     * the same day: three step widths, wide enough that the five chips
     * cover morning, midday and afternoon, narrow enough that a day with
     * only one free window still fills all five.
     */
    static final int MIN_SEPARATION_MINUTES = 90;

    /**
     * Interviews actually booked per start hour, from production on
     * 2026-08-24: {@code SELECT HOUR(scheduled_at), COUNT(*) FROM
     * recruitment_interviews WHERE scheduled_at IS NOT NULL GROUP BY 1}
     * over all 39 scheduled rows (37 ROUND, 2 INFORMAL, cancelled ones
     * included). Nothing is booked before 09:00 or after 16:59, and 34
     * of 39 (87%) start at 10:00 or later — which is exactly why the
     * earliest-win selection this replaced was useless: on a free day it
     * offered 07:00–09:00, the one band nobody meets in.
     * <p>
     * Used as {@code count + 1} so an hour nobody has booked yet is only
     * unattractive, never ineligible: on a day whose sole free window is
     * 07:00–08:30 those starts are still suggested.
     */
    private static final int[] BOOKINGS_BY_HOUR = {
        //  0   1   2   3   4   5   6   7   8   9  10  11  12  13  14  15  16  17  18  19  20  21  22  23
            0,  0,  0,  0,  0,  0,  0,  0,  0,  5,  5,  2, 11,  1,  6,  6,  3,  0,  0,  0,  0,  0,  0,  0};

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

    /**
     * A bookable room candidate. {@code capacity} is nullable = Graph has no
     * capacity for the room, which never disqualifies it: capacity is only a
     * floor, so an unknown one simply cannot rule the room out
     * ({@link MeetingRoomPicker}).
     */
    public record RoomOption(String email, String displayName, Integer capacity) {
    }

    /**
     * One ranked suggestion; room fields are null when no room fits.
     * {@code roomReason} explains a non-obvious room choice ("preference 2 —
     * 1 preferred room was busy") and is null when the top-preference room
     * simply won — see {@link MeetingRoomPicker.Pick#reason()}.
     */
    public record Slot(LocalDateTime start, int durationMinutes,
                       String roomEmail, String roomDisplayName, String roomReason) {

        /** Convenience for callers that do not care about the explanation. */
        public Slot(LocalDateTime start, int durationMinutes,
                    String roomEmail, String roomDisplayName) {
            this(start, durationMinutes, roomEmail, roomDisplayName, null);
        }
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
            // Collect every qualifying start first — the whole day is
            // scanned now, because the five we offer are chosen from the
            // day as a whole rather than taken from the front of it. This
            // costs only digit arithmetic on schedules already in memory;
            // the room pick still runs for the selected starts only.
            List<LocalDateTime> qualifying = new ArrayList<>();
            LocalDateTime slotStart = day.atTime(DAY_WINDOW_START);
            LocalDateTime lastStart = day.atTime(DAY_WINDOW_END).minusMinutes(durationMinutes);
            while (!slotStart.isAfter(lastStart)) {
                if (!slotStart.isBefore(notBefore)
                        && allInterviewersAvailable(windowStart, schedules,
                                interviewerEmails, slotStart, durationMinutes)) {
                    qualifying.add(slotStart);
                }
                slotStart = slotStart.plusMinutes(STEP_MINUTES);
            }
            for (LocalDateTime start : spreadAcrossDay(qualifying)) {
                MeetingRoomPicker.Pick pick = MeetingRoomPicker.pick(windowStart,
                        schedules, rooms, start, durationMinutes, headcount);
                slots.add(new Slot(start, durationMinutes,
                        pick != null ? pick.room().email() : null,
                        pick != null ? pick.room().displayName() : null,
                        pick != null ? pick.reason() : null));
            }
            day = day.plusDays(1);
        }
        return slots;
    }

    /**
     * Pick at most {@value #MAX_PER_DAY} of one day's qualifying starts so
     * the chips read as "somewhere in the morning, around lunch, in the
     * afternoon" rather than "the next five half-hours".
     * <p>
     * Greedy, deterministic, integer-only: repeatedly take the start with
     * the highest {@code hourWeight × separation}, where
     * {@code hourWeight} is {@link #BOOKINGS_BY_HOUR} + 1 (how often that
     * hour is actually booked) and {@code separation} is the distance to
     * the nearest already-picked start, capped at
     * {@value #MIN_SEPARATION_MINUTES} minutes. The cap is what makes it
     * degrade instead of thin out: once every remaining start sits close
     * to a picked one they are compared on their hour weight alone, so a
     * day with a single narrow free window still yields as many slots as
     * the old earliest-win scan did — never fewer. Ties go to the earlier
     * start, so the output is stable input-for-input.
     *
     * @param qualifying the day's qualifying starts, ascending
     * @return the chosen starts, ascending (earliest-first is the order
     *         the whole response promises its callers)
     */
    static List<LocalDateTime> spreadAcrossDay(List<LocalDateTime> qualifying) {
        if (qualifying.size() <= MAX_PER_DAY) {
            return qualifying;
        }
        List<LocalDateTime> remaining = new ArrayList<>(qualifying);
        List<LocalDateTime> picked = new ArrayList<>(MAX_PER_DAY);
        while (picked.size() < MAX_PER_DAY && !remaining.isEmpty()) {
            LocalDateTime best = null;
            long bestScore = Long.MIN_VALUE;
            for (LocalDateTime candidate : remaining) {
                long score = (long) hourWeight(candidate) * separation(candidate, picked);
                if (score > bestScore) { // strict: the earlier start keeps a tie
                    bestScore = score;
                    best = candidate;
                }
            }
            picked.add(best);
            remaining.remove(best);
        }
        picked.sort(Comparator.naturalOrder());
        return picked;
    }

    /** How often the hour this slot starts in is booked, plus one. */
    private static int hourWeight(LocalDateTime slotStart) {
        return BOOKINGS_BY_HOUR[slotStart.getHour()] + 1;
    }

    /**
     * Minutes to the nearest already-picked start, capped at
     * {@value #MIN_SEPARATION_MINUTES}; the full cap when nothing is
     * picked yet, so the first pick is decided by hour weight alone.
     */
    private static long separation(LocalDateTime candidate, List<LocalDateTime> picked) {
        long nearest = MIN_SEPARATION_MINUTES;
        for (LocalDateTime chosen : picked) {
            nearest = Math.min(nearest,
                    Math.abs(Duration.between(chosen, candidate).toMinutes()));
        }
        return nearest;
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
