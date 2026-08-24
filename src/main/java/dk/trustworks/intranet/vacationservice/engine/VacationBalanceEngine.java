package dk.trustworks.intranet.vacationservice.engine;

import dk.trustworks.intranet.vacationservice.model.enums.VacationEntryType;
import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static dk.trustworks.intranet.vacationservice.engine.VacationRules.HOURS_PER_DAY;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.LEDGER_EPOCH_FERIEAAR;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.STATUTORY_PROTECTED_DAYS;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.endOfEarning;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.endOfUse;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.ferieaarOf;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.isOpen;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.label;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.round2;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.startOf;

/**
 * Pure balance computation for one user. No I/O — everything arrives as
 * facts, which keeps the reconciliation rules unit-testable.
 *
 * <p>Model: the ledger stores facts (accrual, import baselines, transfers,
 * payouts, adjustments); consumption is derived live from timesheet usage
 * rows. A Danløn import baseline with as-of date T supersedes every ledger
 * entry dated ≤ T for the (ferieår) pairs it covers, and every
 * payroll-stamped usage row with a stamp ≤ end-of-day T — Danløn's
 * "Afholdt dage" is exactly what payroll has told it, so the cut is by stamp
 * time, not vacation date. Unstamped rows always count, which catches
 * retroactive registrations Danløn never saw.</p>
 */
public final class VacationBalanceEngine {

    private static final double EPS = 0.005;

    // ── Inputs ────────────────────────────────────────────────────────────

    public record PolicyRate(LocalDate effectiveFrom, double feriePerMonth, double feriefridagePerMonth) {
    }

    public record LedgerFact(int ferieaar, VacationPoolType pool, VacationEntryType type, double days,
                             LocalDate effectiveDate, String sourceRef, LocalDateTime createdAt) {
    }

    public record UsageFact(LocalDate registered, double hours, LocalDateTime paidOut) {
    }

    // ── Outputs ───────────────────────────────────────────────────────────

    public enum UsageBucket {CONFIRMED, PENDING, PLANNED}

    public record Assignment(int ferieaar, VacationPoolType pool, LocalDate date, double days,
                             UsageBucket bucket, boolean overdraft) {
    }

    public enum WarningType {
        /** Oct–Dec: statutory days within the protected 20 at risk of forfeit on 31 Dec. */
        FORFEIT_RISK,
        /** Oct–Dec: transferable days (5th week + feriefridage) need a written agreement before 31 Dec. */
        TRANSFER_WINDOW,
        /** Jan–Mar: untransferred 5th-week days from the closed window must be paid out with the March salary. */
        FIFTH_WEEK_PAYOUT_DUE,
        /** Jan–Mar: feriefridage left over from the closed window await a carry-over agreement or settlement. */
        FERIEFRIDAGE_UNRESOLVED,
        /** Registered/planned vacation exceeds even the projected full-year entitlement (forskudsferie). */
        NEGATIVE_PROJECTED
    }

    public record Warning(WarningType type, int ferieaar, double days, String message) {
    }

    /** Per (ferieår, pool) status. Mutable during computation, read-only after. */
    public static final class PoolStatus {
        public final int ferieaar;
        public final VacationPoolType pool;
        public double baselineEarned;
        public double baselineUsed;
        public LocalDate baselineAsOf;
        public double accrued;
        public double transferredIn;
        public double transferredOut;
        public double paidOutDays;
        public double adjustment;
        public double usedConfirmed; // baselineUsed + stamped-after-cutoff assignments
        public double usedPending;   // unstamped, registered ≤ today
        public double usedPlanned;   // unstamped, registered > today
        public final TreeMap<LocalDate, Double> accrualActualByMonthEnd = new TreeMap<>();
        public final TreeMap<LocalDate, Double> accrualProjectedByMonthEnd = new TreeMap<>();

        PoolStatus(int ferieaar, VacationPoolType pool) {
            this.ferieaar = ferieaar;
            this.pool = pool;
        }

        public double earnedToDate() {
            return round2(baselineEarned + accrued);
        }

