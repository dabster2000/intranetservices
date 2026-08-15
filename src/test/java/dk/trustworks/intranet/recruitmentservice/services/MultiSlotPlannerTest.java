package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.MailboxWindowSchedule;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.RoomOption;
import dk.trustworks.intranet.recruitmentservice.services.MultiSlotPlanner.PlanRequest;
import dk.trustworks.intranet.recruitmentservice.services.MultiSlotPlanner.PlannedSlot;
import dk.trustworks.intranet.recruitmentservice.services.MultiSlotPlanner.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Method B option planning (plan §8.4): separation matrix, different-days,
 * permitted hours, window bounds, weekend skip, room requirement,
 * excluded intervals, top-up seeding and exhaustion. Pure unit test —
 * the DB-free tier that gates deploys.
 */
class MultiSlotPlannerTest {

    /** Monday 2026-08-17; the probe anchor is 07:00 that day. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);
    private static final LocalDate FRIDAY = MONDAY.plusDays(4);
    private static final LocalDateTime EARLY = MONDAY.minusDays(7).atStartOfDay();

    private static final String A = "a@trustworks.dk";

    // ---- Basics ----------------------------------------------------------

    @Test
    void picksRequestedCount_earliestFirst_neverOverlapping() {
        // Options are alternatives, but each one holds real calendar
        // time — even at separation 0 two options never overlap, so
        // 60-minute options on the 30-minute grid land a full hour apart.
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 3, 0, false, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(), List.of()));
        assertEquals(3, picks.size());
        assertEquals(MONDAY.atTime(7, 0), picks.get(0).start());
        assertEquals(MONDAY.atTime(8, 0), picks.get(1).start());
        assertEquals(MONDAY.atTime(9, 0), picks.get(2).start());
    }

    @Test
    void permittedHours_clipTheDay() {
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, LocalTime.of(10, 0), LocalTime.of(12, 0),
                60, 3, 0, false, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(), List.of()));
        // Inside 10–12 only two non-overlapping 60-minute options fit.
        assertEquals(List.of(MONDAY.atTime(10, 0), MONDAY.atTime(11, 0)),
                picks.stream().map(PlannedSlot::start).toList());
    }

    @Test
    void weekends_areNeverProposed() {
        LocalDate saturday = MONDAY.plusDays(5);
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                saturday, saturday.plusDays(1), null, null, 60, 3, 0, false, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(), List.of()));
        assertTrue(picks.isEmpty());
    }

    @Test
    void busyDigits_disqualify() {
        // Busy 07:00–12:00 (20 digits '2'), free after.
        MailboxWindowSchedule schedule = new MailboxWindowSchedule(
                "2".repeat(20) + "0".repeat(1000), null);
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 1, 0, false, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, schedule), EARLY, List.of(), List.of()));
        assertEquals(MONDAY.atTime(12, 0), picks.get(0).start());
    }

    // ---- Separation & different days -------------------------------------

    @Test
    void minSeparation_holdsBetweenPicks() {
        // 3 h separation, 1 h slots ⇒ starts at least 4 h apart.
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 3, 3, false, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(), List.of()));
        assertEquals(List.of(MONDAY.atTime(7, 0), MONDAY.atTime(11, 0),
                        MONDAY.atTime(15, 0)),
                picks.stream().map(PlannedSlot::start).toList());
    }

    @Test
    void differentDays_forcesDistinctDates() {
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, FRIDAY, null, null, 60, 3, 0, true, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(), List.of()));
        assertEquals(3, picks.size());
        assertEquals(3, picks.stream()
                .map(pick -> pick.start().toLocalDate()).distinct().count());
    }

    @Test
    void separation_alsoHoldsAgainstAlreadyPlannedSlots() {
        // A live option 07:00–08:00 Monday + 3 h separation ⇒ the first
        // new pick starts 11:00, and only 2 are asked for.
        PlannedSlot existing = new PlannedSlot(
                MONDAY.atTime(7, 0), MONDAY.atTime(8, 0), null, null, 0, 0);
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 2, 3, false, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(existing), List.of()));
        assertEquals(List.of(MONDAY.atTime(11, 0), MONDAY.atTime(15, 0)),
                picks.stream().map(PlannedSlot::start).toList());
    }

    @Test
    void differentDays_alsoHoldsAgainstAlreadyPlannedSlots() {
        PlannedSlot existing = new PlannedSlot(
                MONDAY.atTime(9, 0), MONDAY.atTime(10, 0), null, null, 0, 0);
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, FRIDAY, null, null, 60, 1, 0, true, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(existing), List.of()));
        assertEquals(1, picks.size());
        assertEquals(MONDAY.plusDays(1), picks.get(0).start().toLocalDate());
    }

    // ---- Excluded intervals ----------------------------------------------

    @Test
    void excludedIntervals_areNeverReproposed() {
        // A rejected 07:00–08:00 slot: nothing overlapping it comes back.
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 1, 0, false, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(),
                List.of(new TimeInterval(MONDAY.atTime(7, 0), MONDAY.atTime(8, 0)))));
        assertEquals(MONDAY.atTime(8, 0), picks.get(0).start());
    }

    // ---- Rooms -----------------------------------------------------------

    @Test
    void requireRoom_skipsSlotsWithoutAKnownFreeRoom() {
        String room = "room@trustworks.dk";
        // The room is busy 07:00–09:00; required interviewer free all day.
        MailboxWindowSchedule roomSchedule = new MailboxWindowSchedule(
                "2".repeat(8) + "0".repeat(1000), null);
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 1, 0, false, true,
                List.of(A), List.of(), List.of(new RoomOption(room, "Lille", 4)),
                Map.of(A, allFree(), room, roomSchedule), EARLY,
                List.of(), List.of()));
        assertEquals(MONDAY.atTime(9, 0), picks.get(0).start());
        assertEquals(room, picks.get(0).roomEmail());
    }

    @Test
    void requireRoom_withUnknownRoomSchedule_findsNothing() {
        // Unknown room schedule = no promise (the strict room posture).
        String room = "room@trustworks.dk";
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 1, 0, false, true,
                List.of(A), List.of(), List.of(new RoomOption(room, "Lille", 4)),
                Map.of(A, allFree()), EARLY, List.of(), List.of()));
        assertTrue(picks.isEmpty());
    }

    @Test
    void withoutRequireRoom_slotsComeWithoutRoom() {
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 1, 0, false, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(), List.of()));
        assertNull(picks.get(0).roomEmail());
    }

    // ---- Optional interviewers -------------------------------------------

    @Test
    void optionalInterviewers_neverBlock_butAreCounted() {
        String optional = "opt@trustworks.dk";
        // Optional busy 07:00–07:30 — the 07:00 slot still qualifies,
        // with an optional-free count of 0; from 07:30 they count as 1.
        MailboxWindowSchedule optionalSchedule = new MailboxWindowSchedule(
                "2".repeat(2) + "0".repeat(1000), null);
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 2, 0, false, false,
                List.of(A), List.of(optional), List.of(),
                Map.of(A, allFree(), optional, optionalSchedule), EARLY,
                List.of(), List.of()));
        assertEquals(MONDAY.atTime(7, 0), picks.get(0).start());
        assertEquals(0, picks.get(0).optionalFreeCount());
        assertEquals(1, picks.get(1).optionalFreeCount());
    }

    // ---- Exhaustion & bounds ---------------------------------------------

    @Test
    void exhaustion_returnsFewerThanRequested() {
        // Different days over a single-day window: only one can exist.
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 3, 0, true, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(), List.of()));
        assertEquals(1, picks.size());
    }

    @Test
    void notBefore_skipsTooSoonSlots() {
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 1, 0, false, false,
                List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), MONDAY.atTime(12, 0), List.of(), List.of()));
        assertEquals(MONDAY.atTime(12, 0), picks.get(0).start());
    }

    @Test
    void alignUp_roundsToTheNextHalfHour() {
        assertEquals(LocalTime.of(9, 0), MultiSlotPlanner.alignUp(LocalTime.of(9, 0)));
        assertEquals(LocalTime.of(9, 30), MultiSlotPlanner.alignUp(LocalTime.of(9, 1)));
        assertEquals(LocalTime.of(10, 0), MultiSlotPlanner.alignUp(LocalTime.of(9, 31)));
    }

    // ---- Fixture ---------------------------------------------------------

    private static MailboxWindowSchedule allFree() {
        return new MailboxWindowSchedule("0".repeat(4000), null);
    }

    @SuppressWarnings("java:S107")
    private static PlanRequest request(LocalDate windowStart, LocalDate windowEnd,
                                       LocalTime permittedStart, LocalTime permittedEnd,
                                       int duration, int requested, int minSeparation,
                                       boolean differentDays, boolean requireRoom,
                                       List<String> required, List<String> optional,
                                       List<RoomOption> rooms,
                                       Map<String, MailboxWindowSchedule> schedules,
                                       LocalDateTime notBefore,
                                       List<PlannedSlot> alreadyPlanned,
                                       List<TimeInterval> excluded) {
        return new PlanRequest(windowStart, windowEnd, permittedStart, permittedEnd,
                duration, requested, minSeparation, differentDays, requireRoom,
                required.size() + optional.size() + 1, required, optional, rooms,
                schedules, notBefore, alreadyPlanned, excluded, List.of(), Map.of());
    }

    // ---- F2: requireRoom=false never touches rooms -----------------------

    @Test
    void withoutRequireRoom_noRoomIsAssigned_evenWhenOneIsFree() {
        // Owner decision 2026-08-14: an online interview must not block
        // a physical room — not even opportunistically.
        RoomOption room = new RoomOption("room@trustworks.dk", "Room 1", 8);
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request(
                MONDAY, MONDAY, null, null, 60, 1, 0, false, false,
                List.of(A), List.of(), List.of(room),
                Map.of(A, allFree(), "room@trustworks.dk", allFree()),
                EARLY, List.of(), List.of()));
        assertEquals(1, picks.size());
        assertNull(picks.getFirst().roomEmail());
    }

    // ---- F5: declined times repel the re-plan ----------------------------

    @Test
    void declinedSlot_pushesReplanToAnotherDay() {
        // Monday 13.00 was declined; with Tuesday available the re-plan
        // must NOT answer with Monday 13.30 (the adjacent half-hour).
        List<TimeInterval> declined = List.of(new TimeInterval(
                MONDAY.atTime(13, 0), MONDAY.atTime(13, 45)));
        PlanRequest request = new PlanRequest(
                MONDAY, MONDAY.plusDays(1), null, null, 45, 1, 0, false, false,
                2, List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(),
                List.of(new TimeInterval(MONDAY.atTime(13, 0), MONDAY.atTime(13, 45))),
                declined, Map.of());
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request);
        assertEquals(1, picks.size());
        assertEquals(MONDAY.plusDays(1), picks.getFirst().start().toLocalDate(),
                "a declined Monday must rank every Tuesday slot above Monday");
    }

    @Test
    void declinedPenalty_tiersByDayAndProximity() {
        List<TimeInterval> declined = List.of(new TimeInterval(
                MONDAY.atTime(13, 0), MONDAY.atTime(13, 45)));
        assertEquals(0, MultiSlotPlanner.declinedPenalty(declined,
                MONDAY.plusDays(1).atTime(13, 30)), "other days are unpenalised");
        assertEquals(MultiSlotPlanner.DECLINED_SAME_DAY_PENALTY,
                MultiSlotPlanner.declinedPenalty(declined, MONDAY.atTime(8, 0)),
                "same day, far away: day penalty only");
        assertEquals(MultiSlotPlanner.DECLINED_SAME_DAY_PENALTY
                        + MultiSlotPlanner.DECLINED_NEAR_TIME_PENALTY,
                MultiSlotPlanner.declinedPenalty(declined, MONDAY.atTime(13, 30)),
                "the adjacent half-hour: day + proximity");
    }

    // ---- F1a: a stated available period beats the O365 calendar ----------

    @Test
    void statedAvailablePeriod_overridesO365Busy() {
        // O365 shows Monday fully busy, but the interviewer SAID
        // "Monday 13–15 works" — the human's word wins (owner decision
        // 2026-08-14); outside the stated window the day stays blocked.
        MailboxWindowSchedule allBusy = new MailboxWindowSchedule("2".repeat(4000), null);
        Map<String, List<MultiSlotPlanner.ExternalConstraint>> constraints = Map.of(A,
                List.of(new MultiSlotPlanner.ExternalConstraint(
                        dk.trustworks.intranet.recruitmentservice.model.enums
                                .AvailabilityConstraintType.AVAILABLE_ONLY,
                        MONDAY.atTime(13, 0), MONDAY.atTime(15, 0),
                        MONDAY, MONDAY)));
        PlanRequest request = new PlanRequest(
                MONDAY, MONDAY, null, null, 60, 3, 0, false, false,
                2, List.of(A), List.of(), List.of(),
                Map.of(A, allBusy), EARLY, List.of(), List.of(), List.of(),
                constraints);
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request);
        assertEquals(2, picks.size(), "only the stated 13–15 window plans");
        assertEquals(MONDAY.atTime(13, 0), picks.get(0).start());
        assertEquals(MONDAY.atTime(14, 0), picks.get(1).start());
    }

    @Test
    void statedWindow_waivesTheDeclinedDayRepulsion() {
        // Backlog fix 2026-08-15: the interviewer declined Monday 13.00,
        // then SAID "Monday 13-15 works" — the explicit statement beats
        // the implicit day-repulsion, so Monday 13.00's window must
        // outrank an unpenalised Tuesday.
        List<TimeInterval> declined = List.of(new TimeInterval(
                MONDAY.atTime(13, 0), MONDAY.atTime(13, 45)));
        Map<String, List<MultiSlotPlanner.ExternalConstraint>> constraints = Map.of(A,
                List.of(new MultiSlotPlanner.ExternalConstraint(
                        dk.trustworks.intranet.recruitmentservice.model.enums
                                .AvailabilityConstraintType.AVAILABLE_ONLY,
                        MONDAY.atTime(13, 0), MONDAY.atTime(15, 0),
                        MONDAY, MONDAY)));
        PlanRequest request = new PlanRequest(
                MONDAY, MONDAY.plusDays(1), null, null, 45, 1, 0, false, false,
                2, List.of(A), List.of(), List.of(),
                Map.of(A, allFree()), EARLY, List.of(),
                List.of(new TimeInterval(MONDAY.atTime(13, 0), MONDAY.atTime(13, 45))),
                declined, constraints);
        List<PlannedSlot> picks = MultiSlotPlanner.plan(request);
        assertEquals(1, picks.size());
        assertEquals(MONDAY, picks.getFirst().start().toLocalDate(),
                "the stated Monday window must not be repelled to Tuesday");
        assertEquals(MONDAY.atTime(14, 0), picks.getFirst().start(),
                "everything overlapping the declined 13.00-13.45 stays excluded; "
                        + "the window's first clear slot wins");
    }
}
