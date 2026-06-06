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

/** Pure unit test — no CDI, no DB. Covers AC2, AC9-math, AC10, residual. */
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

    @Test
    void revenueBasis_proportionalToRevenue() {
        // a: 30h @ revenue 3000 ; b: 10h @ revenue 1000 ; total 4000
        Map<String, WorkAgg> w = work("a", 30, 3000, "b", 10, 1000);
        List<ShareRow> rows = PhantomAttributionService.computeShares(w, new BigDecimal("4000.00"));

        ShareRow a = rows.stream().filter(r -> r.consultantUuid().equals("a")).findFirst().orElseThrow();
        ShareRow b = rows.stream().filter(r -> r.consultantUuid().equals("b")).findFirst().orElseThrow();
        assertEquals(0, a.sharePct().compareTo(new BigDecimal("75.0000")));
        assertEquals(0, b.sharePct().compareTo(new BigDecimal("25.0000")));
        assertEquals(0, a.attributedAmount().compareTo(new BigDecimal("3000.00")));
        assertEquals(0, b.attributedAmount().compareTo(new BigDecimal("1000.00")));
        assertEquals(0, a.originalHours().compareTo(new BigDecimal("30.00")));
    }

    @Test
    void hoursFallback_whenAllRevenueZero() {
        // revenue all 0 -> fall back to hours (30 vs 10)
        Map<String, WorkAgg> w = work("a", 30, 0, "b", 10, 0);
        List<ShareRow> rows = PhantomAttributionService.computeShares(w, new BigDecimal("4000.00"));

        ShareRow a = rows.stream().filter(r -> r.consultantUuid().equals("a")).findFirst().orElseThrow();
        assertEquals(0, a.sharePct().compareTo(new BigDecimal("75.0000")));
        assertEquals(0, a.attributedAmount().compareTo(new BigDecimal("3000.00")));
    }

    @Test
    void residualAbsorbed_sumEqualsPhantomTotalExactly() {
        // three equal consultants on 100.00 -> 33.33 each = 99.99 ; +0.01 residual to smallest uuid
        Map<String, WorkAgg> w = work("a", 1, 1, "b", 1, 1, "c", 1, 1);
        BigDecimal total = new BigDecimal("100.00");
        List<ShareRow> rows = PhantomAttributionService.computeShares(w, total);

        BigDecimal sum = rows.stream().map(ShareRow::attributedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(total), "amounts must sum exactly to the phantom total");

        ShareRow a = rows.stream().filter(r -> r.consultantUuid().equals("a")).findFirst().orElseThrow();
        assertEquals(0, a.attributedAmount().compareTo(new BigDecimal("33.34")),
                "residual goes to the smallest-uuid largest-share consultant");
    }

    @Test
    void residual_goesToLargestShare_notSmallestUuid() {
        // a,b small (16.67% each), z large (66.67%); per-row rounding overshoots the
        // total by 0.01, so the residual is NEGATIVE and must land on the LARGEST-share
        // consultant z — NOT row 0 (the smallest uuid 'a'). The original equal-share
        // residual test could not tell these apart (a buggy "always index 0" passes it).
        Map<String, WorkAgg> w = work("a", 1, 1, "b", 1, 1, "z", 1, 4);
        BigDecimal total = new BigDecimal("100.00");
        List<ShareRow> rows = PhantomAttributionService.computeShares(w, total);

        BigDecimal sum = rows.stream().map(ShareRow::attributedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(total), "amounts must sum exactly to the phantom total");

        ShareRow a = rows.stream().filter(r -> r.consultantUuid().equals("a")).findFirst().orElseThrow();
        ShareRow z = rows.stream().filter(r -> r.consultantUuid().equals("z")).findFirst().orElseThrow();
        assertEquals(0, z.attributedAmount().compareTo(new BigDecimal("66.66")),
                "negative residual absorbed by the LARGEST-share consultant z — proves sharePct drives selection");
        assertEquals(0, a.attributedAmount().compareTo(new BigDecimal("16.67")),
                "the smallest-uuid (small-share) consultant keeps its rounded amount");
    }

    @Test
    void residual_tieForLargestShare_goesToSmallerUuidOfTheTie() {
        // a small (9.09%), m & z tie for the largest share (45.45% each); rounding
        // undershoots by 0.01, so the +0.01 residual goes to the smaller-uuid member
        // of the LARGEST-share tie (m), not to the global smallest uuid 'a'.
        Map<String, WorkAgg> w = work("a", 1, 1, "m", 1, 5, "z", 1, 5);
        BigDecimal total = new BigDecimal("100.00");
        List<ShareRow> rows = PhantomAttributionService.computeShares(w, total);

        BigDecimal sum = rows.stream().map(ShareRow::attributedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(total), "amounts must sum exactly to the phantom total");

        ShareRow a = rows.stream().filter(r -> r.consultantUuid().equals("a")).findFirst().orElseThrow();
        ShareRow m = rows.stream().filter(r -> r.consultantUuid().equals("m")).findFirst().orElseThrow();
        assertEquals(0, m.attributedAmount().compareTo(new BigDecimal("45.46")),
                "tie among the largest share resolves to the smaller uuid (m)");
        assertEquals(0, a.attributedAmount().compareTo(new BigDecimal("9.09")),
                "the global-smallest uuid 'a' does NOT absorb the residual (it is not in the largest-share tie)");
    }

    @Test
    void mixedRevenue_zeroRevenueConsultantGetsZeroShare() {
        // Any positive total revenue => revenue basis; a consultant with revenue=0 is
        // zero-weighted (0%), NOT hours-weighted. Pins the basis-switch boundary between
        // the two extreme cases above.
        Map<String, WorkAgg> w = work("a", 10, 1000, "b", 10, 0);
        List<ShareRow> rows = PhantomAttributionService.computeShares(w, new BigDecimal("1000.00"));

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
    void singleConsultant_getsFullTotal() {
        List<ShareRow> rows = PhantomAttributionService.computeShares(
                work("solo", 12, 500), new BigDecimal("12345.67"));
        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).sharePct().compareTo(new BigDecimal("100.0000")));
        assertEquals(0, rows.get(0).attributedAmount().compareTo(new BigDecimal("12345.67")));
    }

    @Test
    void noWork_emptyResult() {
        assertTrue(PhantomAttributionService.computeShares(Map.of(), new BigDecimal("100.00")).isEmpty());
        // all weights zero -> empty
        assertTrue(PhantomAttributionService.computeShares(
                work("a", 0, 0), new BigDecimal("100.00")).isEmpty());
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
        // A credit-note phantom: a negative total must distribute to negative shares
        // that sum EXACTLY to the total. share_pct stays positive (it is a weight ratio).
        Map<String, WorkAgg> w = work("a", 30, 3000, "b", 10, 1000); // 75% / 25%
        List<ShareRow> rows = PhantomAttributionService.computeShares(w, new BigDecimal("-4000.00"));

        ShareRow a = rows.stream().filter(r -> r.consultantUuid().equals("a")).findFirst().orElseThrow();
        ShareRow b = rows.stream().filter(r -> r.consultantUuid().equals("b")).findFirst().orElseThrow();

        assertEquals(0, a.attributedAmount().compareTo(new BigDecimal("-3000.00")));
        assertEquals(0, b.attributedAmount().compareTo(new BigDecimal("-1000.00")));
        assertEquals(0, a.sharePct().compareTo(new BigDecimal("75.0000")), "share_pct is a positive ratio");

        BigDecimal sum = rows.stream().map(ShareRow::attributedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(new BigDecimal("-4000.00")),
                "negative shares must sum exactly to the negative phantom total");
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
