package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.MailboxWindowSchedule;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.RoomOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The room-choice rule both automatic planners share (V513).
 *
 * <p>These tests pin the behaviour that replaced "smallest room that seats
 * the headcount": the offered list is the admin's preference order and the
 * first acceptable room wins. The old rule made one room win every interview
 * forever — the smallest fit always beat everything, and equal-capacity ties
 * fell to Graph's list order, which nobody at Trustworks could see or change.
 */
@DisplayName("MeetingRoomPicker — preference order decides, capacity is only a floor")
class MeetingRoomPickerTest {

    /** Digit index 0 of every availability view below. */
    private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 8, 24, 7, 0);
    private static final LocalDateTime SLOT = LocalDateTime.of(2026, 8, 24, 10, 0);
    private static final int DURATION = 60;

    // ---- The preference rule ---------------------------------------------------

    @Test
    void firstPreferenceWins_whenItIsFreeAndBigEnough() {
        List<RoomOption> rooms = List.of(
                room("stor", 20), room("hp1", 8), room("hp3", 4));

        MeetingRoomPicker.Pick pick = MeetingRoomPicker.pick(
                ANCHOR, allFree(rooms), rooms, SLOT, DURATION, 3);

        assertEquals("stor", pick.room().email());
        assertEquals(1, pick.rank());
        assertNull(pick.reason(), "the top preference winning needs no explanation");
    }

    @Test
    void biggerRoomIsPreferable_whenTheAdminSaidSo() {
        // The whole point: a 3-person interview may deliberately land in the
        // 20-seat room. Under the old smallest-fit rule this was unsayable.
        List<RoomOption> rooms = List.of(room("stor", 20), room("hp3", 4));

        MeetingRoomPicker.Pick pick = MeetingRoomPicker.pick(
                ANCHOR, allFree(rooms), rooms, SLOT, DURATION, 3);

        assertEquals("stor", pick.room().email());
    }

    @Test
    void reversingThePreferenceOrder_reversesTheChoice() {
        // Order is the ONLY thing that decides between two acceptable rooms —
        // no hidden capacity comparator, no Graph list order.
        List<RoomOption> first = List.of(room("hp3", 4), room("stor", 20));
        List<RoomOption> second = List.of(room("stor", 20), room("hp3", 4));

        assertEquals("hp3", MeetingRoomPicker.pick(
                ANCHOR, allFree(first), first, SLOT, DURATION, 3).room().email());
        assertEquals("stor", MeetingRoomPicker.pick(
                ANCHOR, allFree(second), second, SLOT, DURATION, 3).room().email());
    }

    @Test
    void equalCapacityRooms_areDecidedByOrder_notByGraphsListOrder() {
        List<RoomOption> rooms = List.of(room("hp4", 6), room("hp3", 6));

        MeetingRoomPicker.Pick pick = MeetingRoomPicker.pick(
                ANCHOR, allFree(rooms), rooms, SLOT, DURATION, 4);

        assertEquals("hp4", pick.room().email(),
                "the ranked room wins; equal capacity is not a tiebreak at all");
    }

    // ---- Capacity as a floor ---------------------------------------------------

    @Test
    void tooSmallRoomsAreSkipped_andTheReasonSaysSo() {
        List<RoomOption> rooms = List.of(room("hp3", 2), room("stor", 20));

        MeetingRoomPicker.Pick pick = MeetingRoomPicker.pick(
                ANCHOR, allFree(rooms), rooms, SLOT, DURATION, 6);

        assertEquals("stor", pick.room().email());
        assertEquals(2, pick.rank());
        assertEquals(1, pick.skippedTooSmall());
        assertEquals("preference 2 — 1 was too small", pick.reason());
    }

    @Test
    void unknownCapacityNeverDisqualifies() {
        // Graph reports no capacity when the Exchange resource has none set.
        // The old rule dropped those rooms entirely, making them invisible to
        // automation with nothing anywhere explaining why.
        List<RoomOption> rooms = List.of(room("nocap", null));

        MeetingRoomPicker.Pick pick = MeetingRoomPicker.pick(
                ANCHOR, allFree(rooms), rooms, SLOT, DURATION, 99);

        assertEquals("nocap", pick.room().email(),
                "unknown capacity cannot prove the room too small");
    }

    // ---- Availability posture --------------------------------------------------

    @Test
    void busyFirstPreference_fallsThroughAndExplains() {
        List<RoomOption> rooms = List.of(room("stor", 20), room("hp1", 8));
        Map<String, MailboxWindowSchedule> schedules = allFree(rooms);
        schedules.put("stor", schedule(busyAtSlot()));

        MeetingRoomPicker.Pick pick = MeetingRoomPicker.pick(
                ANCHOR, schedules, rooms, SLOT, DURATION, 3);

        assertEquals("hp1", pick.room().email());
        assertEquals("preference 2 — 1 preferred room was unavailable", pick.reason());
    }

    @Test
    void unknownSchedule_disqualifiesTheRoom() {
        // Deliberately the OPPOSITE of the interviewer rule: "unknown never
        // counts as busy" is right for marking a person and wrong for a room,
        // because a suggested room reads as a booking promise.
        List<RoomOption> rooms = List.of(room("ghost", 20));

        assertNull(MeetingRoomPicker.pick(
                ANCHOR, new HashMap<>(), rooms, SLOT, DURATION, 3));
    }

    @Test
    void nullAvailabilityView_disqualifiesTheRoom() {
        List<RoomOption> rooms = List.of(room("ghost", 20));
        Map<String, MailboxWindowSchedule> schedules = new HashMap<>();
        schedules.put("ghost", new MailboxWindowSchedule(null, null));

        assertNull(MeetingRoomPicker.pick(
                ANCHOR, schedules, rooms, SLOT, DURATION, 3));
    }

    // ---- Degenerate inputs -----------------------------------------------------

    @Test
    void noRoomsOffered_isNullNotAnError() {
        assertNull(MeetingRoomPicker.pick(ANCHOR, new HashMap<>(), List.of(),
                SLOT, DURATION, 3));
        assertNull(MeetingRoomPicker.pick(ANCHOR, new HashMap<>(), null,
                SLOT, DURATION, 3));
    }

    @Test
    void everyRoomUnusable_isNullNotAnError() {
        // A missing room costs a room, never the slot — the caller decides
        // whether a roomless slot is acceptable.
        List<RoomOption> rooms = List.of(room("hp3", 2), room("ghost", 20));

        assertNull(MeetingRoomPicker.pick(
                ANCHOR, allFree(List.of(room("hp3", 2))), rooms, SLOT, DURATION, 6));
    }

    @Test
    void nullSchedulesMap_isTolerated() {
        List<RoomOption> rooms = List.of(room("stor", 20));

        assertNull(MeetingRoomPicker.pick(ANCHOR, null, rooms, SLOT, DURATION, 3));
    }

    @Test
    void bothSkipReasonsAreReported() {
        List<RoomOption> rooms = List.of(
                room("tiny", 1), room("busy", 20), room("stor", 20));
        Map<String, MailboxWindowSchedule> schedules = allFree(rooms);
        schedules.put("busy", schedule(busyAtSlot()));

        MeetingRoomPicker.Pick pick = MeetingRoomPicker.pick(
                ANCHOR, schedules, rooms, SLOT, DURATION, 6);

        assertEquals("stor", pick.room().email());
        assertEquals(3, pick.rank());
        assertEquals(1, pick.skippedUnavailable());
        assertEquals(1, pick.skippedTooSmall());
        assertEquals("preference 3 — 1 preferred room was unavailable, 1 was too small",
                pick.reason());
    }

    // ---- Helpers ---------------------------------------------------------------

    private static RoomOption room(String email, Integer capacity) {
        return new RoomOption(email, email.toUpperCase(java.util.Locale.ROOT), capacity);
    }

    /** Enough "0" digits to cover a whole probe day, for every given room. */
    private static Map<String, MailboxWindowSchedule> allFree(List<RoomOption> rooms) {
        Map<String, MailboxWindowSchedule> schedules = new HashMap<>();
        rooms.forEach(room -> schedules.put(room.email(), schedule("0".repeat(48))));
        return schedules;
    }

    /** A view that is busy exactly over {@link #SLOT}. */
    private static String busyAtSlot() {
        int firstCell = (int) java.time.Duration.between(ANCHOR, SLOT).toMinutes()
                / AvailabilitySlotSuggester.INTERVAL_MINUTES;
        int cells = DURATION / AvailabilitySlotSuggester.INTERVAL_MINUTES;
        StringBuilder view = new StringBuilder("0".repeat(48));
        for (int cell = firstCell; cell < firstCell + cells; cell++) {
            view.setCharAt(cell, '2');
        }
        return view.toString();
    }

    private static MailboxWindowSchedule schedule(String view) {
        return new MailboxWindowSchedule(view, null);
    }
}