        public double projectedFutureAccrual() {
            return round2(accrualProjectedByMonthEnd.values().stream().mapToDouble(Double::doubleValue).sum());
        }

        public double projectedEarnedTotal() {
            return round2(earnedToDate() + projectedFutureAccrual());
        }

        public double usedTotal() {
            return round2(usedConfirmed + usedPending + usedPlanned);
        }

        public double remaining() {
            return round2(baselineEarned + accrued + transferredIn - transferredOut - paidOutDays + adjustment - usedTotal());
        }

        public double remainingProjected() {
            return round2(remaining() + projectedFutureAccrual());
        }

        /**
         * Days that may legally/contractually move to the next ferieår right
         * now. FERIE reserves the statutory 20 days (they can only be taken
         * or forfeited); feriefridage carry over by agreement in full. Days
         * not yet earned are never offered.
         */
        public double transferableNow() {
            double available = Math.max(0, Math.min(remaining(), remainingProjected()));
            if (pool == VacationPoolType.FERIEFRIDAGE) {
                return round2(available);
            }
            double reserved = Math.max(0, STATUTORY_PROTECTED_DAYS - usedTotal());
            return round2(Math.max(0, available - reserved));
        }

        boolean hasActivity() {
            return Math.abs(baselineEarned) > EPS || Math.abs(baselineUsed) > EPS || Math.abs(accrued) > EPS
                    || Math.abs(transferredIn) > EPS || Math.abs(transferredOut) > EPS
                    || Math.abs(paidOutDays) > EPS || Math.abs(adjustment) > EPS
                    || Math.abs(usedTotal()) > EPS;
        }
    }

    public record Result(List<PoolStatus> pools, LocalDate usageCutoff, List<Assignment> assignments,
                         List<Warning> warnings) {
    }

    private VacationBalanceEngine() {
    }

    // ── Computation ───────────────────────────────────────────────────────

