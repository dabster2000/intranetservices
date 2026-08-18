package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.ai.AvailabilitySchedulingPrompts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The image path's deterministic half (the v2 transcribe-then-compute
 * contract). The vision model transcribes a calendar grid — per day: was it
 * legible, was there an all-day band, FREE/PARTIAL/FULL, and the measured
 * block edges — and THIS class turns that transcription into busy intervals,
 * judges whether it can be trusted, and reconciles independent re-reads.
 * <p>
 * Why it exists. Until 2026-08-18 the model was asked for finished intervals in
 * one leap, and nothing in the contract let it say "this day is empty". An
 * unreadable grid therefore degenerated into a fabricated whole-day BUSY: in
 * production two genuinely empty weekdays came back fully booked, and because
 * {@code MultiSlotPlanner} gives a BUSY claim absolute precedence over the
 * Graph calendar, confirming that reading would have deleted the interviewer's
 * whole week. The fix is structural rather than advisory — busy time is now
 * DERIVED from a transcription, so the model cannot assert busy time it never
 * transcribed.
 * <p>
 * Pure and CDI-free on purpose: every rule here is unit-testable without a
 * database, so it lands in the fast tier that gates deploys.
 */
public final class AvailabilityImageReading {

    private AvailabilityImageReading() {
    }

    // ------------------------------------------------------------------
    // Transcription shapes (mirror AvailabilitySchedulingPrompts.imageSchema)
    // ------------------------------------------------------------------

    /** One measured event block on one day. */
    public record Block(LocalDateTime start, LocalDateTime end, BigDecimal confidence) {
    }

    /** The hour gutter the model calibrated against; nulls mean it found none. */
    public record Axis(LocalTime firstVisibleTime, LocalTime lastVisibleTime,
                       String timezoneLabel) {

        public boolean calibrated() {
            return firstVisibleTime != null && lastVisibleTime != null
                    && firstVisibleTime.isBefore(lastVisibleTime);
        }
    }

    /** One visible day column as transcribed. */
    public record DayRead(LocalDate date, boolean gridReadable, boolean allDayBandVisible,
                          boolean allDayBandContinuesPastCrop, String dayVerdict,
                          List<Block> blocks) {
    }

    /** A derived busy interval — end-exclusive is NOT assumed; see WHOLE_DAY_END. */
    public record Interval(LocalDateTime start, LocalDateTime end, BigDecimal confidence) {
    }

    // ------------------------------------------------------------------
    // Tunables
    // ------------------------------------------------------------------

    /** Whole-day busy is rendered 00:00–23:59, matching the text path's convention. */
    static final LocalTime WHOLE_DAY_END = LocalTime.of(23, 59);

    /** The working window density is measured against. */
    static final LocalTime WORK_START = LocalTime.of(8, 0);
    static final LocalTime WORK_END = LocalTime.of(18, 0);

    /**
     * Above this share of the covered working window claimed busy, the reading
     * is suspicious. Real weeks do get fully booked, so this NEVER blocks — it
     * only marks the reading low-trust so a re-read decides.
     */
    static final double DENSITY_SUSPICIOUS = 0.85d;

    /** Fewer intervals than this and all-round-boundaries proves nothing. */
    static final int ROUNDNESS_MIN_INTERVALS = 3;

    /** Two transcriptions agree on a block when every edge is within this. */
    static final int AGREEMENT_TOLERANCE_MINUTES = 10;

    /** Blocks separated by less than this are merged into one busy interval. */
    static final int MERGE_GAP_MINUTES = 1;

    // ------------------------------------------------------------------
    // Derivation
    // ------------------------------------------------------------------

    /**
     * What the transcription actually supports. {@code busy} is everything the
     * planner may be told; {@code discardedDays} were dropped rather than
     * guessed, and {@code freeDays} are positively-read empty days — the card
     * renders those so a fabricated or omitted day stops being invisible.
     */
    public record Derived(List<Interval> busy, List<LocalDate> freeDays,
                          List<LocalDate> discardedDays, List<String> ambiguities) {
    }

