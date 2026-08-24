package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.MailboxWindowSchedule;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.RoomOption;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.Slot;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.WorkingHours;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slot suggestion rules (interview scheduling plan Phase 1): interviewer
 * intersection, working-hours clipping, weekend skip, room-capacity fit,
 * the intra-day spread and the empty cases. Pure unit test — the
 * suggester is CDI-free by design so this runs in the DB-free tier that
 * gates deploys.
 */
class AvailabilitySlotSuggesterTest {

    /** A Monday. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);
    private static final LocalDateTime WINDOW_OPEN = MONDAY.atTime(7, 0);

    // ---- Intersection ----------------------------------------------------------

    @Test
    void suggestions_requireEveryInterviewerFree() {
        // A busy 10–11, B busy 13–14, nothing before 09:30. Neither
        // meeting may be overlapped, and the five offered starts spread
        // over what is left of the day.
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule(busyBetween("10:00", "11:00")),
                "b@trustworks.dk", schedule(busyBetween("13:00", "14:00")));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk", "b@trustworks.dk"),
                List.of(), 60, 3, MONDAY.atTime(9, 30));

        List<LocalDateTime> mondaySlots = slots.stream()
                .map(Slot::start)
                .filter(start -> start.toLocalDate().equals(MONDAY))
                .toList();
        assertEquals(List.of(
                MONDAY.atTime(12, 0), MONDAY.atTime(14, 0), MONDAY.atTime(14, 30),
                MONDAY.atTime(15, 30), MONDAY.atTime(16, 30)), mondaySlots);
        assertTrue(mondaySlots.stream().noneMatch(start ->
                        overlaps(start, 60, "10:00", "11:00")
                                || overlaps(start, 60, "13:00", "14:00")),
                "no suggestion may overlap either interviewer's meeting");
    }

    @Test
    void unknownInterviewerSchedule_neverBlocks() {
        // The house free/busy posture: unknown never counts as busy. One
        // interviewer has no schedule entry at all (no tenant mailbox or a
        // failed batch) — the other's calendar alone decides.
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "known@trustworks.dk", schedule(busyBetween("07:00", "09:00")));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("known@trustworks.dk", "ghost@trustworks.dk"),
                List.of(), 60, 3, WINDOW_OPEN);

        assertEquals(MONDAY.atTime(9, 0), slots.get(0).start());
    }

    @Test
    void allDaysFullyBusy_yieldsNoSuggestions() {
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule("2".repeat(FULL_SCAN_CELLS)));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"),
                List.of(), 60, 2, WINDOW_OPEN);

        assertTrue(slots.isEmpty(), "no common slot must yield an empty list, not filler");
    }

    // ---- Working hours ---------------------------------------------------------

    @Test
    void workingHours_clipStartAndEnd() {
        // 09:00–15:00 working hours: slots may start at 09:00 and the LAST
        // fitting 60-minute start is 14:00.
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", new MailboxWindowSchedule("0".repeat(FULL_SCAN_CELLS),
                        new WorkingHours(weekdays(), LocalTime.of(9, 0), LocalTime.of(15, 0),
                                "Romance Standard Time")));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"),
                List.of(), 60, 2, WINDOW_OPEN);

        assertEquals(MONDAY.atTime(9, 0), slots.get(0).start(),
                "07:00 and 08:30 lie outside working hours");
        assertTrue(slots.stream().allMatch(slot ->
                        !slot.start().toLocalTime().isBefore(LocalTime.of(9, 0))
                                && !slot.start().toLocalTime().plusMinutes(60).isAfter(LocalTime.of(15, 0))),
                "every suggestion fits inside 09:00–15:00");
    }

    @Test
    void workingDays_excludeNonWorkingWeekdays() {
        // Works Mondays only → across the 10-business-day scan the two
        // Mondays are the only days suggested.
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", new MailboxWindowSchedule("0".repeat(FULL_SCAN_CELLS),
                        new WorkingHours(Set.of(DayOfWeek.MONDAY), null, null, null)));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"),
                List.of(), 60, 2, WINDOW_OPEN);

        assertEquals(10, slots.size(), "5 per Monday × the 2 Mondays in scan range");
        assertTrue(slots.stream().allMatch(slot ->
                slot.start().getDayOfWeek() == DayOfWeek.MONDAY));
    }

    // ---- Weekends & ordering -----------------------------------------------------

    @Test
    void weekendStart_skipsToMonday_andNeverSuggestsWeekends() {
        LocalDate saturday = LocalDate.of(2026, 8, 15);
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                saturday, schedules, List.of("a@trustworks.dk"),
                List.of(), 60, 2, saturday.atTime(7, 0));

        assertEquals(MONDAY.atTime(9, 0), slots.get(0).start(),
                "Saturday start → the first suggestion is Monday's earliest offered start");
        assertTrue(slots.stream().noneMatch(slot ->
                slot.start().getDayOfWeek() == DayOfWeek.SATURDAY
                        || slot.start().getDayOfWeek() == DayOfWeek.SUNDAY));
    }

    // ---- Intra-day spread --------------------------------------------------------

    @Test
    void freeDay_spreadsAcrossTheDay_insteadOfFiveEarlyMorningStarts() {
        // The defect this replaced: on a free day the five suggestions
        // were 07:00–09:00, and production books nothing before 09:00
        // (34 of 39 interviews start at 10:00 or later).
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"),
                List.of(), 60, 2, WINDOW_OPEN);

        assertEquals(50, slots.size(), "5 per day × 10 business days");
        assertEquals(List.of(
                        MONDAY.atTime(9, 0), MONDAY.atTime(10, 30), MONDAY.atTime(12, 0),
                        MONDAY.atTime(14, 0), MONDAY.atTime(15, 30)),
                startsOn(slots, MONDAY),
                "morning, late morning, midday and two afternoon options");
        assertEquals(MONDAY.plusDays(1).atTime(9, 0), slots.get(5).start(),
                "then the next business day, again earliest-first");
        assertTrue(slots.stream().allMatch(slot ->
                slot.start().getMinute() % 30 == 0), "starts step on 30-minute boundaries");
        assertTrue(slots.stream().noneMatch(slot ->
                        slot.start().toLocalTime().isBefore(LocalTime.of(9, 0))),
                "a wholly free day must not be spent on hours nobody books");
    }

    @Test
    void spreadIsDeterministic_andAlwaysEarliestFirstWithinTheDay() {
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)));

        List<Slot> first = AvailabilitySlotSuggester.suggest(MONDAY, schedules,
                List.of("a@trustworks.dk"), List.of(), 60, 2, WINDOW_OPEN);
        List<Slot> second = AvailabilitySlotSuggester.suggest(MONDAY, schedules,
                List.of("a@trustworks.dk"), List.of(), 60, 2, WINDOW_OPEN);

        assertEquals(first.stream().map(Slot::start).toList(),
                second.stream().map(Slot::start).toList(),
                "same input, same output — the UI pages over this order");
        for (int i = 1; i < first.size(); i++) {
            assertTrue(!first.get(i).start().isBefore(first.get(i - 1).start()),
                    "suggestions stay in ascending start order");
        }
    }

    @Test
    void narrowFreeWindow_stillFillsTheDayCap_neverFewerThanBefore() {
        // Only 09:00–12:00 is free, so the five slots cannot spread: the
        // spread must degrade to "as many as fit", never thin out. The
        // earliest-win scan would have offered five here too.
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", new MailboxWindowSchedule("0".repeat(FULL_SCAN_CELLS),
                        new WorkingHours(weekdays(), LocalTime.of(9, 0), LocalTime.of(12, 0),
                                "Romance Standard Time")));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"),
                List.of(), 60, 2, WINDOW_OPEN);

        assertEquals(List.of(
                        MONDAY.atTime(9, 0), MONDAY.atTime(9, 30), MONDAY.atTime(10, 0),
                        MONDAY.atTime(10, 30), MONDAY.atTime(11, 0)),
                startsOn(slots, MONDAY),
                "5 qualifying starts, 5 offered — the whole window");
    }

    @Test
    void singleQualifyingStart_isStillOffered() {
        // The degenerate day: one free hour. It must survive, however
        // unattractive its hour is.
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", new MailboxWindowSchedule("0".repeat(FULL_SCAN_CELLS),
                        new WorkingHours(Set.of(DayOfWeek.MONDAY), LocalTime.of(7, 0),
                                LocalTime.of(8, 0), null)));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"),
                List.of(), 60, 2, WINDOW_OPEN);

        assertEquals(List.of(MONDAY.atTime(7, 0)), startsOn(slots, MONDAY),
                "an hour production never books is unattractive, not ineligible");
    }

    @Test
    void notBefore_skipsSlotsInThePast() {
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"),
                List.of(), 60, 2, MONDAY.atTime(12, 0));

        assertEquals(MONDAY.atTime(12, 0), slots.get(0).start(),
                "a slot starting exactly now is still offered");
        assertTrue(startsOn(slots, MONDAY).stream().noneMatch(start ->
                        start.isBefore(MONDAY.atTime(12, 0))),
                "the elapsed part of the day is trimmed before the spread runs");
        assertEquals(5, startsOn(slots, MONDAY).size(),
                "half a day still fills the day cap");
    }

    // ---- Rooms -----------------------------------------------------------------

    @Test
    void highestPreferenceFreeRoomThatSeatsEveryone_winsTheSlot() {
        // The offered list IS the admin's preference order (V513). Capacity
        // is only a floor: "Small" cannot seat 4 and drops out, and the
        // NEXT preference wins — not the smallest survivor, which is what
        // used to make one room win every interview forever.
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)),
                "small@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)),
                "medium@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)),
                "large@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)));
        List<RoomOption> rooms = List.of(
                new RoomOption("small@trustworks.dk", "Small", 2),
                new RoomOption("large@trustworks.dk", "Large", 20),
                new RoomOption("medium@trustworks.dk", "Medium", 6));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"), rooms, 60, 4, WINDOW_OPEN);

        assertEquals("large@trustworks.dk", slots.get(0).roomEmail(),
                "capacity 2 cannot seat 4; the next PREFERENCE wins, not the smallest fit");
        assertEquals("Large", slots.get(0).roomDisplayName());
    }

    @Test
    void preferenceOrderDecides_evenWhenAnotherRoomFitsBetter() {
        // The regression this feature exists for: with the old rule the
        // smallest fitting room won every time and no one could say
        // otherwise. Preferring the big room must now be expressible.
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)),
                "big@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)),
                "snug@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)));
        List<RoomOption> rooms = List.of(
                new RoomOption("big@trustworks.dk", "Havnepakhuset Stor", 20),
                new RoomOption("snug@trustworks.dk", "HP3", 4));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"), rooms, 60, 2, WINDOW_OPEN);

        assertEquals("big@trustworks.dk", slots.get(0).roomEmail(),
                "a 2-person interview lands in the preferred big room, not the snuggest fit");
        assertNull(slots.get(0).roomReason(),
                "the top preference winning needs no explanation");
    }

    @Test
    void busyRoom_fallsBackToTheNextPreference_andSaysSo() {
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)),
                "medium@trustworks.dk", schedule(busyBetween("09:00", "10:00")),
                "large@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)));
        List<RoomOption> rooms = List.of(
                new RoomOption("medium@trustworks.dk", "Medium", 6),
                new RoomOption("large@trustworks.dk", "Large", 20));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"), rooms, 60, 4, WINDOW_OPEN);

        assertEquals(MONDAY.atTime(9, 0), slots.get(0).start());
        assertEquals("large@trustworks.dk", slots.get(0).roomEmail(),
                "09:00: the first preference is busy, the second takes the slot");
        assertEquals("preference 2 — 1 preferred room was unavailable",
                slots.get(0).roomReason(),
                "the fallback is explained rather than looking arbitrary");
        assertEquals(MONDAY.atTime(10, 30), slots.get(1).start());
        assertEquals("medium@trustworks.dk", slots.get(1).roomEmail(),
                "10:30: the first preference is free again and takes it back");
        assertNull(slots.get(1).roomReason());
    }

    @Test
    void roomWithoutCapacity_isEligible_notSilentlyInvisible() {
        // Graph reports no capacity for a room whose Exchange resource never
        // had one set. The old rule dropped those outright, so rooms nobody
        // had finished configuring could never be suggested — with nothing
        // anywhere saying why.
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)),
                "nocap@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)));
        List<RoomOption> rooms = List.of(
                new RoomOption("nocap@trustworks.dk", "NoCapacity", null));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"), rooms, 60, 8, WINDOW_OPEN);

        assertEquals("nocap@trustworks.dk", slots.get(0).roomEmail(),
                "unknown capacity cannot prove the room too small, so it never excludes it");
    }

    @Test
    void noFittingRoom_leavesTheSlotRoomless_notDropped() {
        // A too-small room and a room whose schedule we could not read never
        // earn a suggestion (a suggested room reads as a booking promise),
        // but the slot itself survives.
        Map<String, MailboxWindowSchedule> schedules = Map.of(
                "a@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)),
                "small@trustworks.dk", schedule("0".repeat(FULL_SCAN_CELLS)));
        List<RoomOption> rooms = List.of(
                new RoomOption("small@trustworks.dk", "Small", 2),
                new RoomOption("ghost@trustworks.dk", "Ghost", 12),
                new RoomOption("nocap@trustworks.dk", "NoCapacity", null));

        List<Slot> slots = AvailabilitySlotSuggester.suggest(
                MONDAY, schedules, List.of("a@trustworks.dk"), rooms, 60, 4, WINDOW_OPEN);

        assertEquals(50, slots.size());
        assertNull(slots.get(0).roomEmail(),
                "Small is too small; Ghost and NoCapacity have no readable schedule");
        assertNull(slots.get(0).roomDisplayName());
    }

    // ---- Helpers ---------------------------------------------------------------

    /** Cells enough to cover the whole 10-business-day scan window. */
    private static final int FULL_SCAN_CELLS = 15 * 24 * 4;