    /**
     * @param policies           accrual rates, ascending by effectiveFrom; must cover all months in play
     * @param facts              the user's ledger entries
     * @param usage              the user's VACATION-task timesheet rows (all of them; the engine applies the stamp cut)
     * @param employmentCoverage fraction of each month (keyed by first-of-month) the user is employed in an
     *                           accruing, paid status — drives projection of not-yet-posted accrual
     * @param today              evaluation date
     */
    public static Result compute(List<PolicyRate> policies,
                                 List<LedgerFact> facts,
                                 List<UsageFact> usage,
                                 Map<LocalDate, Double> employmentCoverage,
                                 LocalDate today) {

        // 1) Select the authoritative baseline per ferieår: the batch (source_ref)
        //    with the latest as-of date wins; ties fall to the latest upload.
        Map<Integer, List<LedgerFact>> selectedBaseline = new HashMap<>();
        Map<Integer, LocalDate> baselineCutoff = new HashMap<>();
        facts.stream()
                .filter(f -> f.type() == VacationEntryType.IMPORT_BASELINE_EARNED
                        || f.type() == VacationEntryType.IMPORT_BASELINE_USED)
                .collect(java.util.stream.Collectors.groupingBy(LedgerFact::ferieaar))
                .forEach((year, yearFacts) -> {
                    Map<String, List<LedgerFact>> byRef = new LinkedHashMap<>();
                    yearFacts.forEach(f -> byRef.computeIfAbsent(f.sourceRef(), k -> new ArrayList<>()).add(f));
                    List<LedgerFact> winner = byRef.values().stream()
                            .max(Comparator
                                    .comparing((List<LedgerFact> l) -> l.get(0).effectiveDate())
                                    .thenComparing(l -> l.get(0).createdAt() != null ? l.get(0).createdAt() : LocalDateTime.MIN))
                            .orElse(List.of());
                    if (!winner.isEmpty()) {
                        selectedBaseline.put(year, winner);
                        baselineCutoff.put(year, winner.get(0).effectiveDate());
                    }
                });
        LocalDate usageCutoff = baselineCutoff.values().stream().max(Comparator.naturalOrder()).orElse(null);

        // 2) The ferieår span to model.
        int currentYear = ferieaarOf(today);
        int minYear = currentYear;
        int maxYear = currentYear;
        for (LedgerFact f : facts) {
            minYear = Math.min(minYear, f.ferieaar());
            maxYear = Math.max(maxYear, f.ferieaar());
        }
        for (UsageFact u : usage) {
            if (u.registered().isBefore(startOf(LEDGER_EPOCH_FERIEAAR))) continue;
            int y = ferieaarOf(u.registered());
            minYear = Math.min(minYear, y);
            maxYear = Math.max(maxYear, y);
        }
        minYear = Math.max(minYear, LEDGER_EPOCH_FERIEAAR - 2); // sanity bound; epoch minus transfers-in headroom
        Map<Integer, Map<VacationPoolType, PoolStatus>> pools = new TreeMap<>();
        for (int y = minYear; y <= maxYear; y++) {
            Map<VacationPoolType, PoolStatus> byPool = new LinkedHashMap<>();
            byPool.put(VacationPoolType.FERIE, new PoolStatus(y, VacationPoolType.FERIE));
            byPool.put(VacationPoolType.FERIEFRIDAGE, new PoolStatus(y, VacationPoolType.FERIEFRIDAGE));
            pools.put(y, byPool);
        }

        // 3) Baselines.
        selectedBaseline.forEach((year, winners) -> winners.forEach(f -> {
            PoolStatus pool = pools.get(year).get(f.pool());
            pool.baselineAsOf = f.effectiveDate();
            if (f.type() == VacationEntryType.IMPORT_BASELINE_EARNED) pool.baselineEarned += f.days();
            else pool.baselineUsed += f.days();
        }));

        // 4) Every other ledger fact — superseded when a baseline for its
        //    ferieår carries an as-of date at or after its effective date.
        for (LedgerFact f : facts) {
            if (f.type() == VacationEntryType.IMPORT_BASELINE_EARNED
                    || f.type() == VacationEntryType.IMPORT_BASELINE_USED) continue;
            Map<VacationPoolType, PoolStatus> byPool = pools.get(f.ferieaar());
            if (byPool == null) continue;
            LocalDate cutoff = baselineCutoff.get(f.ferieaar());
            if (cutoff != null && !f.effectiveDate().isAfter(cutoff)) continue;
            PoolStatus pool = byPool.get(f.pool());
            switch (f.type()) {
                case ACCRUAL -> {
                    if (!f.effectiveDate().isAfter(today)) {
                        pool.accrued += f.days();
                        pool.accrualActualByMonthEnd.merge(f.effectiveDate(), f.days(), Double::sum);
                    }
                }
                case TRANSFER_IN -> pool.transferredIn += f.days();
                case TRANSFER_OUT -> pool.transferredOut += f.days();
                case PAYOUT -> pool.paidOutDays += f.days();
                case ADJUSTMENT -> pool.adjustment += f.days();
                default -> { /* baselines handled above */ }
            }
        }

        // 5) Projected accrual for months not yet covered by a baseline or a
        //    posted ACCRUAL entry, weighted by employment coverage.
        for (Map<VacationPoolType, PoolStatus> byPool : pools.values()) {
            for (PoolStatus pool : byPool.values()) {
                LocalDate coveredThrough = pool.accrualActualByMonthEnd.isEmpty()
                        ? baselineCutoff.get(pool.ferieaar)
                        : max(baselineCutoff.get(pool.ferieaar), pool.accrualActualByMonthEnd.lastKey());
                YearMonth month = YearMonth.from(startOf(pool.ferieaar));
                YearMonth lastMonth = YearMonth.from(endOfEarning(pool.ferieaar));
                while (!month.isAfter(lastMonth)) {
                    LocalDate monthEnd = month.atEndOfMonth();
                    if (coveredThrough == null || monthEnd.isAfter(coveredThrough)) {
                        double coverage = employmentCoverage.getOrDefault(month.atDay(1), 0.0);
                        if (coverage > EPS) {
                            double rate = rateFor(policies, pool.pool, monthEnd);
                            double days = round2(rate * coverage);
                            if (days > EPS) pool.accrualProjectedByMonthEnd.put(monthEnd, days);
                        }
                    }
                    month = month.plusMonths(1);
                }
            }
        }

        // 6) Usage: stamp-time cut, then chronological FIFO assignment —
        //    oldest open ferieår first, FERIE before FERIEFRIDAGE. Capacity is
        //    the projected full-year entitlement (samtidighedsferie lets days
        //    be booked ahead of accrual within the year).
        Map<PoolStatus, Double> available = new HashMap<>();
        for (Map<VacationPoolType, PoolStatus> byPool : pools.values()) {
            for (PoolStatus pool : byPool.values()) {
                available.put(pool, pool.baselineEarned + pool.accrued + pool.projectedFutureAccrual()
                        + pool.transferredIn - pool.transferredOut - pool.paidOutDays + pool.adjustment
                        - pool.baselineUsed);
            }
        }
        for (Map<VacationPoolType, PoolStatus> byPool : pools.values()) {
            PoolStatus feriePool = byPool.get(VacationPoolType.FERIE);
            feriePool.usedConfirmed += feriePool.baselineUsed;
            PoolStatus ffPool = byPool.get(VacationPoolType.FERIEFRIDAGE);
            ffPool.usedConfirmed += ffPool.baselineUsed;
        }

        LocalDateTime stampCut = usageCutoff == null ? null : usageCutoff.atTime(LocalTime.MAX);
        List<Assignment> assignments = new ArrayList<>();
        List<UsageFact> effectiveUsage = usage.stream()
                .filter(u -> !u.registered().isBefore(startOf(LEDGER_EPOCH_FERIEAAR)))
                .filter(u -> u.paidOut() == null || stampCut == null || u.paidOut().isAfter(stampCut))
                .sorted(Comparator.comparing(UsageFact::registered))
                .toList();

        for (UsageFact u : effectiveUsage) {
            double remainingDays = u.hours() / HOURS_PER_DAY;
            if (remainingDays <= EPS) continue;
            UsageBucket bucket = u.paidOut() != null ? UsageBucket.CONFIRMED
                    : (u.registered().isAfter(today) ? UsageBucket.PLANNED : UsageBucket.PENDING);

            for (Map.Entry<Integer, Map<VacationPoolType, PoolStatus>> yearEntry : pools.entrySet()) {
                if (remainingDays <= EPS) break;
                if (!isOpen(yearEntry.getKey(), u.registered())) continue;
                for (PoolStatus pool : yearEntry.getValue().values()) {
                    if (remainingDays <= EPS) break;
                    double take = Math.min(remainingDays, Math.max(0, available.get(pool)));
                    if (take <= EPS) continue;
                    applyUsage(pool, bucket, take);
                    available.merge(pool, -take, Double::sum);
                    assignments.add(new Assignment(pool.ferieaar, pool.pool, u.registered(), round2(take), bucket, false));
                    remainingDays -= take;
                }
            }
            if (remainingDays > EPS) {
                // Overdraft: charge the ferieår the date belongs to — the
                // projection surfaces it as forskudsferie instead of dropping it.
                int year = ferieaarOf(u.registered());
                PoolStatus pool = pools.computeIfAbsent(year, y -> {
                    Map<VacationPoolType, PoolStatus> byPool = new LinkedHashMap<>();
                    byPool.put(VacationPoolType.FERIE, new PoolStatus(y, VacationPoolType.FERIE));
                    byPool.put(VacationPoolType.FERIEFRIDAGE, new PoolStatus(y, VacationPoolType.FERIEFRIDAGE));
                    return byPool;
                }).get(VacationPoolType.FERIE);
                applyUsage(pool, bucket, remainingDays);
                available.merge(pool, -remainingDays, Double::sum);
                assignments.add(new Assignment(pool.ferieaar, pool.pool, u.registered(), round2(remainingDays), bucket, true));
            }
        }

        // 7) Prune quiet closed years, round, and collect. Current and future
        //    years always stay — the planner needs their projection even when
        //    nothing is booked yet.
        List<PoolStatus> result = new ArrayList<>();
        for (Map<VacationPoolType, PoolStatus> byPool : pools.values()) {
            int year = byPool.values().iterator().next().ferieaar;
            boolean keepYear = byPool.values().stream().anyMatch(PoolStatus::hasActivity)
                    || isOpen(year, today)
                    || startOf(year).isAfter(today);
            if (!keepYear) continue;
            for (PoolStatus pool : byPool.values()) {
                pool.baselineEarned = round2(pool.baselineEarned);
                pool.baselineUsed = round2(pool.baselineUsed);
                pool.accrued = round2(pool.accrued);
                pool.transferredIn = round2(pool.transferredIn);
                pool.transferredOut = round2(pool.transferredOut);
                pool.paidOutDays = round2(pool.paidOutDays);
                pool.adjustment = round2(pool.adjustment);
                pool.usedConfirmed = round2(pool.usedConfirmed);
                pool.usedPending = round2(pool.usedPending);
                pool.usedPlanned = round2(pool.usedPlanned);
                result.add(pool);
            }
        }

        return new Result(result, usageCutoff, assignments, warnings(result, today));
    }

