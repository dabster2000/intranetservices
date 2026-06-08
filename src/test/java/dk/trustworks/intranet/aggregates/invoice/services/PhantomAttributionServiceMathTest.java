package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.services.PhantomAttributionService.ShareRow;
import dk.trustworks.intranet.aggregates.invoice.services.PhantomAttributionService.WorkAgg;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit test — no CDI, no DB. Attribution amount = consultant work value, apportioned per phantom across the group; scope predicate. */
class PhantomAttributionServiceMathTest {

    private static final LocalDate FY_START = LocalDate.of(2025, 7, 1);
    private static final LocalDate FY_END = LocalDate.of(2026, 7, 1);

    private static Map<String, WorkAgg> work(Object... triples) {
        Map<String, WorkAgg> m = new LinkedHashMap<>();
        for (int i = 0; i < triples.length; i += 3) {
            m.put((String) triples[i],
                    new WorkAgg(BigDecimal.valueOf(((Number) triples[i + 1]).doubleValue()),
                                BigDecimal.valueOf(((Number) triples[i + 2]).doubleValue())));
        }
        return m;
    }

    /** Single-phantom convenience: the group IS this one phantom, so groupTotal == phantomTotal. */
    private static List<ShareRow> shares(Map<String, WorkAgg> w, String total) {
        return PhantomAttributionService.computeShares(w, new BigDecimal(total), new BigDecimal(total));
    }

    @Test
    void revenueBasis_proportionalToRevenue() {
        // a: 30h @ revenue 3000 ; b: 10h @ revenue 1000 ; total 4000
        Map<String, WorkAgg> w = work("a", 30, 3000, "b", 10, 1000);
        List<ShareRow> rows = shares(w, "4000.00");

        ShareRow a = rows.stream().filter(r -> r.consultantUuid().equals("a")).findFirst().orElseThrow();
        ShareRow b = rows.stream().filter(r -> r.consultantUuid().equals("b")).findFirst().orElseThrow();
        assertEquals(0, a.sharePct().compareTo(new BigDecimal("75.0000")));
        assertEquals(0, b.sharePct().compareTo(new BigDecimal("25.0000")));
        assertEquals(0, a.attributedAmount().compareTo(new BigDecimal("3000.00")));
        assertEquals(0, b.attributedAmount().compareTo(new BigDecimal("1000.00")));
        assertEquals(0, a.originalHours().compareTo(new BigDecimal("30.00")));
    }

    @Test
    void noRevenue_amountsAreZero_sharePctStillHoursBased() {
        // rate=0 everywhere => no monetary basis. Work value is 0, so attribution is 0 (the phantom
        // is flagged for manual rate review in deriveForPhantom). sharePct is still computed by
        // hours, for display only.
        Map<String, WorkAgg> w = work("a", 30, 0, "b", 10, 0);
        List<ShareRow> rows = shares(w, "4000.00");

        ShareRow a = rows.stream().filter(r -> r.consultantUuid().equals("a")).findFirst().orElseThrow();
        assertEquals(0, a.sharePct().compareTo(new BigDecimal("75.0000")), "sharePct still hours-based for display");
        assertEquals(0, a.attributedAmount().compareTo(new BigDecimal("0.00")), "no rate => zero transfer value");
    }

    // NOTE: the former residual* tests were removed — amounts are now each consultant's own work
    // value (apportioned per phantom), not a distribution of phantomTotal, so there is no rounding
    // residual to redistribute. See sumOfAttributions_* and multiPhantomGroup_* for the invariants.

    @Test
    void mixedRevenue_zeroRevenueConsultantGetsZeroShare() {
        // Any positive total revenue => revenue basis; a consultant with revenue=0 is
        // zero-weighted (0%), NOT hours-weighted. Pins the basis-switch boundary between
        // the two extreme cases above.
        Map<String, WorkAgg> w = work("a", 10, 1000, "b", 10, 0);
        List<ShareRow> rows = shares(w, "1000.00");

        ShareRow a = rows.stream().filter(r -> r.consultantUuid().equals("a")).findFirst().orElseThrow();
        ShareRow b = rows.stream().filter(r -> r.consultantUuid().equals("b")).findFirst().orElseThrow();
        assertEquals(0, a.sharePct().compareTo(new BigDecimal("100.0000")));
        assertEquals(0, a.attributedAmount().compareTo(new BigDecimal("1000.00")));
        assertEquals(0, b.sharePct().compareTo(new BigDecimal("0.0000")),
                "revenue=0 consultant is zero-weighted under revenue basis");
        assertEquals(0, b.attributedAmount().compareTo(new BigDecimal("0.00")));
    }