    /** The starts suggested for one day, in the order they are returned. */
    private static List<LocalDateTime> startsOn(List<Slot> slots, LocalDate day) {
        return slots.stream()
                .map(Slot::start)
                .filter(start -> start.toLocalDate().equals(day))
                .toList();
    }

    /** Does {@code [start, start+duration)} touch {@code [from, to)}? */
    private static boolean overlaps(LocalDateTime start, int durationMinutes,
                                    String from, String to) {
        LocalDateTime busyFrom = start.toLocalDate().atTime(LocalTime.parse(from));
        LocalDateTime busyTo = start.toLocalDate().atTime(LocalTime.parse(to));
        return start.isBefore(busyTo) && start.plusMinutes(durationMinutes).isAfter(busyFrom);
    }

    private static MailboxWindowSchedule schedule(String availabilityView) {
        return new MailboxWindowSchedule(availabilityView, null);
    }

    private static Set<DayOfWeek> weekdays() {
        return Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
    }

    /**
     * A continuous availability view (anchored at MONDAY 07:00, 15-minute
     * cells) that is free everywhere except {@code [from, to)} on the
     * anchor day.
     */
    private static String busyBetween(String from, String to) {
        StringBuilder view = new StringBuilder("0".repeat(FULL_SCAN_CELLS));
        int firstCell = (int) Duration.between(
                WINDOW_OPEN, MONDAY.atTime(LocalTime.parse(from))).toMinutes() / 15;
        int cellsBusy = (int) Duration.between(
                MONDAY.atTime(LocalTime.parse(from)), MONDAY.atTime(LocalTime.parse(to))).toMinutes() / 15;
        for (int cell = firstCell; cell < firstCell + cellsBusy; cell++) {
            view.setCharAt(cell, '2');
        }
        return view.toString();
    }
}
