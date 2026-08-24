package dk.trustworks.intranet.vacationservice.engine;

import dk.trustworks.intranet.vacationservice.engine.ImportBaselinePlanner.Baseline;
import dk.trustworks.intranet.vacationservice.engine.ImportBaselinePlanner.BaselineEntry;
import dk.trustworks.intranet.vacationservice.engine.ImportBaselinePlanner.Figures;
import dk.trustworks.intranet.vacationservice.engine.ImportBaselinePlanner.Row;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.PolicyRate;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntryType;
import dk.trustworks.intranet.vacationservice.model.enums.VacationImportRowStatus;
import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests (no DB) for the import planner: merging the lines Danløn
 * emits per employment record, the pool split that must happen after the
 * merge and not before, and the ordering that keeps a re-run identical.
 */
class ImportBaselinePlannerTest {

    private static final List<PolicyRate> POLICIES =
            List.of(new PolicyRate(LocalDate.of(2000, 1, 1), 2.08, 0.42));

    private static final String USER_A = "user-a";
    private static final String USER_B = "user-b";

    private static Row row(String useruuid, Object... yearsThenFigures) {
        Map<Integer, Figures> years = new LinkedHashMap<>();
        for (int i = 0; i < yearsThenFigures.length; i += 3) {
            years.put((Integer) yearsThenFigures[i],
                    new Figures((Double) yearsThenFigures[i + 1], (Double) yearsThenFigures[i + 2]));
        }
        return new Row(useruuid, VacationImportRowStatus.AUTO, years);
    }

    private static double days(List<BaselineEntry> plan, String useruuid, int ferieaar,
                               VacationPoolType pool, VacationEntryType type) {
        return plan.stream()
                .filter(e -> e.useruuid().equals(useruuid) && e.ferieaar() == ferieaar
                        && e.pool() == pool && e.type() == type)
                .mapToDouble(BaselineEntry::days)
                .reduce((a, b) -> {
                    throw new AssertionError("More than one entry for " + useruuid + " " + ferieaar
                            + " " + pool + " " + type + " — that is the duplicate key the planner exists to prevent");
                })
                .orElseThrow(() -> new AssertionError("No entry for " + useruuid + " " + ferieaar
                        + " " + pool + " " + type));
    }

    // ── The incident ──────────────────────────────────────────────────────

