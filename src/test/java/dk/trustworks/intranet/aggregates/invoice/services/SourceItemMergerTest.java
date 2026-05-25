package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.enums.AttributionSource;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-function tests covering the persisted-wins merge rule and null-key safety.
 * No CDI, no DB, no Quarkus — direct invocation of {@link SourceItemMerger#merge}.
 *
 * <p>Covers spec §6.4 acceptance criteria 6 and 7.
 */
class SourceItemMergerTest {

    // ── helpers ────────────────────────────────────────────────────────────

    private static InvoiceItem base(String name, double rate, double hours) {
        InvoiceItem ii = new InvoiceItem(name, "desc", rate, hours, "src-invoice");
        ii.origin = InvoiceItemOrigin.BASE;
        return ii;
    }

    private static InvoiceItem calculated(String name, double rate, String ruleId, String calcRef) {
        InvoiceItem ii = new InvoiceItem(name, "desc", rate, 1.0, "src-invoice");
        ii.origin = InvoiceItemOrigin.CALCULATED;
        ii.ruleId = ruleId;
        ii.calculationRef = calcRef;
        ii.label = name;
        return ii;
    }

    // ── AC11(a): pure BASE source — synthetics all included ───────────────

    @Test
    void merge_noPersistedCalculated_includesAllSynthetics() {
        InvoiceItem base = base("Mary", 1400.0, 51.0);
        InvoiceItem syn1 = calculated("MSP fee", -100.0, "novo-msp-1.8", "calc-1");
        InvoiceItem syn2 = calculated("Discount", -250.0, "general-disc", "calc-2");

        List<InvoiceItem> out = SourceItemMerger.merge(List.of(base), List.of(syn1, syn2));

        assertEquals(3, out.size());
        assertSame(base, out.get(0));
        assertSame(syn1, out.get(1));
        assertSame(syn2, out.get(2));
    }

    // ── AC11(b): persisted CALCULATED with same ruleId — synthetic dropped ─

    @Test
    void merge_persistedCalculatedMatchesSyntheticRuleId_persistedWins() {
        InvoiceItem base = base("Mary", 1400.0, 51.0);
        InvoiceItem persistedCalc = calculated("MSP fee (frozen)", -120.0, "novo-msp-1.8", "calc-original");
        InvoiceItem syntheticCalc = calculated("MSP fee (recomputed)", -100.0, "novo-msp-1.8", "calc-fresh");

        List<InvoiceItem> out = SourceItemMerger.merge(List.of(base, persistedCalc), List.of(syntheticCalc));

        assertEquals(2, out.size());
        assertSame(base, out.get(0));
        assertSame(persistedCalc, out.get(1));
    }

    // ── AC11(b)': fallback on calculationRef when ruleId is null ──────────

    @Test
    void merge_persistedCalcRefMatchesSyntheticCalcRef_persistedWins() {
        InvoiceItem persistedCalc = calculated("Old-style discount", -50.0, null, "shared-calc-ref");
        InvoiceItem syntheticCalc = calculated("New-style discount", -75.0, null, "shared-calc-ref");

        List<InvoiceItem> out = SourceItemMerger.merge(List.of(persistedCalc), List.of(syntheticCalc));

        assertEquals(1, out.size());
        assertSame(persistedCalc, out.get(0));
    }

    // ── AC11(c): null-null collision still defers to persisted ────────────

    @Test
    void merge_persistedNullKeyMatchesSyntheticNullKey_persistedWins() {
        InvoiceItem persistedCalc = calculated("Legacy CALCULATED row", -30.0, null, null);
        InvoiceItem syntheticCalc = calculated("Synthetic without keys", -45.0, null, null);

        List<InvoiceItem> out = SourceItemMerger.merge(List.of(persistedCalc), List.of(syntheticCalc));

        assertEquals(1, out.size(), "null-key persisted CALCULATED should suppress null-key synthetic");
        assertSame(persistedCalc, out.get(0));
    }

    // ── AC11(d): BASE-only source — no change ─────────────────────────────

    @Test
    void merge_baseOnlySource_returnsBaseOnly() {
        InvoiceItem b1 = base("Mary", 1400.0, 51.0);
        InvoiceItem b2 = base("Joe", 1500.0, 20.0);

        List<InvoiceItem> out = SourceItemMerger.merge(List.of(b1, b2), List.of());

        assertEquals(2, out.size());
        assertSame(b1, out.get(0));
        assertSame(b2, out.get(1));
    }

    // ── Defensive null handling ──────────────────────────────────────────

    @Test
    void merge_bothNull_returnsEmpty() {
        List<InvoiceItem> out = SourceItemMerger.merge(null, null);
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void merge_nullPersisted_returnsAllSynthetics() {
        InvoiceItem syn = calculated("MSP fee", -100.0, "novo-msp-1.8", "calc-1");
        List<InvoiceItem> out = SourceItemMerger.merge(null, List.of(syn));
        assertEquals(1, out.size());
        assertSame(syn, out.get(0));
    }

    @Test
    void merge_nullSynthetic_returnsAllPersisted() {
        InvoiceItem base = base("Mary", 1400.0, 51.0);
        List<InvoiceItem> out = SourceItemMerger.merge(List.of(base), null);
        assertEquals(1, out.size());
        assertSame(base, out.get(0));
    }

    // ── Sanity: persisted BASE never blocks a synthetic ──────────────────

    @Test
    void merge_persistedBaseDoesNotBlockSynthetic_evenWithSameNullKeyShape() {
        InvoiceItem base = base("Mary", 1400.0, 51.0); // BASE, ruleId/calcRef null
        InvoiceItem syn = calculated("MSP fee", -100.0, null, null);

        List<InvoiceItem> out = SourceItemMerger.merge(List.of(base), List.of(syn));

        assertEquals(2, out.size(), "BASE items don't contribute to the dedup set");
        assertSame(base, out.get(0));
        assertSame(syn, out.get(1));
    }

    // ── synthesizeAttributionsFor ────────────────────────────────────────

    private static InvoiceItemAttribution attr(String invoiceitemUuid, String consultantUuid,
                                                double sharePct, double amount) {
        return new InvoiceItemAttribution(
                invoiceitemUuid,
                consultantUuid,
                BigDecimal.valueOf(sharePct),
                BigDecimal.valueOf(amount),
                BigDecimal.ZERO,
                AttributionSource.AUTO);
    }

    @Test
    void synthesize_singleConsultant100Pct_producesOneAttributionPerSyntheticItem() {
        InvoiceItem base = base("Stephan Jensen", 1400.0, 51.0);
        // Manually override UUID for assertion clarity.
        base.uuid = "base-1";
        InvoiceItem syn = calculated("general-discount-1.8%", -1285.0, "general-discount-1.8pct", "calc-fresh");

        InvoiceItemAttribution baseAttr = attr("base-1", "stephan-uuid", 100.0, 71400.0);

        List<InvoiceItemAttribution> synthesized = SourceItemMerger.synthesizeAttributionsFor(
                List.of(syn), List.of(baseAttr), Set.of("base-1"));

        assertEquals(1, synthesized.size());
        InvoiceItemAttribution s = synthesized.get(0);
        assertEquals(syn.uuid, s.invoiceitemUuid, "must reference the synthetic CALCULATED item's UUID");
        assertEquals("stephan-uuid", s.consultantUuid);
        // 100% share — 71400/71400 × 100 = 100 (HALF_UP @ scale 4 = 100.0000)
        assertEquals(0, s.sharePct.compareTo(BigDecimal.valueOf(100)),
                "single-consultant should get 100% share — got " + s.sharePct);
    }

    @Test
    void synthesize_twoConsultantsSplit_dividesShareProportionally() {
        InvoiceItem b1 = base("A", 1000.0, 10.0); // amount=10000
        b1.uuid = "base-A";
        InvoiceItem b2 = base("B", 1000.0, 30.0); // amount=30000
        b2.uuid = "base-B";
        InvoiceItem syn = calculated("Discount", -400.0, "general-discount", "calc-fresh");

        InvoiceItemAttribution a1 = attr("base-A", "consultant-1", 100.0, 10000.0);
        InvoiceItemAttribution a2 = attr("base-B", "consultant-2", 100.0, 30000.0);

        List<InvoiceItemAttribution> synthesized = SourceItemMerger.synthesizeAttributionsFor(
                List.of(syn), List.of(a1, a2), Set.of("base-A", "base-B"));

        assertEquals(2, synthesized.size());
        // consultant-1: 10000/40000 = 25%
        // consultant-2: 30000/40000 = 75%
        InvoiceItemAttribution c1 = synthesized.stream()
                .filter(s -> "consultant-1".equals(s.consultantUuid)).findFirst().orElseThrow();
        InvoiceItemAttribution c2 = synthesized.stream()
                .filter(s -> "consultant-2".equals(s.consultantUuid)).findFirst().orElseThrow();
        assertEquals(0, c1.sharePct.compareTo(new BigDecimal("25.0000")));
        assertEquals(0, c2.sharePct.compareTo(new BigDecimal("75.0000")));
    }

    @Test
    void synthesize_excludesAttributionsForPersistedCalculatedItems() {
        InvoiceItem persistedBase = base("X", 1000.0, 10.0); // amount=10000
        persistedBase.uuid = "base-X";
        InvoiceItem persistedCalc = calculated("FrozenDiscount", -200.0, "rule-A", "calc-A");
        persistedCalc.uuid = "calc-X";
        InvoiceItem syn = calculated("AnotherDiscount", -100.0, "rule-B", "calc-fresh");

        // baseItemUuids includes ONLY the persisted BASE — calc-X is excluded by the
        // caller per spec (only BASE-item attributions drive the share derivation).
        InvoiceItemAttribution baseAttr = attr("base-X", "consultant-1", 100.0, 10000.0);
        InvoiceItemAttribution calcAttr = attr("calc-X", "consultant-2", 100.0, -200.0);

        List<InvoiceItemAttribution> synthesized = SourceItemMerger.synthesizeAttributionsFor(
                List.of(syn), List.of(baseAttr, calcAttr), Set.of("base-X"));

        // consultant-2 has an attribution row, but it points to the persisted CALCULATED
        // (which is NOT in baseItemUuids) — so consultant-2 should be excluded.
        assertEquals(1, synthesized.size());
        assertEquals("consultant-1", synthesized.get(0).consultantUuid);
    }

    @Test
    void synthesize_emptySyntheticList_returnsEmpty() {
        List<InvoiceItemAttribution> out = SourceItemMerger.synthesizeAttributionsFor(
                List.of(), List.of(attr("base", "c1", 100.0, 100.0)), Set.of("base"));
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void synthesize_emptyBaseAttributions_returnsEmpty() {
        InvoiceItem syn = calculated("Discount", -100.0, "rule", "calc");
        List<InvoiceItemAttribution> out = SourceItemMerger.synthesizeAttributionsFor(
                List.of(syn), List.of(), Set.of("base"));
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void synthesize_zeroTotalBaseAmount_returnsEmpty() {
        InvoiceItem syn = calculated("Discount", -100.0, "rule", "calc");
        InvoiceItemAttribution zeroAttr = attr("base-Z", "c1", 100.0, 0.0);
        List<InvoiceItemAttribution> out = SourceItemMerger.synthesizeAttributionsFor(
                List.of(syn), List.of(zeroAttr), Set.of("base-Z"));
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    // ── End-to-end (merge → synthesize → generate) — concretizes AC6 ─────

    /**
     * AC6 end-to-end: 1 BASE item (Stephan, 51h × 1400, 100% cross-company-attributed
     * to issuer X) + 1 synthetic CALCULATED item (-1285 kr, ruleId=general-discount-1.8pct).
     * Expected output from {@link InternalInvoiceLineGenerator#generate}: a BASE line
     * for issuer X (51h × 1400) AND a CALCULATED line for issuer X (rate=-1285, hours=1).
     *
     * <p>Without the synthetic-attribution synthesis the CALCULATED line is silently
     * dropped — this test fails fast if the fix regresses.
     */
    @Test
    void mergeThenSynthesizeThenGenerate_producesBaseAndCalculatedInternalLines() {
        // ── given ────────────────────────────────────────────────────────
        String sourceCompanyUuid = "company-debtor";
        String issuerCompanyUuid = "company-issuer-x";
        String stephanUuid = "stephan-jensen";

        InvoiceItem persistedBase = base("Stephan Jensen", 1400.0, 51.0);
        persistedBase.uuid = "base-stephan";
        InvoiceItem syntheticCalc = calculated("general-discount-1.8%",
                -1285.0, "general-discount-1.8pct", "calc-fresh");
        // synthetic item gets a fresh UUID from the no-arg constructor — leave it.

        InvoiceItemAttribution baseAttr = attr(
                persistedBase.uuid, stephanUuid, 100.0, 51.0 * 1400.0);

        // ── when (merge) ─────────────────────────────────────────────────
        List<InvoiceItem> merged = SourceItemMerger.merge(
                List.of(persistedBase), List.of(syntheticCalc));
        assertEquals(2, merged.size(), "merger must keep BASE and add synthetic CALCULATED");

        // ── when (synthesize in-memory attributions for synthetic CALCULATED) ──
        Set<String> persistedItemUuids = new HashSet<>();
        Set<String> baseItemUuids = new HashSet<>();
        persistedItemUuids.add(persistedBase.uuid);
        baseItemUuids.add(persistedBase.uuid);

        List<InvoiceItem> syntheticOnly = new java.util.ArrayList<>();
        for (InvoiceItem item : merged) {
            if (item.origin == InvoiceItemOrigin.CALCULATED && !persistedItemUuids.contains(item.uuid)) {
                syntheticOnly.add(item);
            }
        }
        List<InvoiceItemAttribution> syntheticAttrs = SourceItemMerger.synthesizeAttributionsFor(
                syntheticOnly, List.of(baseAttr), baseItemUuids);

        assertEquals(1, syntheticAttrs.size(),
                "expected one synthetic attribution for stephan on the synthetic CALCULATED");
        assertEquals(syntheticCalc.uuid, syntheticAttrs.get(0).invoiceitemUuid,
                "synthetic attribution must reference the synthetic CALCULATED item's UUID");

        List<InvoiceItemAttribution> effectiveAttributions = new java.util.ArrayList<>();
        effectiveAttributions.add(baseAttr);
        effectiveAttributions.addAll(syntheticAttrs);

        // ── when (generate internal lines) ───────────────────────────────
        Map<String, String> userCompanies = Map.of(stephanUuid, issuerCompanyUuid);
        Map<String, List<InvoiceItem>> grouped = InternalInvoiceLineGenerator.generate(
                sourceCompanyUuid, merged, effectiveAttributions, userCompanies);

        // ── then (issuer X must have BOTH a BASE and a CALCULATED line) ──
        assertTrue(grouped.containsKey(issuerCompanyUuid),
                "expected issuer-X to receive lines, got: " + grouped.keySet());
        List<InvoiceItem> issuerLines = grouped.get(issuerCompanyUuid);
        assertEquals(2, issuerLines.size(),
                "expected exactly 2 lines for issuer X (1 BASE + 1 CALCULATED), got: " + issuerLines.size());

        boolean hasBase = false;
        boolean hasCalc = false;
        for (InvoiceItem line : issuerLines) {
            if (line.origin == InvoiceItemOrigin.BASE) {
                hasBase = true;
                assertEquals(1400.0, line.rate, 0.001, "BASE rate must equal source rate");
                assertEquals(51.0, line.hours, 0.001, "BASE hours = 100% × 51 = 51");
            } else if (line.origin == InvoiceItemOrigin.CALCULATED) {
                hasCalc = true;
                assertEquals(-1285.0, line.rate, 0.001, "CALCULATED rate = 100% × -1285 = -1285");
                assertEquals(1.0, line.hours, 0.001, "CALCULATED hours fixed at 1");
                assertEquals("general-discount-1.8pct", line.ruleId,
                        "CALCULATED must carry source ruleId");
            }
        }
        assertTrue(hasBase, "expected one BASE line on issuer X group");
        assertTrue(hasCalc, "expected one CALCULATED line on issuer X group — "
                + "if missing, the synthetic-attribution synthesis regressed");
        assertFalse(issuerLines.isEmpty());
    }
}