    private static void applyUsage(PoolStatus pool, UsageBucket bucket, double days) {
        switch (bucket) {
            case CONFIRMED -> pool.usedConfirmed += days;
            case PENDING -> pool.usedPending += days;
            case PLANNED -> pool.usedPlanned += days;
        }
    }

    // ── Warnings ──────────────────────────────────────────────────────────

    private static List<Warning> warnings(List<PoolStatus> pools, LocalDate today) {
        List<Warning> warnings = new ArrayList<>();

        for (PoolStatus pool : pools) {
            // Forskudsferie: booked beyond even the projected entitlement.
            if (isOpen(pool.ferieaar, today) && pool.remainingProjected() < -EPS) {
                warnings.add(new Warning(WarningType.NEGATIVE_PROJECTED, pool.ferieaar,
                        round2(-pool.remainingProjected()),
                        (pool.pool == VacationPoolType.FERIE ? "Ferie" : "Feriefridage")
                                + " for ferieår " + label(pool.ferieaar)
                                + " er overtegnet med " + round2(-pool.remainingProjected())
                                + " dage — forskudsferie kræver en aftale"));
            }
        }

        // The ferieår whose usage window ends 31 Dec this year.
        int closingYear = today.getYear() - 1;
        boolean inAutumnWindow = today.getMonthValue() >= 10 && !today.isAfter(endOfUse(closingYear));
        if (inAutumnWindow) {
            for (PoolStatus pool : pools) {
                if (pool.ferieaar != closingYear) continue;
                if (pool.pool == VacationPoolType.FERIE) {
                    double atRisk = round2(Math.min(Math.max(0, pool.remaining()),
                            Math.max(0, STATUTORY_PROTECTED_DAYS - pool.usedTotal())));
                    if (atRisk > EPS) {
                        warnings.add(new Warning(WarningType.FORFEIT_RISK, closingYear, atRisk,
                                atRisk + " feriedage fra ferieår " + label(closingYear)
                                        + " bortfalder 31. december, hvis de ikke afholdes"));
                    }
                }
                double transferable = pool.transferableNow();
                if (transferable > EPS) {
                    warnings.add(new Warning(WarningType.TRANSFER_WINDOW, closingYear, transferable,
                            transferable + (pool.pool == VacationPoolType.FERIE
                                    ? " feriedage (5. ferieuge) kan overføres — aftalen skal indgås senest 31. december"
                                    : " feriefridage kan overføres efter aftale inden 31. december")));
                }
            }
        }

        // Jan–Mar: the window that closed on 31 Dec must be settled.
        if (today.getMonthValue() <= 3) {
            int settledYear = today.getYear() - 2;
            for (PoolStatus pool : pools) {
                if (pool.ferieaar != settledYear) continue;
                double leftover = Math.max(0, pool.remaining());
                if (leftover <= EPS) continue;
                if (pool.pool == VacationPoolType.FERIE) {
                    double payoutDue = round2(Math.max(0, leftover - Math.max(0, STATUTORY_PROTECTED_DAYS - pool.usedTotal())));
                    if (payoutDue > EPS) {
                        warnings.add(new Warning(WarningType.FIFTH_WEEK_PAYOUT_DUE, settledYear, payoutDue,
                                payoutDue + " feriedage ud over 4 uger fra ferieår " + label(settledYear)
                                        + " skal udbetales senest med marts-lønnen"));
                    }
                } else {
                    warnings.add(new Warning(WarningType.FERIEFRIDAGE_UNRESOLVED, settledYear, round2(leftover),
                            round2(leftover) + " feriefridage fra ferieår " + label(settledYear)
                                    + " mangler en overførselsaftale eller afregning"));
                }
            }
        }
        return warnings;
    }

