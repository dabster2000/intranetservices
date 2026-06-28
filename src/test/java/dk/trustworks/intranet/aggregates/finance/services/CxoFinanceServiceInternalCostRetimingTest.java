package dk.trustworks.intranet.aggregates.finance.services;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3 — unit coverage for the internal-cost TIMING alignment mechanism in
 * {@link CxoFinanceService#getExpectedAccumulatedEBITDA}.
 *
 * <p>Internal (intercompany) revenue is bucketed on the issuer's invoicedate, but the matching
 * debtor cost (CREATED INTERNAL, GL accounts 3050/3055/3070/3075/1350) posts on the later
 * expensedate. That leaves an unmatched internal margin in the revenue month (the June year-end
 * settlement is the worst case). When the flag is ON the chart re-times CREATED-internal cost onto
 * the invoicedate month via {@link CxoFinanceService#computeInternalCostRetimingAdjustment}:
 * <pre>adjustment(m) = synth_invoicedate_CREATED(m) − gl_expensedate_CREATED(m)</pre>
 *
 * <p>The arithmetic is extracted into a package-private static method precisely so it can be asserted
 * deterministically here, without a test database (full-DB production-number assertions are not
 * feasible under the lightweight test profile). The method did not exist before the fix, so this
 * class fails to compile against the pre-fix tree — a hard pre-fix failure.
 *
 * <p>Note on the flag-OFF path: when the flag is OFF the production code never builds an adjustment
 * map at all (it stays {@code Collections.emptyMap()}) and the loop skips the {@code +=} entirely, so
 * {@code monthDirectCost} is byte-identical to today. {@link #flagOffAnalog_emptyInputs_noAdjustment()}
 * pins the equivalent no-op semantics at the unit level.
 */
class CxoFinanceServiceInternalCostRetimingTest {

    private static final String JUNE = "202606";
    private static final String JULY = "202607"; // where June's lagged GL cost typically lands

    // ------------------------------------------------------------------
    // Flag-OFF analog: no internal data ⇒ no adjustment (no-op).
    // ------------------------------------------------------------------

    @Test
    void flagOffAnalog_emptyInputs_noAdjustment() {
        Map<String, Double> adj = CxoFinanceService.computeInternalCostRetimingAdjustment(
                Map.of(), Map.of());

        assertTrue(adj.isEmpty(),
                "With no CREATED-internal cost on either timing, the adjustment must be empty — "
                + "monthDirectCost is left exactly as the GL-expensedate path computed it.");
    }

    // ------------------------------------------------------------------
    // Flag-ON: cost pulled forward onto the revenue (invoicedate) month.
    // ------------------------------------------------------------------

    @Test
    void synthOnly_noLaggedGlYet_pullsFullCostIntoInvoicedateMonth() {
        // The June internal settlement is invoiced (synth) but its debtor GL cost has not posted yet
        // (gl empty for June). The full synthesized cost must be ADDED to June's direct cost so the
        // internal revenue stops showing unmatched margin.
        Map<String, Double> synth = Map.of(JUNE, 7_690_000.0);
        Map<String, Double> gl    = Map.of();

        Map<String, Double> adj = CxoFinanceService.computeInternalCostRetimingAdjustment(synth, gl);

        assertEquals(7_690_000.0, adj.getOrDefault(JUNE, 0.0), 0.001,
                "Invoicedate-only CREATED-internal cost must be added in full to its revenue month");
    }

    @Test
    void glOnly_laggedCostInLaterMonth_isRemovedFromThatMonth() {
        // A later month carries only the lagged GL copy (the invoice's invoicedate was a prior FY/month
        // outside this map). That GL copy is already inside monthDirectCost, so the adjustment must
        // REMOVE it (negative) — the cost belongs to the earlier revenue month, not here.
        Map<String, Double> synth = Map.of();
        Map<String, Double> gl    = Map.of(JULY, 7_690_000.0);

        Map<String, Double> adj = CxoFinanceService.computeInternalCostRetimingAdjustment(synth, gl);

        assertEquals(-7_690_000.0, adj.getOrDefault(JULY, 0.0), 0.001,
                "Expensedate-only GL CREATED-internal cost must be subtracted from the lag month");
    }

    // ------------------------------------------------------------------
    // Double-count avoidance: synth and gl in the SAME month cancel.
    // ------------------------------------------------------------------

    @Test
    void synthEqualsGlSameMonth_netZero_noDoubleCount() {
        // A fully-reconciled month: the invoice was both invoiced AND booked to GL in the same month.
        // The GL copy is already inside monthDirectCost, so adding synth without subtracting gl would
        // double-count. The adjustment must net to ZERO.
        Map<String, Double> synth = Map.of(JUNE, 5_320_000.0);
        Map<String, Double> gl    = Map.of(JUNE, 5_320_000.0);

        Map<String, Double> adj = CxoFinanceService.computeInternalCostRetimingAdjustment(synth, gl);

        assertEquals(0.0, adj.getOrDefault(JUNE, 0.0), 0.001,
                "Same-month synth and GL must cancel — the cost is counted exactly once, never twice");
    }

    @Test
    void juneScenario_partialGlInJune_netIsTheUnmatchedMargin() {
        // The production June case: internal revenue 13.02M is matched by only 5.32M of in-month GL
        // cost (rest lags). synth(June)=13.02M, gl(June)=5.32M → +7.69M added to June direct cost,
        // exactly closing the unmatched internal margin the bug report quantifies.
        Map<String, Double> synth = Map.of(JUNE, 13_020_000.0);
        Map<String, Double> gl    = Map.of(JUNE,  5_320_000.0);

        Map<String, Double> adj = CxoFinanceService.computeInternalCostRetimingAdjustment(synth, gl);

        assertEquals(7_700_000.0, adj.getOrDefault(JUNE, 0.0), 0.001,
                "June adjustment must equal the unmatched internal margin (synth − in-month GL)");
    }

    // ------------------------------------------------------------------
    // FY conservation: cost MOVES between months, FY total adjustment ~0.
    // ------------------------------------------------------------------

    @Test
    void costMovedFromLagMonthToRevenueMonth_fyTotalConserved() {
        // The invoice is invoiced in June (synth) but its GL cost posts in July (gl), both inside the
        // window. Re-timing must ADD it to June and REMOVE it from July — cost moves, FY total is
        // conserved (sum of adjustments across the window is ~0).
        Map<String, Double> synth = Map.of(JUNE, 7_690_000.0);
        Map<String, Double> gl    = Map.of(JULY, 7_690_000.0);

        Map<String, Double> adj = CxoFinanceService.computeInternalCostRetimingAdjustment(synth, gl);

        assertEquals(7_690_000.0, adj.getOrDefault(JUNE, 0.0), 0.001,
                "Cost must be added to the revenue (invoicedate) month");
        assertEquals(-7_690_000.0, adj.getOrDefault(JULY, 0.0), 0.001,
                "The same cost must be removed from the lag (expensedate) month");

        double fyTotalAdjustment = adj.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(0.0, fyTotalAdjustment, 0.001,
                "When both timings are inside the window the FY total adjustment nets to zero — "
                + "the EBITDA chart only re-times cost, it never creates or destroys it.");
    }

    @Test
    void glOutsideWindow_leavesResidual_matchingClosedFyOracle() {
        // Boundary case used as the staging reconciliation oracle: an invoice invoiced inside the FY
        // whose GL cost posts in the NEXT FY (so gl is absent from this window). The residual equals
        // the synth that has no in-window GL offset — the small structural figure the closed-FY
        // validation reconciles against (FY24/25 ≈ +2.62M).
        Map<String, Double> synth = Map.of(JUNE, 2_620_000.0);
        Map<String, Double> gl    = Map.of(); // GL posts after fyToKey — outside the queried window

        Map<String, Double> adj = CxoFinanceService.computeInternalCostRetimingAdjustment(synth, gl);

        double fyTotalAdjustment = adj.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(2_620_000.0, fyTotalAdjustment, 0.001,
                "A GL posting that lands outside the window leaves exactly the synth as residual — "
                + "this is the structural closed-FY reconciliation figure, not a double count.");
    }

    @Test
    void multiMonthUnion_keysFromBothInputsAreCovered() {
        Map<String, Double> synth = Map.of(JUNE, 9_000_000.0, "202605", 1_000_000.0);
        Map<String, Double> gl    = Map.of(JULY, 4_000_000.0, "202605", 1_000_000.0);

        Map<String, Double> adj = CxoFinanceService.computeInternalCostRetimingAdjustment(synth, gl);

        assertEquals(9_000_000.0, adj.getOrDefault(JUNE, 0.0), 0.001, "synth-only month");
        assertEquals(-4_000_000.0, adj.getOrDefault(JULY, 0.0), 0.001, "gl-only month");
        assertEquals(0.0, adj.getOrDefault("202605", 0.0), 0.001, "reconciled month nets to zero");
        assertEquals(3, adj.size(),
                "Adjustment map must cover the union of months from both inputs");
    }
}
