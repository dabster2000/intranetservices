package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.MailboxWindowSchedule;
import dk.trustworks.intranet.recruitmentservice.services.AvailabilitySlotSuggester.RoomOption;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Picks up to N interview options for a Method B scheduling request
 * (plan §8.4): scans the request's date window with the same digit
 * semantics and postures as {@link AvailabilitySlotSuggester} (unknown
 * never counts as busy for people; rooms need a KNOWN free schedule),
 * then greedily selects options satisfying the request's separation and
 * different-days rules.
 * <p>
 * Ranking is deterministic (earliest first, then room fit, then the
 * optional-interviewer preference score — richer preference inputs
 * arrive in Phase 12), so the same inputs always plan the same options:
 * a re-run after a deploy re-derives, never re-invents.
 * <p>
 * Other requests' holds block naturally: a hold is a tentative Graph
 * event, and tentative digits are busy digits. This request's OWN
 * pipeline is excluded via {@code excluded} intervals instead, so a
 * re-search never proposes a time it already holds or that an
 * interviewer already declined.
 * <p>
 * Pure and CDI-free — covered by the DB-free tier that gates deploys.
 */
public final class MultiSlotPlanner {

    private MultiSlotPlanner() {
    }

    /** A time interval this plan must stay clear of (own held/proposed/
     * rejected slots). End exclusive. */
    public record TimeInterval(LocalDateTime start, LocalDateTime end) {
        boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
            return otherStart.isBefore(end) && start.isBefore(otherEnd);
        }
    }

    /**
     * The full planning input. Mailbox keys/addresses are lowercase; the
     * schedules map shares one digit anchor: index 0 =
     * {@code windowStart} at {@link AvailabilitySlotSuggester#DAY_WINDOW_START},
     * digits running CONTINUOUSLY (nights and weekends included) to
     * {@code windowEnd} at {@link AvailabilitySlotSuggester#DAY_WINDOW_END}.
     *
     * @param permittedStart earliest wall-clock start (null = probe start)
     * @param permittedEnd   latest wall-clock END (null = probe end)
     * @param notBefore      slots strictly before this are skipped (pass
     *                       "now" — a parameter to stay deterministic)
     * @param alreadyPlanned the request's LIVE slots — new picks must
     *                       satisfy separation/different-days against
     *                       them, but they do not count toward
     *                       {@code requestedOptions} (top-up planning)
     * @param excluded       times never to propose again (this request's
     *                       rejected/released slots)
     */
    public record PlanRequest(
            LocalDate windowStart,
            LocalDate windowEnd,
            LocalTime permittedStart,
            LocalTime permittedEnd,
            int durationMinutes,
            int requestedOptions,
            int minSeparationHours,
            boolean differentDays,
            boolean requireRoom,
            int headcount,
            List<String> requiredEmails,
            List<String> optionalEmails,
            List<RoomOption> rooms,
            Map<String, MailboxWindowSchedule> schedules,
            LocalDateTime notBefore,
            List<PlannedSlot> alreadyPlanned,
            List<TimeInterval> excluded) {
    }

    /** One planned option; room fields null when no room was secured. */
    public record PlannedSlot(LocalDateTime start, LocalDateTime end,
                              String roomEmail, String roomDisplayName,
                              int optionalFreeCount) {
    }

    /**
     * Plan up to {@code requestedOptions} options. Fewer — possibly
     * zero — come back when the window is exhausted; the orchestrator
     * decides whether to keep searching or hand back.
     */
    public static List<PlannedSlot> plan(PlanRequest request) {
        List<PlannedSlot> feasible = feasibleSlots(request);
        feasible.sort(Comparator
                .comparing(PlannedSlot::start)
                .thenComparing(slot -> slot.roomEmail() == null) // room fit first
                .thenComparing(Comparator.comparingInt(PlannedSlot::optionalFreeCount).reversed()));

        // Existing live slots seed the constraint set but not the count.
        List<PlannedSlot> constraintSet = new ArrayList<>(request.alreadyPlanned());
        List<PlannedSlot> picked = new ArrayList<>();
        for (PlannedSlot candidate : feasible) {
            if (picked.size() >= request.requestedOptions()) {
                break;
            }
            if (compatible(candidate, constraintSet, request)) {
                constraintSet.add(candidate);
                picked.add(candidate);
            }
        }
        return picked;
    }

    /** Every slot that could be offered, ignoring inter-option rules. */
    private static List<PlannedSlot> feasibleSlots(PlanRequest request) {
        LocalDateTime anchor = request.windowStart()
                .atTime(AvailabilitySlotSuggester.DAY_WINDOW_START);
        LocalTime earliest = laterOf(AvailabilitySlotSuggester.DAY_WINDOW_START,
                request.permittedStart());
        LocalTime latestEnd = earlierOf(AvailabilitySlotSuggester.DAY_WINDOW_END,
                request.permittedEnd());

        List<PlannedSlot> feasible = new ArrayList<>();
        for (LocalDate day = request.windowStart();
             !day.isAfter(request.windowEnd());
             day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY
                    || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            LocalDateTime slotStart = day.atTime(alignUp(earliest));
            LocalDateTime lastStart = day.atTime(latestEnd)
                    .minusMinutes(request.durationMinutes());
            while (!slotStart.isAfter(lastStart)) {
                PlannedSlot slot = evaluate(request, anchor, slotStart);
                if (slot != null) {
                    feasible.add(slot);
                }
                slotStart = slotStart.plusMinutes(AvailabilitySlotSuggester.STEP_MINUTES);
            }
        }
        return feasible;
    }

    /** Null when the start does not qualify. */
    private static PlannedSlot evaluate(PlanRequest request, LocalDateTime anchor,
                                        LocalDateTime slotStart) {
        LocalDateTime slotEnd = slotStart.plusMinutes(request.durationMinutes());
        if (slotStart.isBefore(request.notBefore())) {
            return null;
        }
        for (TimeInterval interval : request.excluded()) {
            if (interval.overlaps(slotStart, slotEnd)) {
                return null;
            }
        }
        for (String email : request.requiredEmails()) {
            if (!personFree(request, anchor, email, slotStart)) {
                return null;
            }
        }
        RoomOption room = smallestFittingFreeRoom(request, anchor, slotStart);
        if (request.requireRoom() && room == null) {
            return null;
        }
        int optionalFree = 0;
        for (String email : request.optionalEmails()) {
            if (personFree(request, anchor, email, slotStart)) {
                optionalFree++;
            }
        }
        return new PlannedSlot(slotStart, slotEnd,
                room != null ? room.email() : null,
                room != null ? room.displayName() : null,
                optionalFree);
    }

    /** The interviewer posture: unknown never counts as busy. */
    private static boolean personFree(PlanRequest request, LocalDateTime anchor,
                                      String email, LocalDateTime slotStart) {
        MailboxWindowSchedule schedule = request.schedules().get(email);
        if (schedule == null) {
            return true;
        }
        return AvailabilitySlotSuggester.withinWorkingHours(
                        schedule.workingHours(), slotStart, request.durationMinutes())
                && AvailabilitySlotSuggester.viewFree(anchor, schedule.availabilityView(),
                        slotStart, request.durationMinutes(), true);
    }

    /** The room posture: only a KNOWN free schedule makes a promise. */
    private static RoomOption smallestFittingFreeRoom(PlanRequest request,
                                                      LocalDateTime anchor,
                                                      LocalDateTime slotStart) {
        return request.rooms().stream()
                .filter(room -> room.capacity() != null
                        && room.capacity() >= request.headcount())
                .filter(room -> {
                    MailboxWindowSchedule schedule = request.schedules().get(room.email());
                    return schedule != null && schedule.availabilityView() != null
                            && AvailabilitySlotSuggester.viewFree(anchor,
                                    schedule.availabilityView(), slotStart,
                                    request.durationMinutes(), false);
                })
                .min(Comparator.comparingInt(RoomOption::capacity))
                .orElse(null);
    }

    /** The inter-option rules: overlap, separation, different days. */
    private static boolean compatible(PlannedSlot candidate, List<PlannedSlot> picked,
                                      PlanRequest request) {
        for (PlannedSlot other : picked) {
            if (request.differentDays()
                    && candidate.start().toLocalDate().equals(other.start().toLocalDate())) {
                return false;
            }
            long gapMinutes = gapMinutes(candidate, other);
            if (gapMinutes < 0) {
                return false; // overlap
            }
            if (gapMinutes < request.minSeparationHours() * 60L) {
                return false;
            }
        }
        return true;
    }

    /** Minutes of clear air between two slots; negative on overlap. */
    private static long gapMinutes(PlannedSlot a, PlannedSlot b) {
        if (!a.end().isAfter(b.start())) {
            return ChronoUnit.MINUTES.between(a.end(), b.start());
        }
        if (!b.end().isAfter(a.start())) {
            return ChronoUnit.MINUTES.between(b.end(), a.start());
        }
        return -1;
    }

    /** Round a wall-clock time UP to the next 30-minute boundary. */
    static LocalTime alignUp(LocalTime time) {
        int step = AvailabilitySlotSuggester.STEP_MINUTES;
        int minuteOfDay = time.getHour() * 60 + time.getMinute();
        int aligned = ((minuteOfDay + step - 1) / step) * step;
        if (time.getSecond() > 0 && aligned == minuteOfDay) {
            aligned += step;
        }
        if (aligned >= 24 * 60) {
            return LocalTime.of(23, 30);
        }
        return LocalTime.of(aligned / 60, aligned % 60);
    }

    private static LocalTime laterOf(LocalTime base, LocalTime other) {
        return other == null || other.isBefore(base) ? base : other;
    }

    private static LocalTime earlierOf(LocalTime base, LocalTime other) {
        return other == null || other.isAfter(base) ? base : other;
    }
}
