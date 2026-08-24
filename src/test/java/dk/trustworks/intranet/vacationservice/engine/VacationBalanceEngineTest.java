package dk.trustworks.intranet.vacationservice.engine;

import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.LedgerFact;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.PolicyRate;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.PoolStatus;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.ProjectionPoint;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.Result;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.UsageFact;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.Warning;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.WarningType;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntryType;
import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests (no DB) for the balance engine: accrual, the Danløn
 * baseline supersede rules, the stamp-time usage cut, FIFO spend order,
 * transfers and the statutory warnings.
 */
class VacationBalanceEngineTest {

    private static final List<PolicyRate> POLICIES =
            List.of(new PolicyRate(LocalDate.of(2000, 1, 1), 2.08, 0.42));

    /** Full employment coverage Sep 2024 – Aug 2028. */
    private static Map<LocalDate, Double> fullCoverage() {
        Map<LocalDate, Double> coverage = new HashMap<>();
        YearMonth month = YearMonth.of(2024, 9);
        while (!month.isAfter(YearMonth.of(2028, 8))) {
            coverage.put(month.atDay(1), 1.0);
            month = month.plusMonths(1);
        }
        return coverage;
    }

    private static LedgerFact accrual(int ferieaar, VacationPoolType pool, double days, LocalDate monthEnd) {
        return new LedgerFact(ferieaar, pool, VacationEntryType.ACCRUAL, days, monthEnd,
                "accrual:" + YearMonth.from(monthEnd), LocalDateTime.of(2026, 1, 1, 4, 0));
    }

    private static List<LedgerFact> accrualRange(int ferieaar, YearMonth from, YearMonth to) {
        List<LedgerFact> facts = new ArrayList<>();
        YearMonth month = from;
        while (!month.isAfter(to)) {
            facts.add(accrual(ferieaar, VacationPoolType.FERIE, 2.08, month.atEndOfMonth()));
            facts.add(accrual(ferieaar, VacationPoolType.FERIEFRIDAGE, 0.42, month.atEndOfMonth()));
            month = month.plusMonths(1);
        }
        return facts;
    }

    private static LedgerFact baseline(int ferieaar, VacationPoolType pool, VacationEntryType type,
                                       double days, LocalDate asOf, String batch) {
        return new LedgerFact(ferieaar, pool, type, days, asOf, batch, LocalDateTime.of(2026, 8, 10, 12, 0));
    }

    private static PoolStatus pool(Result result, int ferieaar, VacationPoolType pool) {
        Optional<PoolStatus> status = result.pools().stream()
                .filter(p -> p.ferieaar == ferieaar && p.pool == pool)
                .findFirst();
        assertTrue(status.isPresent(), "Expected pool " + ferieaar + "/" + pool);
        return status.get();
    }

    // ── Accrual & projection ──────────────────────────────────────────────

    @Test
    void accrualOnly_earnedToDatePlusProjectionCoversTheFullYear() {
        // 11 posted months (Sep 25 – Jul 26); August not yet posted.
        List<LedgerFact> facts = accrualRange(2025, YearMonth.of(2025, 9), YearMonth.of(2026, 7));
        Result result = VacationBalanceEngine.compute(POLICIES, facts, List.of(), fullCoverage(),
                LocalDate.of(2026, 8, 23));

        PoolStatus ferie = pool(result, 2025, VacationPoolType.FERIE);
        assertEquals(22.88, ferie.earnedToDate(), 0.01);
        // August 2026 projected on top → the full 24.96-day statutory year.
        assertEquals(24.96, ferie.projectedEarnedTotal(), 0.01);

        PoolStatus ff = pool(result, 2025, VacationPoolType.FERIEFRIDAGE);
        assertEquals(4.62, ff.earnedToDate(), 0.01);
        assertEquals(5.04, ff.projectedEarnedTotal(), 0.01);
    }

