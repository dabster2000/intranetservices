package dk.trustworks.intranet.aggregates.finance.health;

import dk.trustworks.intranet.aggregates.finance.health.IntercompanyClassificationCheck.GrowthDelta;
import dk.trustworks.intranet.aggregates.finance.health.IntercompanyClassificationCheck.Misclassification;
import dk.trustworks.intranet.aggregates.finance.health.IntercompanyClassificationCheck.Snapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free regression tests for the intercompany mis-classification detector.
 *
 * <p>Covers the two measurement bugs and the restart bug, all verified against
 * production 2026-08-17:
 * <ul>
 *   <li><b>Fan-out.</b> Joining {@code finance_details} to {@code invoices} on
 *       {@code invoicenumber + companyuuid} multiplied every GL row carrying the
 *       placeholder invoice number 0 by the number of zero-numbered invoices in
 *       that company — 224 GL rows became 680, and FY25/26 read 101,344,192 DKK
 *       against a de-duplicated 29,785,107.</li>
 *   <li><b>ABS.</b> Account 1010 holds the revenue credit and its reversals.
 *       {@code SUM(ABS(amount))} added them instead of netting: 29.2M gross
 *       against a 13.1M net for the same BOOKED population.</li>
 *   <li><b>Restart.</b> The baseline lived in an {@code AtomicReference}, so
 *       every deploy re-armed the "first run, stay silent" path. Two boots on
 *       2026-08-14 recorded baselines 81,056,905 DKK apart with no alert.</li>
 * </ul>
 */
class IntercompanyClassificationCheckTest {

    // ── SQL shape ────────────────────────────────────────────────────────────

    @Test
    void detectSql_usesASemiJoin_soAGlRowIsCountedOnce() {
        String sql = IntercompanyClassificationCheck.DETECT_SQL;
        assertTrue(sql.contains("AND EXISTS (SELECT 1"),
                "the invoice test must be a semi-join; an inner JOIN fans out on invoicenumber=0");
        assertFalse(sql.contains("JOIN invoices inv\n"),
                "no inner JOIN to invoices — that is the fan-out");
        assertTrue(sql.contains("inv.cvr           = '35648941'"),
                "the identification rule still requires the invoice to be billed to Trustworks A/S");
        assertTrue(sql.contains("fd.accountnumber = 1010"),
                "the rule is about revenue landing on 1010 instead of 1040");
    }

    @Test
    void detectSql_netsReversals_ratherThanAddingThem() {
        String sql = IntercompanyClassificationCheck.DETECT_SQL;
        assertFalse(sql.contains("ABS("),
                "ABS counts a reversed invoice twice — the sum must net");
        assertTrue(sql.contains("-SUM(fd.amount) AS misposted_dkk"),
                "negated signed sum: positive DKK of revenue sitting on the wrong account");
    }

    // ── growth detection across a restart ────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<GrowthDelta> detectGrowth(Snapshot previous, Snapshot current,
                                           List<Misclassification> currentList) throws Exception {
        Method m = IntercompanyClassificationCheck.class.getDeclaredMethod(
                "detectGrowth", Snapshot.class, Snapshot.class, List.class);
        m.setAccessible(true);
        return (List<GrowthDelta>) m.invoke(new IntercompanyClassificationCheck(), previous, current, currentList);
    }

    private static Snapshot snapshot(String key, long dkk) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(key, dkk);
        return new Snapshot(m);
    }

    @Test
    void driftAcrossARestart_stillAlerts() throws Exception {
        // The baseline now comes from intercompany_classification_baseline (V502),
        // so a process restart between the two runs no longer resets it to null.
        Snapshot persistedBeforeRestart = snapshot("TECH/2026-06", 5_000_000L);

        Misclassification afterRestart = new Misclassification("TECH", 2026, 6, 93, 11_847_510.05);
        Snapshot current = snapshot("TECH/2026-06", 11_847_510L);

        List<GrowthDelta> growths = detectGrowth(persistedBeforeRestart, current, List.of(afterRestart));

        assertEquals(1, growths.size(), "drift that happened across a restart must not be swallowed");
        assertEquals(6_847_510.0, growths.get(0).deltaDkk(), 1.0);
    }

    @Test
    void steadyState_isSilent() throws Exception {
        Snapshot same = snapshot("TECH/2026-06", 11_847_510L);
        Misclassification cell = new Misclassification("TECH", 2026, 6, 93, 11_847_510.05);
        assertTrue(detectGrowth(same, same, List.of(cell)).isEmpty(),
                "an unchanged cell must not alert — accounting is already on it");
    }

    @Test
    void aShrinkingCell_isSilent() throws Exception {
        // Reclassification work reduces the cell; that is the fix landing, not drift.
        Snapshot before = snapshot("TECH/2026-06", 11_847_510L);
        Snapshot after = snapshot("TECH/2026-06", 2_000_000L);
        Misclassification cell = new Misclassification("TECH", 2026, 6, 20, 2_000_000.0);
        assertTrue(detectGrowth(before, after, List.of(cell)).isEmpty());
    }

    @Test
    void anUnseenCell_growsFromZero() throws Exception {
        Snapshot before = new Snapshot(Map.of());
        Snapshot after = snapshot("CYBER/2026-06", 3_336_957L);
        Misclassification cell = new Misclassification("CYBER", 2026, 6, 32, 3_336_957.69);
        List<GrowthDelta> growths = detectGrowth(before, after, List.of(cell));
        assertEquals(1, growths.size());
        assertEquals(3_336_957.0, growths.get(0).deltaDkk(), 1.0);
    }

    @Test
    void subKroneNoise_doesNotAlert() throws Exception {
        Snapshot before = snapshot("TECH/2026-06", 11_847_510L);
        Snapshot after = snapshot("TECH/2026-06", 11_847_510L);
        Misclassification cell = new Misclassification("TECH", 2026, 6, 93, 11_847_510.99);
        assertTrue(detectGrowth(before, after, List.of(cell)).isEmpty(),
                "rounding noise below the 1 DKK growth threshold must stay quiet");
    }
}
