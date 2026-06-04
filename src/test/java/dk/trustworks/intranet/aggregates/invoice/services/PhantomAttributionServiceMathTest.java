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