    @Test
    void projectionOnly_newHireWithoutPostedAccrualStillProjectsByCoverage() {
        // Coverage only from March 2026 (mid-year hire); no accrual entries yet.
        Map<LocalDate, Double> coverage = new HashMap<>();
        YearMonth month = YearMonth.of(2026, 3);
        while (!month.isAfter(YearMonth.of(2027, 12))) {
            coverage.put(month.atDay(1), 1.0);
            month = month.plusMonths(1);
        }
        Result result = VacationBalanceEngine.compute(POLICIES, List.of(), List.of(), coverage,
                LocalDate.of(2026, 8, 23));

        PoolStatus ferie = pool(result, 2025, VacationPoolType.FERIE);
        assertEquals(0.0, ferie.earnedToDate(), 0.01);
        // Mar–Aug 2026 = 6 months × 2.08 — pre-hire months never project.
        assertEquals(12.48, ferie.projectedEarnedTotal(), 0.01);
    }

    // ── Usage buckets & FIFO ──────────────────────────────────────────────

    @Test
    void usage_isBucketedByStampAndDate_andSpendsOldestYearFirst() {
        List<LedgerFact> facts = accrualRange(2025, YearMonth.of(2025, 9), YearMonth.of(2026, 7));
        List<UsageFact> usage = List.of(
                new UsageFact(LocalDate.of(2026, 6, 10), 7.4, LocalDateTime.of(2026, 7, 1, 10, 0)), // stamped
                new UsageFact(LocalDate.of(2026, 8, 10), 7.4, null),                                 // awaits payroll
                new UsageFact(LocalDate.of(2026, 10, 5), 14.8, null));                               // planned, Sep–Dec overlap

        Result result = VacationBalanceEngine.compute(POLICIES, facts, usage, fullCoverage(),
                LocalDate.of(2026, 8, 23));

        PoolStatus ferie2025 = pool(result, 2025, VacationPoolType.FERIE);
        assertEquals(1.0, ferie2025.usedConfirmed, 0.01);
        assertEquals(1.0, ferie2025.usedPending, 0.01);
        // The October days sit in the Sep–Dec overlap: FIFO charges the OLD
        // year (2025) while it still has days — not the new 2026 pool.
        assertEquals(2.0, ferie2025.usedPlanned, 0.01);
        assertEquals(0.0, pool(result, 2026, VacationPoolType.FERIE).usedTotal(), 0.01);
    }

    @Test
    void usage_overflowsFerieIntoFeriefridage() {
        // Small year: 3 accrual months → 6.24 ferie + 1.26 feriefridage.
        List<LedgerFact> facts = accrualRange(2025, YearMonth.of(2025, 9), YearMonth.of(2025, 11));
        Map<LocalDate, Double> coverage = new HashMap<>();
        coverage.put(LocalDate.of(2025, 9, 1), 1.0);
        coverage.put(LocalDate.of(2025, 10, 1), 1.0);
        coverage.put(LocalDate.of(2025, 11, 1), 1.0);
        List<UsageFact> usage = List.of(new UsageFact(LocalDate.of(2025, 12, 15), 7.4 * 7, null)); // 7 days

        Result result = VacationBalanceEngine.compute(POLICIES, facts, usage, coverage,
                LocalDate.of(2025, 12, 20));

        PoolStatus ferie = pool(result, 2025, VacationPoolType.FERIE);
        PoolStatus ff = pool(result, 2025, VacationPoolType.FERIEFRIDAGE);
        assertEquals(6.24, ferie.usedPending, 0.01);
        assertEquals(0.76, ff.usedPending, 0.01);
    }

    @Test
    void usage_beyondAllPoolsBecomesOverdraftAndWarns() {
        // One accrued month, five days booked.
        List<LedgerFact> facts = List.of(
                accrual(2025, VacationPoolType.FERIE, 2.08, LocalDate.of(2025, 9, 30)));
        Map<LocalDate, Double> coverage = Map.of(LocalDate.of(2025, 9, 1), 1.0);
        List<UsageFact> usage = List.of(new UsageFact(LocalDate.of(2025, 10, 10), 7.4 * 5, null));

        Result result = VacationBalanceEngine.compute(POLICIES, facts, usage, coverage,
                LocalDate.of(2025, 10, 20));

        PoolStatus ferie = pool(result, 2025, VacationPoolType.FERIE);
        assertTrue(ferie.remainingProjected() < 0);
        assertTrue(result.warnings().stream().anyMatch(w -> w.type() == WarningType.NEGATIVE_PROJECTED));
        assertTrue(result.assignments().stream().anyMatch(VacationBalanceEngine.Assignment::overdraft));
    }