    /**
     * The Trustworks Technology file listed one employee on line 15 and again
     * on line 18 — two Danløn employment records, one person. Both lines
     * auto-matched to the same user and the batch died on
     * {@code uq_vacation_ledger_entries_dedup} at commit, six retries running.
     */
    @Test
    void twoLinesForOnePersonBecomeOneBaselinePerFerieaar() {
        List<Row> rows = List.of(
                row(USER_A, 2024, 4.16, 0.0, 2025, 0.0, 0.0),
                row(USER_A, 2024, 12.5, 12.5, 2025, 27.5, 8.0));

        List<Baseline> baselines = ImportBaselinePlanner.aggregate(rows);

        assertEquals(2, baselines.size(), "one baseline per ferieår, not per line");
        assertEquals(2024, baselines.get(0).ferieaar());
        assertEquals(16.66, baselines.get(0).earnedDays(), 0.001);
        assertEquals(12.5, baselines.get(0).usedDays(), 0.001);
        assertEquals(2025, baselines.get(1).ferieaar());
        assertEquals(27.5, baselines.get(1).earnedDays(), 0.001);
        assertEquals(8.0, baselines.get(1).usedDays(), 0.001);

        List<BaselineEntry> plan = ImportBaselinePlanner.plan(rows, POLICIES);

        assertEquals(8, plan.size(), "4 entries per user-year — 2 years, one user");
        assertEquals(13.86, days(plan, USER_A, 2024, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED), 0.001);
        assertEquals(12.5, days(plan, USER_A, 2024, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
        assertEquals(2.8, days(plan, USER_A, 2024, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_EARNED), 0.001);
        assertEquals(0.0, days(plan, USER_A, 2024, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
        assertEquals(22.88, days(plan, USER_A, 2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED), 0.001);
        assertEquals(8.0, days(plan, USER_A, 2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
        assertEquals(4.62, days(plan, USER_A, 2025, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_EARNED), 0.001);
        assertEquals(0.0, days(plan, USER_A, 2025, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
    }

    /**
     * Why summing has to come first. Earned days split proportionally and
     * survive being split per line; used days are charged ferie-first and
     * clamped against that line's own ferie capacity, so the small line's
     * unused ferie sits idle while the large line spills 2.10 days into
     * feriefridage it should never have touched. The totals agree either way
     * — the pool distribution is what the merge corrects.
     */
    @Test
    void splittingPerLineWouldMisplaceUsedDays() {
        Row small = row(USER_A, 2024, 4.16, 0.0);
        Row large = row(USER_A, 2024, 12.5, 12.5);

        List<BaselineEntry> perLine = ImportBaselinePlanner.plan(List.of(small), POLICIES);
        perLine = java.util.stream.Stream.concat(perLine.stream(),
                ImportBaselinePlanner.plan(List.of(large), POLICIES).stream()).toList();
        double perLineFerieUsed = perLine.stream()
                .filter(e -> e.pool() == VacationPoolType.FERIE && e.type() == VacationEntryType.IMPORT_BASELINE_USED)
                .mapToDouble(BaselineEntry::days).sum();
        double perLineFfUsed = perLine.stream()
                .filter(e -> e.pool() == VacationPoolType.FERIEFRIDAGE && e.type() == VacationEntryType.IMPORT_BASELINE_USED)
                .mapToDouble(BaselineEntry::days).sum();
        assertEquals(10.4, perLineFerieUsed, 0.001);
        assertEquals(2.1, perLineFfUsed, 0.001);

        List<BaselineEntry> merged = ImportBaselinePlanner.plan(List.of(small, large), POLICIES);
        assertEquals(12.5, days(merged, USER_A, 2024, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
        assertEquals(0.0, days(merged, USER_A, 2024, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
        assertEquals(perLineFerieUsed + perLineFfUsed,
                days(merged, USER_A, 2024, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED)
                        + days(merged, USER_A, 2024, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED),
                0.001, "the days themselves are the same — only the pool changes");
    }

    // ── Merge rules ───────────────────────────────────────────────────────

    @Test
    void aPersonOnThreeLinesStillGetsOneSet() {
        List<Row> rows = List.of(
                row(USER_A, 2025, 10.0, 2.0),
                row(USER_A, 2025, 5.5, 1.5),
                row(USER_A, 2025, 2.0, 0.0));

        List<Baseline> baselines = ImportBaselinePlanner.aggregate(rows);

        assertEquals(1, baselines.size());
        assertEquals(17.5, baselines.get(0).earnedDays(), 0.001);
        assertEquals(3.5, baselines.get(0).usedDays(), 0.001);
        assertEquals(4, ImportBaselinePlanner.plan(rows, POLICIES).size());
    }

    @Test
    void ignoredAndUnmatchableRowsContributeNothing() {
        Map<Integer, Figures> figures = Map.of(2025, new Figures(9.0, 4.0));
        List<Row> rows = List.of(
                new Row(USER_A, VacationImportRowStatus.AUTO, Map.of(2025, new Figures(6.0, 1.0))),
                new Row(USER_A, VacationImportRowStatus.IGNORED, figures),
                new Row(null, VacationImportRowStatus.UNMATCHED, figures),
                new Row("  ", VacationImportRowStatus.MANUAL, figures));

        List<Baseline> baselines = ImportBaselinePlanner.aggregate(rows);

        assertEquals(1, baselines.size());
        assertEquals(USER_A, baselines.get(0).useruuid());
        assertEquals(6.0, baselines.get(0).earnedDays(), 0.001, "the ignored line's 9 days must not be counted");
        assertEquals(1.0, baselines.get(0).usedDays(), 0.001);
    }

    /**
     * The company gate's rows must not reach a baseline. An OTHER_COMPANY line
     * is a person who has transferred: payroll moved their available balance,
     * so the receiving company's file already states the whole figure and this
     * one is a superseded record. Summing it would double-count.
     *
     * <p>UNKNOWN_COMPANY should never get this far — apply refuses while any
     * remain — so this is defence in depth, and a statement that the planner
     * does not rely on that gate holding.</p>
     */
    @Test
    void companyGatedRowsContributeNothing() {
        Map<Integer, Figures> figures = Map.of(2025, new Figures(9.0, 4.0));
        List<Row> rows = List.of(
                new Row(USER_A, VacationImportRowStatus.AUTO, Map.of(2025, new Figures(6.0, 1.0))),
                new Row(USER_A, VacationImportRowStatus.OTHER_COMPANY, figures),
                new Row(USER_A, VacationImportRowStatus.UNKNOWN_COMPANY, figures));

        List<Baseline> baselines = ImportBaselinePlanner.aggregate(rows);

        assertEquals(1, baselines.size());
        assertEquals(6.0, baselines.get(0).earnedDays(), 0.001,
                "the other company's superseded line must not be added to this company's figures");
        assertEquals(1.0, baselines.get(0).usedDays(), 0.001);
    }

    /**
     * The rule is an allow-list — AUTO and MANUAL — not a deny-list. Every
     * status outside the APPLIES bucket contributes nothing, whichever one it
     * is, so a status added later cannot default into a baseline.
     */
    @Test
    void onlyAutoAndManualEverContribute() {
        Map<Integer, Figures> figures = Map.of(2025, new Figures(9.0, 4.0));
        for (VacationImportRowStatus status : VacationImportRowStatus.values()) {
            List<Baseline> baselines = ImportBaselinePlanner.aggregate(
                    List.of(new Row(USER_A, status, figures)));
            boolean contributes = status == VacationImportRowStatus.AUTO
                    || status == VacationImportRowStatus.MANUAL;
            assertEquals(contributes ? 1 : 0, baselines.size(), status + " contributed the wrong number of baselines");
        }
    }

    /**
     * A within-company rehire is the opposite case and must still be summed:
     * the balance is not moved between the two employment records, both lines
     * sit in the same company's file, and the older one can still carry an
     * unsettled provision. The company gate leaves both AUTO, so the merge is
     * untouched.
     */
    @Test
    void aWithinCompanyRehireStillMerges() {
        List<Row> rows = List.of(
                new Row(USER_A, VacationImportRowStatus.AUTO, Map.of(2025, new Figures(4.16, 0.0))),
                new Row(USER_A, VacationImportRowStatus.AUTO, Map.of(2025, new Figures(12.5, 2.0))));

        List<Baseline> baselines = ImportBaselinePlanner.aggregate(rows);

        assertEquals(1, baselines.size());
        assertEquals(16.66, baselines.get(0).earnedDays(), 0.001);
        assertEquals(2.0, baselines.get(0).usedDays(), 0.001);
    }

    /**
     * A second employment record starting mid-year carries fewer ferieår than
     * the first, so the merged year set is the union — never the first line's.
     */
    @Test
    void disjointFerieaarSetsAreUnioned() {
        List<Row> rows = List.of(
                row(USER_A, 2025, 6.0, 2.0),
                row(USER_A, 2024, 5.0, 1.0));

        List<Baseline> baselines = ImportBaselinePlanner.aggregate(rows);

        assertEquals(2, baselines.size());
        assertEquals(2024, baselines.get(0).ferieaar(), "ferieår ascending, whichever line introduced it");
        assertEquals(5.0, baselines.get(0).earnedDays(), 0.001);
        assertEquals(2025, baselines.get(1).ferieaar());
        assertEquals(6.0, baselines.get(1).earnedDays(), 0.001);
    }

    /** A merge can cancel an overdraft — two records of one person, one over-drawn. */
    @Test
    void mergingCancelsAnOverdraftThatOnlyExistedPerLine() {
        List<BaselineEntry> perLine = ImportBaselinePlanner.plan(
                List.of(row(USER_A, 2025, 0.0, 3.0)), POLICIES);
        assertEquals(3.0, days(perLine, USER_A, 2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
        assertEquals(0.0, days(perLine, USER_A, 2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED), 0.001);

        List<BaselineEntry> merged = ImportBaselinePlanner.plan(
                List.of(row(USER_A, 2025, 0.0, 3.0), row(USER_A, 2025, 10.0, 0.0)), POLICIES);
        assertEquals(8.32, days(merged, USER_A, 2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED), 0.001);
        assertEquals(3.0, days(merged, USER_A, 2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
        assertEquals(1.68, days(merged, USER_A, 2025, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_EARNED), 0.001);
        assertEquals(0.0, days(merged, USER_A, 2025, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
    }

    // ── The ordinary file ─────────────────────────────────────────────────

    /** One line per person — the shape of every file that has ever worked. */
    @Test
    void oneLinePerPersonIsUnchanged() {
        List<Row> rows = List.of(
                row(USER_A, 2024, 33.0, 33.0, 2025, 27.5, 7.0),
                row(USER_B, 2024, 17.5, 17.5, 2025, 10.0, 10.0));

        List<Baseline> baselines = ImportBaselinePlanner.aggregate(rows);
        assertEquals(4, baselines.size());
        assertEquals(33.0, baselines.get(0).earnedDays(), 0.001);
        assertEquals(33.0, baselines.get(0).usedDays(), 0.001);

        List<BaselineEntry> plan = ImportBaselinePlanner.plan(rows, POLICIES);
        assertEquals(16, plan.size(), "2 users × 2 ferieår × 4 entries");
        assertEquals(27.46, days(plan, USER_A, 2024, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED), 0.001);
        assertEquals(27.46, days(plan, USER_A, 2024, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
        assertEquals(5.54, days(plan, USER_A, 2024, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_EARNED), 0.001);
        assertEquals(5.54, days(plan, USER_A, 2024, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
        assertEquals(7.0, days(plan, USER_A, 2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED), 0.001);
        assertEquals(8.32, days(plan, USER_B, 2025, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED), 0.001);
    }

    /** Zero stays posted: "Danløn says 0" is an override statement, not a gap. */
    @Test
    void zeroFiguresStillPost() {
        List<BaselineEntry> plan = ImportBaselinePlanner.plan(List.of(row(USER_A, 2025, 0.0, 0.0)), POLICIES);
        assertEquals(4, plan.size());
        assertTrue(plan.stream().allMatch(e -> e.days() == 0.0));
    }

    // ── Determinism ───────────────────────────────────────────────────────

    @Test
    void orderIsFirstAppearanceThenFerieaarAscending() {
        List<Row> rows = List.of(
                row(USER_B, 2025, 1.0, 0.0),
                row(USER_A, 2025, 1.0, 0.0),
                row(USER_A, 2024, 1.0, 0.0),
                row(USER_B, 2024, 1.0, 0.0));

        List<Baseline> baselines = ImportBaselinePlanner.aggregate(rows);

        assertEquals(List.of(USER_B, USER_B, USER_A, USER_A),
                baselines.stream().map(Baseline::useruuid).toList());
        assertEquals(List.of(2024, 2025, 2024, 2025),
                baselines.stream().map(Baseline::ferieaar).toList());

        List<BaselineEntry> first = ImportBaselinePlanner.plan(rows, POLICIES);
        assertEquals(first, ImportBaselinePlanner.plan(rows, POLICIES), "a re-run must post the same entries in the same order");
    }
}
