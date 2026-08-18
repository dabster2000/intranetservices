package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.enums.AvailabilityConstraintType;
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
     * One confirmed external-evidence interval for one interviewer
     * (Phase 12, spec §12.3) — already clipped to its evidence's
     * covered range by {@code AvailabilityConstraintResolver}:
     * <ul>
     *   <li>BUSY subtracts, exactly like an O365 busy digit — busy
     *       always wins, positive claims never override it.</li>
     *   <li>AVAILABLE_ONLY restricts: on days inside
     *       {@code restrictedFrom..restrictedTo} (its evidence's
     *       covered range), a slot must fit inside one such interval;
     *       days OUTSIDE the range are untouched (covered-range-only).</li>
     *   <li>PREFERRED/AVOID only move {@code preferenceScore} — never a
     *       hard exclusion.</li>
     *   <li>{@code overridesCalendar} — whether fitting an AVAILABLE_ONLY
     *       window may beat an O365 busy digit (F1a). TRUE for a human's
     *       typed statement; FALSE when the claim was read out of a calendar
     *       IMAGE (owner decision 2026-08-18): a picture is not a person
     *       promising to move a conflicting meeting, so an image-derived
     *       window restricts the day but still has to respect the real
     *       calendar.</li>
     * </ul>
     * {@code restrictedFrom/To} are non-null exactly for AVAILABLE_ONLY.
     */
    public record ExternalConstraint(AvailabilityConstraintType type,
                                     LocalDateTime start, LocalDateTime end,
                                     LocalDate restrictedFrom, LocalDate restrictedTo,
                                     boolean overridesCalendar) {

        /**
         * Pre-v2 shape: a stated AVAILABLE_ONLY window overrides the O365
         * calendar (F1a). Keeps every existing call site and test compiling.
         */
        public ExternalConstraint(AvailabilityConstraintType type,
                                  LocalDateTime start, LocalDateTime end,
                                  LocalDate restrictedFrom, LocalDate restrictedTo) {
            this(type, start, end, restrictedFrom, restrictedTo, true);
        }
        boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
            return otherStart.isBefore(end) && start.isBefore(otherEnd);
        }

        boolean contains(LocalDateTime otherStart, LocalDateTime otherEnd) {
            return !otherStart.isBefore(start) && !otherEnd.isAfter(end);
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
     * @param declined       times a HUMAN said no to (interviewer decline
     *                       or message answer) — the re-plan repels their
     *                       day and neighbourhood instead of proposing
     *                       the adjacent half-hour (F5)
     * @param externalConstraints confirmed evidence intervals per
     *                       LOWERCASE mailbox (Phase 12) — resolver
     *                       output; empty map = O365-only planning
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
            List<TimeInterval> excluded,
            List<TimeInterval> declined,
            Map<String, List<ExternalConstraint>> externalConstraints) {
    }

    /** One planned option; room fields null when no room was secured. */
    public record PlannedSlot(LocalDateTime start, LocalDateTime end,
                              String roomEmail, String roomDisplayName,
                              int optionalFreeCount, int preferenceScore) {
    }

    /**
     * Plan up to {@code requestedOptions} options. Fewer — possibly
     * zero — come back when the window is exhausted; the orchestrator
     * decides whether to keep searching or hand back.
     */
    public static List<PlannedSlot> plan(PlanRequest request) {
        List<PlannedSlot> feasible = feasibleSlots(request);
        // Phase 12 deviation from plan §8.4's parenthetical order
        // (earliest → room → preference): with distinct start times an
        // earliest-first primary key would make the preference score
        // permanently inert, contradicting §12.5's "PREFERRED/AVOID
        // affect ranking". Preference CLASS ranks first; earliest-first
        // stays the tiebreak inside a class, so a request without
        // preference evidence plans exactly as before (score 0 across
        // the board).
        feasible.sort(Comparator
                .comparingInt(PlannedSlot::preferenceScore).reversed()
                .thenComparing(PlannedSlot::start)
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
        int penalty = declinedPenalty(request.declined(), slotStart);
        if (penalty > 0 && fitsStatedWindow(request, slotStart, slotEnd)) {
            // Explicit beats implicit (the F1a philosophy): a slot inside
            // a window the interviewer SAID works must not be repelled by
            // the day their earlier proposals were declined on — the
            // statement is newer and more specific than the repulsion.
            penalty = 0;
        }
        return new PlannedSlot(slotStart, slotEnd,
                room != null ? room.email() : null,
                room != null ? room.displayName() : null,
                optionalFree,
                preferenceScore(request, slotStart, slotEnd) - penalty);
    }

    /** True when the slot fits a confirmed AVAILABLE_ONLY window of at
     * least one required interviewer. */
    static boolean fitsStatedWindow(PlanRequest request, LocalDateTime slotStart,
                                    LocalDateTime slotEnd) {
        for (String email : request.requiredEmails()) {
            if (externalVerdict(constraintsOf(request, email), slotStart, slotEnd)
                    == ExternalVerdict.AVAILABLE_OVERRIDE) {
                return true;
            }
        }
        return false;
    }

    /** Penalty steps of the F5 repulsion — each far larger than any
     * realistic PREFERRED/AVOID sum, so a declined day only comes back
     * when nothing else fits. */
    static final int DECLINED_SAME_DAY_PENALTY = 4;
    static final int DECLINED_NEAR_TIME_PENALTY = 4;
    static final int DECLINED_NEAR_HOURS = 3;

    /**
     * How hard a candidate start repels times a human declined (F5): a
     * decline means "not that day" far more often than "not that exact
     * half-hour", so the same day costs {@value DECLINED_SAME_DAY_PENALTY}
     * and being within {@value DECLINED_NEAR_HOURS} h of a declined start
     * on that day costs {@value DECLINED_NEAR_TIME_PENALTY} more. Soft on
     * purpose — a window with no other feasible day still plans, just
     * last.
     */
    static int declinedPenalty(List<TimeInterval> declined, LocalDateTime slotStart) {
        int penalty = 0;
        boolean sameDay = false;
        boolean nearTime = false;
        for (TimeInterval interval : declined) {
            if (!interval.start().toLocalDate().equals(slotStart.toLocalDate())) {
                continue;
            }
            sameDay = true;
            long distanceMinutes = Math.abs(
                    ChronoUnit.MINUTES.between(interval.start(), slotStart));
            if (distanceMinutes < DECLINED_NEAR_HOURS * 60L) {
                nearTime = true;
            }
        }
        if (sameDay) {
            penalty += DECLINED_SAME_DAY_PENALTY;
        }
        if (nearTime) {
            penalty += DECLINED_NEAR_TIME_PENALTY;
        }
        return penalty;
    }

    /** What one interviewer's confirmed statements say about a slot. */
    enum ExternalVerdict {
        /** A BUSY claim overlaps, or a governing AVAILABLE_ONLY day
         * does not fit — the slot is out. */
        BLOCKED,
        /** The slot fits inside a stated AVAILABLE_ONLY window — the
         * human's word beats their O365 calendar (F1a). */
        AVAILABLE_OVERRIDE,
        /** No statement governs — O365 decides. */
        NEUTRAL
    }

    /**
     * The interviewer posture: unknown never counts as busy — a
     * CONFIRMED external claim binds regardless of O365 visibility, and
     * (F1a, owner decision 2026-08-14) a slot inside a stated available
     * period IS available even where O365 says busy: people state
     * availability knowing they will move the conflicting meeting. A
     * confirmed external BUSY still beats everything (§27 scenario 4).
     */
    private static boolean personFree(PlanRequest request, LocalDateTime anchor,
                                      String email, LocalDateTime slotStart) {
        LocalDateTime slotEnd = slotStart.plusMinutes(request.durationMinutes());
        ExternalVerdict verdict =
                externalVerdict(constraintsOf(request, email), slotStart, slotEnd);
        if (verdict == ExternalVerdict.BLOCKED) {
            return false;
        }
        if (verdict == ExternalVerdict.AVAILABLE_OVERRIDE) {
            return true;
        }
        MailboxWindowSchedule schedule = request.schedules().get(email);
        if (schedule == null) {
            return true;
        }
        return AvailabilitySlotSuggester.withinWorkingHours(
                        schedule.workingHours(), slotStart, request.durationMinutes())
                && AvailabilitySlotSuggester.viewFree(anchor, schedule.availabilityView(),
                        slotStart, request.durationMinutes(), true);
    }

    /**
     * The spec §12.3 precedence, hard-rule half (pure; matrix-tested):
     * any BUSY overlap blocks — busy claims always win, whatever else
     * is stated. On a day at least one AVAILABLE_ONLY window governs,
     * the slot must fit inside one of the (resolver-merged) windows —
     * and when it does, the fit is an OVERRIDE: the stated period beats
     * the O365 calendar (F1a). Days outside every restricted range are
     * untouched (covered-range-only).
     */
    static ExternalVerdict externalVerdict(List<ExternalConstraint> constraints,
                                           LocalDateTime slotStart, LocalDateTime slotEnd) {
        boolean dayRestricted = false;
        boolean fitsRestriction = false;
        boolean fitsWithoutOverride = false;
        LocalDate slotDate = slotStart.toLocalDate();
        for (ExternalConstraint constraint : constraints) {
            switch (constraint.type()) {
                case BUSY -> {
                    if (constraint.overlaps(slotStart, slotEnd)) {
                        return ExternalVerdict.BLOCKED;
                    }
                }
                case AVAILABLE_ONLY -> {
                    if (!slotDate.isBefore(constraint.restrictedFrom())
                            && !slotDate.isAfter(constraint.restrictedTo())) {
                        dayRestricted = true;
                        if (constraint.contains(slotStart, slotEnd)) {
                            fitsRestriction = true;
                            if (!constraint.overridesCalendar()) {
                                // Image-derived: restrict the day, but let the
                                // O365 calendar still have the final say.
                                fitsWithoutOverride = true;
                            }
                        }
                    }
                }
                default -> {
                    // PREFERRED/AVOID never exclude.
                }
            }
        }
        if (dayRestricted) {
            if (!fitsRestriction) {
                return ExternalVerdict.BLOCKED;
            }
            // A window read out of an image restricts the day but does not
            // license overriding the calendar — fall through to O365.
            return fitsWithoutOverride
                    ? ExternalVerdict.NEUTRAL : ExternalVerdict.AVAILABLE_OVERRIDE;
        }
        return ExternalVerdict.NEUTRAL;
    }

    /** The soft-rule half: +1 per fully containing PREFERRED, −1 per
     * overlapping AVOID, summed over every interviewer's constraints. */
    static int preferenceScore(PlanRequest request, LocalDateTime slotStart,
                               LocalDateTime slotEnd) {
        int score = 0;
        for (List<ExternalConstraint> constraints : request.externalConstraints().values()) {
            for (ExternalConstraint constraint : constraints) {
                if (constraint.type() == AvailabilityConstraintType.PREFERRED
                        && constraint.contains(slotStart, slotEnd)) {
                    score++;
                } else if (constraint.type() == AvailabilityConstraintType.AVOID
                        && constraint.overlaps(slotStart, slotEnd)) {
                    score--;
                }
            }
        }
        return score;
    }

    private static List<ExternalConstraint> constraintsOf(PlanRequest request, String email) {
        return request.externalConstraints().getOrDefault(email, List.of());
    }

    /** The room posture: only a KNOWN free schedule makes a promise —
     * and no room is ever touched unless the request ASKED for one
     * (F2, owner decision 2026-08-14). */
    private static RoomOption smallestFittingFreeRoom(PlanRequest request,
                                                      LocalDateTime anchor,
                                                      LocalDateTime slotStart) {
        if (!request.requireRoom()) {
            return null;
        }
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