    // ── Danløn baseline supersede ─────────────────────────────────────────

    @Test
    void baseline_supersedesOlderEntriesAndOlderStamps_keepsNewerAndUnstamped() {
        LocalDate asOf = LocalDate.of(2026, 8, 10);
        List<LedgerFact> facts = new ArrayList<>(accrualRange(2025, YearMonth.of(2025, 9), YearMonth.of(2026, 7)));
        facts.add(accrual(2025, VacationPoolType.FERIE, 2.08, LocalDate.of(2026, 8, 31))); // after as-of
        facts.add(baseline(2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED, 22.92, asOf, "batch-1"));
        facts.add(baseline(2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED, 12.0, asOf, "batch-1"));
        facts.add(baseline(2025, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_EARNED, 4.58, asOf, "batch-1"));
        facts.add(baseline(2025, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED, 0.0, asOf, "batch-1"));

        List<UsageFact> usage = List.of(
                // Stamped before the as-of date → already inside "Afholdt dage 12".
                new UsageFact(LocalDate.of(2026, 5, 5), 7.4 * 12, LocalDateTime.of(2026, 6, 1, 10, 0)),
                // Stamped after the as-of date → layers on top.
                new UsageFact(LocalDate.of(2026, 8, 20), 7.4, LocalDateTime.of(2026, 9, 1, 10, 0)),
                // Never stamped, even though the date is old → Danløn never saw it.
                new UsageFact(LocalDate.of(2026, 6, 15), 7.4, null));

        Result result = VacationBalanceEngine.compute(POLICIES, facts, usage, fullCoverage(),
                LocalDate.of(2026, 9, 15));

        PoolStatus ferie = pool(result, 2025, VacationPoolType.FERIE);
        // Baseline earned + the one post-as-of accrual month; July-and-earlier accruals superseded.
        assertEquals(22.92 + 2.08, ferie.earnedToDate(), 0.01);
        // Baseline used (12) + post-as-of stamp (1); the pre-as-of stamp must NOT double count.
        assertEquals(13.0, ferie.usedConfirmed, 0.01);
        // The unstamped retro registration still deducts.
        assertEquals(1.0, ferie.usedPending, 0.01);
        assertEquals(asOf, result.usageCutoff());
    }

    @Test
    void baseline_newestBatchWinsPerFerieaar() {
        LocalDate oldAsOf = LocalDate.of(2026, 3, 1);
        LocalDate newAsOf = LocalDate.of(2026, 8, 10);
        List<LedgerFact> facts = List.of(
                baseline(2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED, 10.0, oldAsOf, "batch-old"),
                baseline(2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED, 2.0, oldAsOf, "batch-old"),
                baseline(2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED, 22.92, newAsOf, "batch-new"),
                baseline(2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED, 12.0, newAsOf, "batch-new"));

        Result result = VacationBalanceEngine.compute(POLICIES, facts, List.of(), fullCoverage(),
                LocalDate.of(2026, 9, 15));

        PoolStatus ferie = pool(result, 2025, VacationPoolType.FERIE);
        assertEquals(22.92, ferie.baselineEarned, 0.01);
        assertEquals(12.0, ferie.baselineUsed, 0.01);
    }

    // ── Transfers ─────────────────────────────────────────────────────────

    @Test
    void transfers_moveRemainingDaysBetweenYears() {
        List<LedgerFact> facts = new ArrayList<>();
        facts.add(baseline(2024, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED, 24.96,
                LocalDate.of(2025, 9, 1), "batch-1"));
        facts.add(baseline(2024, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED, 20.0,
                LocalDate.of(2025, 9, 1), "batch-1"));
        LocalDateTime created = LocalDateTime.of(2025, 12, 20, 12, 0);
        facts.add(new LedgerFact(2024, VacationPoolType.FERIE, VacationEntryType.TRANSFER_OUT, 4.0,
                LocalDate.of(2025, 12, 20), "letter-1", created));
        facts.add(new LedgerFact(2025, VacationPoolType.FERIE, VacationEntryType.TRANSFER_IN, 4.0,
                LocalDate.of(2025, 12, 20), "letter-1", created));

        Result result = VacationBalanceEngine.compute(POLICIES, facts, List.of(), fullCoverage(),
                LocalDate.of(2026, 1, 15));

        assertEquals(0.96, pool(result, 2024, VacationPoolType.FERIE).remaining(), 0.01);
        assertTrue(pool(result, 2025, VacationPoolType.FERIE).transferredIn > 3.99);
    }