    @Test
    void scope_malformedPeriodIsOutOfScope() {
        // An unset/garbage month (int default 0, or >12) or year<1 would make
        // LocalDate.of throw; isInScope must treat these as out-of-scope so a single
        // malformed phantom cannot abort the whole deriveAllInScope batch.
        assertFalse(PhantomAttributionService.isInScope(phantom(InvoiceType.PHANTOM, InvoiceStatus.CREATED, 2025, 0, false), FY_START, FY_END), "month=0 out");
        assertFalse(PhantomAttributionService.isInScope(phantom(InvoiceType.PHANTOM, InvoiceStatus.CREATED, 2025, 13, false), FY_START, FY_END), "month=13 out");
        assertFalse(PhantomAttributionService.isInScope(phantom(InvoiceType.PHANTOM, InvoiceStatus.CREATED, 0, 9, false), FY_START, FY_END), "year=0 out");
    }

    @Test
    void singleConsultant_getsOwnWorkValue_notPhantomTotal() {
        // The clearest expression of the original bug: a consultant with 500 of logged work was
        // attributed the entire 12,345.67 self-billed phantom. The transfer price is their work value.
        List<ShareRow> rows = shares(work("solo", 12, 500), "12345.67");
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).sharePct().compareTo(new BigDecimal("100.0000")));
        assertEquals(0, rows.get(0).attributedAmount().compareTo(new BigDecimal("500.00")),
                "attribution = the consultant's work value (hours×rate), not the phantom total");
    }

    // ---- Regression: attribution basis = consultant work value, NOT self-billed-revenue share ----

    @Test
    void attribution_overStatedPhantom_isConsultantWorkValue() {
        // Vattenfall 2025-08: self-billed phantom revenue (3,198,984) is ~2.5x the consultants'
        // logged work value (1,280,350). The cross-company consultant's transfer price must be
        // their OWN work value (162,725) — NOT their hour-share of the inflated phantom (406,572).
        Map<String, WorkAgg> w = work(
                "michelle", 141.5, 162725,
                "rest",    1000.0, 1117625);   // 162,725 + 1,117,625 = 1,280,350 total work value
        List<ShareRow> rows = shares(w, "3198984.00");

        ShareRow m = rows.stream().filter(r -> r.consultantUuid().equals("michelle")).findFirst().orElseThrow();
        assertEquals(0, m.attributedAmount().compareTo(new BigDecimal("162725.00")),
                "over-stated phantom must not inflate the consultant's work-value transfer price (was 406,572)");
    }

    @Test
    void attribution_underStatedPhantom_isConsultantWorkValue() {
        // Energinet 2026-04: phantom revenue (455,630) is BELOW the work value (557,321). The
        // cross-company consultant must still get their full work value (132,652), not the
        // smaller phantom share (108,448).
        Map<String, WorkAgg> w = work(
                "julie", 100.0, 132652,
                "rest",  300.0, 424669);    // 132,652 + 424,669 = 557,321 total work value
        List<ShareRow> rows = shares(w, "455630.00");

        ShareRow j = rows.stream().filter(r -> r.consultantUuid().equals("julie")).findFirst().orElseThrow();
        assertEquals(0, j.attributedAmount().compareTo(new BigDecimal("132652.00")),
                "under-stated phantom must not reduce the work-value transfer price (was 108,448)");
    }

    @Test
    void attribution_independentOfPhantomMagnitude() {
        // For a single-phantom group the amount is the work value regardless of the phantom's size.
        Map<String, WorkAgg> w = work("a", 10, 1000, "b", 20, 2000);
        List<ShareRow> small = shares(w, "3000.00");
        List<ShareRow> huge  = shares(w, "999999.00");
        assertEquals(0, amt(small, "a").compareTo(amt(huge, "a")), "amount must not scale with phantom revenue");
        assertEquals(0, amt(small, "b").compareTo(amt(huge, "b")));
        assertEquals(0, amt(small, "a").compareTo(new BigDecimal("1000.00")));
        assertEquals(0, amt(small, "b").compareTo(new BigDecimal("2000.00")));
    }

    @Test
    void sumOfAttributions_equalsTotalWorkValue_notPhantomTotal() {
        // The margin (phantom revenue − work value) is intentionally NOT attributed to anyone;
        // it stays with the contract-holding company.
        Map<String, WorkAgg> w = work("a", 10, 1000, "b", 20, 2000); // work value total = 3000
        List<ShareRow> rows = shares(w, "9000.00");
        BigDecimal sum = rows.stream().map(ShareRow::attributedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(new BigDecimal("3000.00")),
                "attributions sum to the work value, not the self-billed phantom total");
    }

    @Test
    void multiPhantomGroup_sumsToWorkValuePerConsultant_notMultiplied() {
        // A settlement group has MANY phantoms (one per e-conomic entry). deriveForPhantom runs
        // per phantom with the SAME group-level work data, so each phantom carries only its share
        // (phantomTotal / |groupTotal|) and a consultant's amounts SUM across the group to their
        // work value — NOT work value x phantomCount (the bug the staging probe caught).
        Map<String, WorkAgg> w = work("michelle", 50, 1000, "rest", 150, 3000); // W = 4000
        BigDecimal R = new BigDecimal("4000.00");                                // 2 phantoms: 3000 + 1000
        BigDecimal[] phantomTotals = { new BigDecimal("3000.00"), new BigDecimal("1000.00") };

        BigDecimal michelle = BigDecimal.ZERO, rest = BigDecimal.ZERO;
        for (BigDecimal r : phantomTotals) {
            List<ShareRow> rows = PhantomAttributionService.computeShares(w, r, R);
            michelle = michelle.add(amt(rows, "michelle"));
            rest = rest.add(amt(rows, "rest"));
        }
        assertEquals(0, michelle.compareTo(new BigDecimal("1000.00")),
                "consultant's amounts across the group's phantoms must equal their work value, not work value x N");
        assertEquals(0, rest.compareTo(new BigDecimal("3000.00")));
    }

    @Test
    void multiPhantomGroup_perPhantomIsRevenueWeightedSlice() {
        // Each phantom carries its revenue-weighted slice of the work value: phantom of 3000/4000
        // gives michelle 1000 x 3/4 = 750; phantom of 1000/4000 gives 250.
        Map<String, WorkAgg> w = work("michelle", 50, 1000, "rest", 150, 3000);
        BigDecimal R = new BigDecimal("4000.00");
        assertEquals(0, amt(PhantomAttributionService.computeShares(w, new BigDecimal("3000.00"), R), "michelle")
                .compareTo(new BigDecimal("750.00")));
        assertEquals(0, amt(PhantomAttributionService.computeShares(w, new BigDecimal("1000.00"), R), "michelle")
                .compareTo(new BigDecimal("250.00")));
    }

    @Test
    void multiPhantomGroup_roundingResidualIsBounded() {
        // 3 equal phantoms over a work value of 100: each slice = 100 × 1/3 = 33.3333… → 33.33, so
        // the group sum is 99.99 — a 1-øre residual from independent per-phantom 2dp rounding (NOT
        // exact). The residual stays within a few øre of the work value (~phantomCount/2 øre).
        Map<String, WorkAgg> w = work("a", 10, 100); // work value = 100
        BigDecimal R = new BigDecimal("3.00");
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            sum = sum.add(amt(PhantomAttributionService.computeShares(w, new BigDecimal("1.00"), R), "a"));
        }
        assertEquals(0, sum.compareTo(new BigDecimal("99.99")), "per-phantom 2dp rounding leaves a small residual");
        assertTrue(sum.subtract(new BigDecimal("100.00")).abs().compareTo(new BigDecimal("0.05")) <= 0,
                "residual stays within a few øre of the work value");
    }

    private static BigDecimal amt(List<ShareRow> rows, String consultant) {
        return rows.stream().filter(r -> r.consultantUuid().equals(consultant))
                .findFirst().orElseThrow().attributedAmount();
    }

    @Test
    void noWork_emptyResult() {
        assertTrue(shares(Map.of(), "100.00").isEmpty());
        // all weights zero -> empty
        assertTrue(shares(work("a", 0, 0), "100.00").isEmpty());
    }

    @Test
    void zeroGroupTotal_yieldsZeroAmounts() {
        // A net-zero group (e.g. an invoice + its credit note) cannot apportion -> amounts are 0.
        Map<String, WorkAgg> w = work("a", 30, 3000, "b", 10, 1000);
        List<ShareRow> rows = PhantomAttributionService.computeShares(w, new BigDecimal("3000.00"), BigDecimal.ZERO);
        assertEquals(0, amt(rows, "a").compareTo(new BigDecimal("0.00")), "groupTotal=0 -> amount 0");
        assertEquals(0, amt(rows, "b").compareTo(new BigDecimal("0.00")));
    }

    @Test
    void scope_currentFyCreatedPhantomOnly() {
        assertTrue(PhantomAttributionService.isInScope(phantom(InvoiceType.PHANTOM, InvoiceStatus.CREATED, 2025, 9, false), FY_START, FY_END));
        assertFalse(PhantomAttributionService.isInScope(phantom(InvoiceType.PHANTOM, InvoiceStatus.CREATED, 2025, 3, false), FY_START, FY_END), "prior FY out");
        assertFalse(PhantomAttributionService.isInScope(phantom(InvoiceType.INVOICE, InvoiceStatus.CREATED, 2025, 9, false), FY_START, FY_END), "non-phantom out");
        assertFalse(PhantomAttributionService.isInScope(phantom(InvoiceType.PHANTOM, InvoiceStatus.DRAFT, 2025, 9, false), FY_START, FY_END), "non-CREATED out");
        assertFalse(PhantomAttributionService.isInScope(phantom(InvoiceType.PHANTOM, InvoiceStatus.CREATED, 2025, 9, true), FY_START, FY_END), "skip-flagged out");
    }

    @Test
    void negativeTotal_yieldsNegativeSharesSummingExactly() {
        // A credit-note single-phantom group: a negative total reverses to negative amounts that
        // sum to the negated work value. share_pct stays positive (it is a weight ratio).
        Map<String, WorkAgg> w = work("a", 30, 3000, "b", 10, 1000); // 75% / 25%
        List<ShareRow> rows = shares(w, "-4000.00");

        ShareRow a = rows.stream().filter(r -> r.consultantUuid().equals("a")).findFirst().orElseThrow();
        ShareRow b = rows.stream().filter(r -> r.consultantUuid().equals("b")).findFirst().orElseThrow();

        assertEquals(0, a.attributedAmount().compareTo(new BigDecimal("-3000.00")));
        assertEquals(0, b.attributedAmount().compareTo(new BigDecimal("-1000.00")));
        assertEquals(0, a.sharePct().compareTo(new BigDecimal("75.0000")), "share_pct is a positive ratio");

        BigDecimal sum = rows.stream().map(ShareRow::attributedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(new BigDecimal("-4000.00")),
                "negative amounts must sum to the negated work value");
    }

    @Test
    void negativeTotal_negatesWorkValue_notPhantomShare() {
        // Credit-note phantom whose magnitude (9000) differs from the work value (4000): each amount
        // must be the NEGATED work value (-3000 / -1000), NOT a share of the phantom (-6750 / -2250).
        // The test above uses revenue == |total|, so it cannot distinguish the two; this one can.
        Map<String, WorkAgg> w = work("a", 30, 3000, "b", 10, 1000); // work value total = 4000
        List<ShareRow> rows = shares(w, "-9000.00");
        assertEquals(0, amt(rows, "a").compareTo(new BigDecimal("-3000.00")),
                "credit note negates the consultant's work value, not a share of the phantom total");
        assertEquals(0, amt(rows, "b").compareTo(new BigDecimal("-1000.00")));
    }

    private static Invoice phantom(InvoiceType type, InvoiceStatus status, int year, int month, boolean skip) {
        Invoice i = new Invoice();
        i.type = type;
        i.status = status;
        i.year = year;
        i.month = month;
        i.internalInvoiceSkip = skip;
        return i;
    }
}