    // ── Projection ────────────────────────────────────────────────────────

    public record ProjectionPoint(LocalDate date, double ferieRemaining, double feriefridageRemaining) {
    }

    /**
     * Month-end series of projected remaining days from today until the last
     * open usage window closes (or {@code until}, whichever is first).
     * Expired pools drop out of the sum at their window end.
     */
    public static List<ProjectionPoint> projection(Result result, LocalDate today, LocalDate until) {
        LocalDate horizon = result.pools().stream()
                .filter(p -> isOpen(p.ferieaar, today) || startOf(p.ferieaar).isAfter(today))
                .map(p -> endOfUse(p.ferieaar))
                .max(Comparator.naturalOrder())
                .orElse(today);
        if (until != null && until.isBefore(horizon)) horizon = until;

        Map<PoolStatus, TreeMap<LocalDate, Double>> usageByPool = new HashMap<>();
        for (Assignment a : result.assignments()) {
            result.pools().stream()
                    .filter(p -> p.ferieaar == a.ferieaar() && p.pool == a.pool())
                    .findFirst()
                    .ifPresent(pool -> usageByPool.computeIfAbsent(pool, k -> new TreeMap<>())
                            .merge(a.date(), a.days(), Double::sum));
        }

        List<ProjectionPoint> points = new ArrayList<>();
        YearMonth month = YearMonth.from(today);
        while (!month.atDay(1).isAfter(horizon)) {
            LocalDate d = month.atEndOfMonth().isAfter(horizon) ? horizon : month.atEndOfMonth();
            double ferie = 0;
            double ff = 0;
            for (PoolStatus pool : result.pools()) {
                if (d.isAfter(endOfUse(pool.ferieaar)) || d.isBefore(startOf(pool.ferieaar))) continue;
                double earned = pool.baselineEarned
                        + sumThrough(pool.accrualActualByMonthEnd, d)
                        + sumThrough(pool.accrualProjectedByMonthEnd, d);
                double used = pool.baselineUsed
                        + sumThrough(usageByPool.getOrDefault(pool, new TreeMap<>()), d);
                double value = earned + pool.transferredIn - pool.transferredOut - pool.paidOutDays
                        + pool.adjustment - used;
                if (pool.pool == VacationPoolType.FERIE) ferie += value;
                else ff += value;
            }
            points.add(new ProjectionPoint(d, round2(ferie), round2(ff)));
            month = month.plusMonths(1);
        }
        return points;
    }

    private static double sumThrough(TreeMap<LocalDate, Double> byDate, LocalDate through) {
        return byDate.headMap(through, true).values().stream().mapToDouble(Double::doubleValue).sum();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    public static double rateFor(List<PolicyRate> policies, VacationPoolType pool, LocalDate date) {
        PolicyRate applicable = null;
        for (PolicyRate rate : policies) {
            if (!rate.effectiveFrom().isAfter(date)) applicable = rate;
            else break;
        }
        if (applicable == null) {
            throw new IllegalStateException("No vacation policy covers " + date);
        }
        return pool == VacationPoolType.FERIE ? applicable.feriePerMonth() : applicable.feriefridagePerMonth();
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