    @Test
    void transferableNow_reservesTheStatutoryTwentyDays() {
        List<LedgerFact> facts = List.of(
                baseline(2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED, 25.0,
                        LocalDate.of(2026, 10, 1), "batch-1"),
                baseline(2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED, 10.0,
                        LocalDate.of(2026, 10, 1), "batch-1"),
                baseline(2025, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_EARNED, 5.0,
                        LocalDate.of(2026, 10, 1), "batch-1"),
                baseline(2025, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED, 0.0,
                        LocalDate.of(2026, 10, 1), "batch-1"));

        Result result = VacationBalanceEngine.compute(POLICIES, facts, List.of(), fullCoverage(),
                LocalDate.of(2026, 11, 15));

        // Remaining 15, but 10 of them are needed to reach the protected 20 → only 5 transferable.
        assertEquals(5.0, pool(result, 2025, VacationPoolType.FERIE).transferableNow(), 0.01);
        // Feriefridage carry over by agreement in full.
        assertEquals(5.0, pool(result, 2025, VacationPoolType.FERIEFRIDAGE).transferableNow(), 0.01);
    }

    // ── Statutory warnings ────────────────────────────────────────────────

    @Test
    void autumn_warnsAboutForfeitAndTheTransferWindow() {
        List<LedgerFact> facts = List.of(
                baseline(2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED, 25.0,
                        LocalDate.of(2026, 10, 1), "batch-1"),
                baseline(2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED, 10.0,
                        LocalDate.of(2026, 10, 1), "batch-1"));

        Result result = VacationBalanceEngine.compute(POLICIES, facts, List.of(), fullCoverage(),
                LocalDate.of(2026, 11, 15));

        Warning forfeit = result.warnings().stream()
                .filter(w -> w.type() == WarningType.FORFEIT_RISK).findFirst().orElseThrow();
        assertEquals(10.0, forfeit.days(), 0.01);
        Warning transfer = result.warnings().stream()
                .filter(w -> w.type() == WarningType.TRANSFER_WINDOW).findFirst().orElseThrow();
        assertEquals(5.0, transfer.days(), 0.01);
    }

    @Test
    void spring_flagsTheFifthWeekPayoutAndUnresolvedFeriefridage() {
        LocalDate asOf = LocalDate.of(2026, 1, 10);
        List<LedgerFact> facts = List.of(
                baseline(2024, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED, 22.92, asOf, "b"),
                baseline(2024, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED, 15.0, asOf, "b"),
                baseline(2024, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_EARNED, 4.58, asOf, "b"),
                baseline(2024, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED, 0.0, asOf, "b"));

        Result result = VacationBalanceEngine.compute(POLICIES, facts, List.of(), fullCoverage(),
                LocalDate.of(2026, 2, 15));

        Warning payout = result.warnings().stream()
                .filter(w -> w.type() == WarningType.FIFTH_WEEK_PAYOUT_DUE).findFirst().orElseThrow();
        // Leftover 7.92 minus the 5 days still inside the protected 20 → 2.92 must be paid out.
        assertEquals(2.92, payout.days(), 0.01);
        Warning ff = result.warnings().stream()
                .filter(w -> w.type() == WarningType.FERIEFRIDAGE_UNRESOLVED).findFirst().orElseThrow();
        assertEquals(4.58, ff.days(), 0.01);
    }

    // ── Projection series ─────────────────────────────────────────────────