    /**
     * Turn one transcription into busy intervals. The anti-fabrication rules,
     * in the order they fire:
     * <ol>
     *   <li>no calibrated axis ⇒ every measured edge is meaningless, so timed
     *       blocks are dropped wholesale; only all-day bands survive;</li>
     *   <li>{@code gridReadable=false} ⇒ the day is discarded, never guessed;</li>
     *   <li>{@code FREE} ⇒ no busy at all (the rule v1 had no way to express);</li>
     *   <li>{@code FULL} with nothing transcribed ⇒ discarded, because a
     *       whole-day claim with no measured block and no all-day band is
     *       exactly the fabrication signature observed in production;</li>
     *   <li>a block dated outside its own day column is dropped.</li>
     * </ol>
     */
    public static Derived derive(Axis axis, List<DayRead> days) {
        List<Interval> busy = new ArrayList<>();
        List<LocalDate> freeDays = new ArrayList<>();
        List<LocalDate> discarded = new ArrayList<>();
        Set<String> ambiguities = new LinkedHashSet<>();

        boolean calibrated = axis != null && axis.calibrated();
        if (!calibrated) {
            ambiguities.add("Tidsaksen i billedet kunne ikke læses — kun heldagsmarkeringer er brugt.");
        }

        for (DayRead day : days == null ? List.<DayRead>of() : days) {
            if (day == null || day.date() == null) {
                continue;
            }
            if (!day.gridReadable()) {
                discarded.add(day.date());
                ambiguities.add("Kalenderen kunne ikke læses sikkert for " + day.date() + ".");
                continue;
            }
            if (day.allDayBandVisible()) {
                busy.add(new Interval(day.date().atStartOfDay(),
                        day.date().atTime(WHOLE_DAY_END), confidenceOf(day)));
                if (day.allDayBandContinuesPastCrop()) {
                    ambiguities.add("Heldagsaftalen den " + day.date()
                            + " fortsætter uden for billedet — kun de synlige dage er registreret.");
                }
                continue;
            }

            List<Block> blocks = sameDayBlocks(day, ambiguities);
            if (AvailabilitySchedulingPrompts.DAY_FREE.equals(day.dayVerdict())) {
                if (!blocks.isEmpty()) {
                    // The transcription contradicts itself. Trust the measured
                    // blocks — they are evidence; the verdict is a summary.
                    ambiguities.add("Dagen " + day.date()
                            + " blev markeret som fri, men der blev læst aftaler — aftalerne er brugt.");
                } else {
                    freeDays.add(day.date());
                    continue;
                }
            }
            if (blocks.isEmpty()) {
                // FULL or PARTIAL with nothing measured cannot be substantiated.
                discarded.add(day.date());
                ambiguities.add("Ingen aftaletider kunne måles for " + day.date() + ".");
                continue;
            }
            if (!calibrated) {
                discarded.add(day.date());
                continue;
            }
            busy.addAll(mergeBlocks(blocks));
        }

        busy.sort(Comparator.comparing(Interval::start).thenComparing(Interval::end));
        return new Derived(List.copyOf(busy), List.copyOf(freeDays),
                List.copyOf(discarded), List.copyOf(ambiguities));
    }

    private static List<Block> sameDayBlocks(DayRead day, Set<String> ambiguities) {
        List<Block> kept = new ArrayList<>();
        for (Block block : day.blocks() == null ? List.<Block>of() : day.blocks()) {
            if (block == null || block.start() == null || block.end() == null) {
                continue;
            }
            if (!block.start().isBefore(block.end())) {
                ambiguities.add("En ulæselig aftaletid blev sprunget over den " + day.date() + ".");
                continue;
            }
            if (!block.start().toLocalDate().equals(day.date())
                    || !endBelongsToDay(block, day.date())) {
                ambiguities.add("En aftale med forkert dato blev sprunget over den " + day.date() + ".");
                continue;
            }
            kept.add(block);
        }
        kept.sort(Comparator.comparing(Block::start).thenComparing(Block::end));
        return kept;
    }

    /** An end at midnight the next day still belongs to this day. */
    private static boolean endBelongsToDay(Block block, LocalDate date) {
        LocalDate endDate = block.end().toLocalDate();
        return endDate.equals(date)
                || (endDate.equals(date.plusDays(1)) && block.end().toLocalTime().equals(LocalTime.MIDNIGHT));
    }

    /** Union overlapping or touching blocks so one busy interval covers them. */
    static List<Interval> mergeBlocks(List<Block> sorted) {
        List<Interval> merged = new ArrayList<>();
        LocalDateTime start = null;
        LocalDateTime end = null;
        BigDecimal confidence = null;
        for (Block block : sorted) {
            if (start == null) {
                start = block.start();
                end = block.end();
                confidence = block.confidence();
                continue;
            }
            if (!block.start().isAfter(end.plusMinutes(MERGE_GAP_MINUTES))) {
                if (block.end().isAfter(end)) {
                    end = block.end();
                }
                confidence = lower(confidence, block.confidence());
            } else {
                merged.add(new Interval(start, end, confidence));
                start = block.start();
                end = block.end();
                confidence = block.confidence();
            }
        }
        if (start != null) {
            merged.add(new Interval(start, end, confidence));
        }
        return merged;
    }

    private static BigDecimal confidenceOf(DayRead day) {
        BigDecimal lowest = null;
        for (Block block : day.blocks() == null ? List.<Block>of() : day.blocks()) {
            if (block != null) {
                lowest = lower(lowest, block.confidence());
            }
        }
        return lowest == null ? BigDecimal.ONE : lowest;
    }