    @Test
    void projection_dropsPlannedUsageAndExpiredPoolsFromTheSeries() {
        List<LedgerFact> facts = new ArrayList<>(accrualRange(2025, YearMonth.of(2025, 9), YearMonth.of(2026, 7)));
        List<UsageFact> usage = List.of(new UsageFact(LocalDate.of(2026, 10, 5), 7.4 * 5, null));

        Result result = VacationBalanceEngine.compute(POLICIES, facts, usage, fullCoverage(),
                LocalDate.of(2026, 8, 23));
        List<ProjectionPoint> series = VacationBalanceEngine.projection(result, LocalDate.of(2026, 8, 23), null);

        assertFalse(series.isEmpty());
        ProjectionPoint september = series.stream()
                .filter(p -> p.date().equals(LocalDate.of(2026, 9, 30))).findFirst().orElseThrow();
        ProjectionPoint november = series.stream()
                .filter(p -> p.date().equals(LocalDate.of(2026, 11, 30))).findFirst().orElseThrow();
        // Between the two points the balance drops by the five October days
        // while accrual keeps adding — the usage component must be exactly 5.
        assertEquals(5.0, september.ferieRemaining() - november.ferieRemaining()
                + accruedBetween(result, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 11, 30)), 0.05);
    }

    @Test
    void projection_reachesIntoTheNextFerieaarEvenWithNothingBookedInIt() {
        // Late August: the current ferieår has stopped earning, nothing is
        // registered after 31 Aug, and no accrual entry for the next year can
        // exist yet (the job only posts completed months). The planner must
        // still show the next year filling up.
        List<LedgerFact> facts = accrualRange(2025, YearMonth.of(2025, 9), YearMonth.of(2026, 7));
        LocalDate today = LocalDate.of(2026, 8, 24);

        Result result = VacationBalanceEngine.compute(POLICIES, facts, List.of(), fullCoverage(), today);
        List<ProjectionPoint> series = VacationBalanceEngine.projection(result, today, null);

        PoolStatus next = pool(result, 2026, VacationPoolType.FERIE);
        assertEquals(0.0, next.earnedToDate(), 0.01);
        assertEquals(24.96, next.projectedEarnedTotal(), 0.01); // 12 × 2.08
        assertFalse(VacationRules.isOpen(2026, today), "ferieår 2026 has not started on 24 Aug 2026");

        // The series runs to the end of the next year's usage window …
        assertEquals(LocalDate.of(2027, 12, 31), series.get(series.size() - 1).date());
        // … and climbs again from September instead of flat-lining: the old
        // span stopped at 31 Dec 2026 with the current year already fully
        // earned, so every month-end carried the same number.
        assertEquals(24.96, point(series, LocalDate.of(2026, 8, 31)).ferieRemaining(), 0.01);
        assertEquals(27.04, point(series, LocalDate.of(2026, 9, 30)).ferieRemaining(), 0.01);
        assertEquals(33.28, point(series, LocalDate.of(2026, 12, 31)).ferieRemaining(), 0.01);
        // 2025 expires on 31 Dec 2026; from January only the new year remains.
        assertEquals(10.40, point(series, LocalDate.of(2027, 1, 31)).ferieRemaining(), 0.01);
    }

    private static ProjectionPoint point(List<ProjectionPoint> series, LocalDate date) {
        return series.stream().filter(p -> p.date().equals(date)).findFirst()
                .orElseThrow(() -> new AssertionError("No projection point for " + date));
    }

    private static double accruedBetween(Result result, LocalDate from, LocalDate to) {
        return result.pools().stream()
                .filter(p -> p.pool == VacationPoolType.FERIE)
                .mapToDouble(p -> p.accrualProjectedByMonthEnd.subMap(from, false, to, true)
                        .values().stream().mapToDouble(Double::doubleValue).sum()
                        + p.accrualActualByMonthEnd.subMap(from, false, to, true)
                        .values().stream().mapToDouble(Double::doubleValue).sum())
                .sum();
    }

    // ── Guard rails ───────────────────────────────────────────────────────

    @Test
    void usage_beforeTheLedgerEpochIsIgnored() {
        List<UsageFact> usage = List.of(new UsageFact(LocalDate.of(2023, 7, 1), 7.4 * 5, null));
        Result result = VacationBalanceEngine.compute(POLICIES, List.of(), usage, fullCoverage(),
                LocalDate.of(2026, 8, 23));
        assertTrue(result.assignments().isEmpty());
    }
}