    private static BigDecimal lower(BigDecimal a, BigDecimal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) <= 0 ? a : b;
    }

    // ------------------------------------------------------------------
    // Trust assessment
    // ------------------------------------------------------------------

    /**
     * Whether a reading looks like a misread. Never a block — the owner's
     * standing decision (2026-08-18) is that this path stays frictionless, so a
     * suspicious verdict spends MACHINE effort (a re-read) instead of asking
     * the interviewer to do anything.
     */
    public record Trust(boolean suspicious, List<String> reasons) {
    }

    /**
     * The two cheap deterministic tells, plus the structural ones:
     * <ul>
     *   <li><b>Roundness.</b> Every boundary on the hour, across three or more
     *       intervals. Real Outlook calendars are full of :15/:30/:45 edges; a
     *       reading with none is the fingerprint of a model emitting round
     *       guesses rather than measuring. This was the 2026-08-18 signature —
     *       0 of 24 boundaries fell on a quarter hour.</li>
     *   <li><b>Density.</b> More than {@value #DENSITY_SUSPICIOUS} of the
     *       covered working window claimed busy. Genuinely full weeks exist,
     *       which is exactly why this informs a re-read instead of a rejection.</li>
     * </ul>
     */
    public static Trust assess(Axis axis, Derived derived, LocalDate coveredFrom,
                               LocalDate coveredTo) {
        List<String> reasons = new ArrayList<>();
        if (axis == null || !axis.calibrated()) {
            reasons.add("NO_AXIS");
        }
        if (!derived.discardedDays().isEmpty()) {
            reasons.add("UNREADABLE_DAYS");
        }
        if (isAllRoundBoundaries(derived.busy())) {
            reasons.add("ALL_ROUND_BOUNDARIES");
        }
        if (isTooDense(derived.busy(), coveredFrom, coveredTo)) {
            reasons.add("IMPLAUSIBLE_DENSITY");
        }
        return new Trust(!reasons.isEmpty(), List.copyOf(reasons));
    }

    /**
     * True when every timed boundary sits exactly on the hour. Whole-day
     * intervals are excluded — they are legitimately 00:00–23:59 and would
     * otherwise mask the signal.
     */
    static boolean isAllRoundBoundaries(List<Interval> busy) {
        int timed = 0;
        for (Interval interval : busy) {
            if (isWholeDay(interval)) {
                continue;
            }
            timed++;
            if (interval.start().getMinute() != 0 || interval.end().getMinute() != 0) {
                return false;
            }
        }
        return timed >= ROUNDNESS_MIN_INTERVALS;
    }

    static boolean isWholeDay(Interval interval) {
        return interval.start().toLocalTime().equals(LocalTime.MIDNIGHT)
                && interval.end().toLocalTime().equals(WHOLE_DAY_END);
    }

    /** Busy share of the working window across the covered weekdays. */
    static boolean isTooDense(List<Interval> busy, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || busy.isEmpty()) {
            return false;
        }
        long workingMinutes = 0;
        long busyMinutes = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            workingMinutes += Duration.between(WORK_START, WORK_END).toMinutes();
            busyMinutes += busyMinutesWithinWorkingHours(busy, day);
        }
        return workingMinutes > 0 && (double) busyMinutes / workingMinutes > DENSITY_SUSPICIOUS;
    }

    private static long busyMinutesWithinWorkingHours(List<Interval> busy, LocalDate day) {
        LocalDateTime windowStart = day.atTime(WORK_START);
        LocalDateTime windowEnd = day.atTime(WORK_END);
        // Flatten to avoid double-counting overlapping intervals.
        boolean[] minutes = new boolean[(int) Duration.between(windowStart, windowEnd).toMinutes()];
        for (Interval interval : busy) {
            LocalDateTime start = interval.start().isBefore(windowStart) ? windowStart : interval.start();
            LocalDateTime end = interval.end().isAfter(windowEnd) ? windowEnd : interval.end();
            if (!start.isBefore(end)) {
                continue;
            }
            int fromIdx = (int) Duration.between(windowStart, start).toMinutes();
            int toIdx = (int) Duration.between(windowStart, end).toMinutes();
            for (int i = Math.max(0, fromIdx); i < Math.min(minutes.length, toIdx); i++) {
                minutes[i] = true;
            }
        }
        long count = 0;
        for (boolean minute : minutes) {
            if (minute) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Reconciling independent re-reads
    // ------------------------------------------------------------------

    /**
     * The per-day consensus of two or three independent transcriptions of the
     * same images. {@code unresolved} days had no majority and are dropped —
     * silence beats a coin flip, because a wrong BUSY outranks the real
     * calendar downstream.
     */
    public record Consensus(List<DayRead> days, List<LocalDate> unresolved,
                            boolean unanimous) {
    }

    /**
     * Keep a day only when at least two transcriptions agree about it. With a
     * single reading (a re-read failed) everything passes through and the
     * caller is told it was not corroborated via {@code unanimous=false}.
     */
    public static Consensus reconcile(List<List<DayRead>> readings) {
        List<List<DayRead>> present = new ArrayList<>();
        for (List<DayRead> reading : readings == null ? List.<List<DayRead>>of() : readings) {
            if (reading != null && !reading.isEmpty()) {
                present.add(reading);
            }
        }
        if (present.isEmpty()) {
            return new Consensus(List.of(), List.of(), false);
        }
        if (present.size() == 1) {
            return new Consensus(List.copyOf(present.get(0)), List.of(), false);
        }

        Map<LocalDate, List<DayRead>> byDate = new LinkedHashMap<>();
        for (List<DayRead> reading : present) {
            for (DayRead day : reading) {
                if (day != null && day.date() != null) {
                    byDate.computeIfAbsent(day.date(), d -> new ArrayList<>()).add(day);
                }
            }
        }

        List<DayRead> agreed = new ArrayList<>();
        List<LocalDate> unresolved = new ArrayList<>();
        boolean unanimous = true;
        for (Map.Entry<LocalDate, List<DayRead>> entry : byDate.entrySet()) {
            List<DayRead> candidates = entry.getValue();
            DayRead winner = largestAgreeingGroup(candidates, present.size());
            if (winner == null) {
                unresolved.add(entry.getKey());
                unanimous = false;
            } else {
                agreed.add(winner);
                if (candidates.size() < present.size() || !allAgree(candidates)) {
                    unanimous = false;
                }
            }
        }
        agreed.sort(Comparator.comparing(DayRead::date));
        return new Consensus(List.copyOf(agreed), List.copyOf(unresolved), unanimous);
    }

    private static boolean allAgree(List<DayRead> candidates) {
        for (int i = 1; i < candidates.size(); i++) {
            if (!dayAgrees(candidates.get(0), candidates.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The representative of the largest mutually-agreeing group, or null when
     * no two transcriptions of that day agree.
     */
    private static DayRead largestAgreeingGroup(List<DayRead> candidates, int readingCount) {
        DayRead best = null;
        int bestVotes = 1;
        for (DayRead candidate : candidates) {
            int votes = 0;
            for (DayRead other : candidates) {
                if (dayAgrees(candidate, other)) {
                    votes++;
                }
            }
            if (votes > bestVotes) {
                bestVotes = votes;
                best = candidate;
            }
        }
        // A day only one reading even mentions is not corroborated.
        return bestVotes >= 2 ? best : null;
    }

    /**
     * Two transcriptions agree about a day when they classify it the same way
     * and every measured edge lands within {@value #AGREEMENT_TOLERANCE_MINUTES}
     * minutes. Deliberately strict about COUNT: one reading seeing an extra
     * meeting is a real disagreement, not a rounding difference.
     */
    static boolean dayAgrees(DayRead a, DayRead b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.gridReadable() != b.gridReadable()
                || a.allDayBandVisible() != b.allDayBandVisible()
                || !java.util.Objects.equals(a.dayVerdict(), b.dayVerdict())) {
            return false;
        }
        List<Block> ab = a.blocks() == null ? List.of() : a.blocks();
        List<Block> bb = b.blocks() == null ? List.of() : b.blocks();
        if (ab.size() != bb.size()) {
            return false;
        }
        List<Block> as = new ArrayList<>(ab);
        List<Block> bs = new ArrayList<>(bb);
        as.sort(Comparator.comparing(Block::start).thenComparing(Block::end));
        bs.sort(Comparator.comparing(Block::start).thenComparing(Block::end));
        for (int i = 0; i < as.size(); i++) {
            if (!within(as.get(i).start(), bs.get(i).start())
                    || !within(as.get(i).end(), bs.get(i).end())) {
                return false;
            }
        }
        return true;
    }

    private static boolean within(LocalDateTime a, LocalDateTime b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Math.abs(Duration.between(a, b).toMinutes()) <= AGREEMENT_TOLERANCE_MINUTES;
    }

    /** Lowest confidence across derived intervals, for the evidence row. */
    public static BigDecimal minConfidence(List<Interval> busy) {
        BigDecimal lowest = null;
        for (Interval interval : busy) {
            lowest = lower(lowest, interval.confidence());
        }
        return lowest == null ? null : lowest.setScale(2, RoundingMode.HALF_UP);
    }

    /** The dates any transcription mentioned — the card's covered range. */
    public static Set<LocalDate> datesSeen(List<DayRead> days) {
        Set<LocalDate> dates = new HashSet<>();
        for (DayRead day : days == null ? List.<DayRead>of() : days) {
            if (day != null && day.date() != null) {
                dates.add(day.date());
            }
        }
        return dates;
    }
}
