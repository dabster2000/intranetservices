package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItemAttribution;
import dk.trustworks.intranet.aggregates.invoice.model.enums.AttributionSource;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure-function line generator. No CDI, no DB — works off
 * plain instances so we can assert deterministic rounding, cross-company filtering,
 * and residual-absorption behavior.
 */
class InternalInvoiceLineGeneratorTest {

    private static final String SOURCE_COMPANY = "source-co";
    private static final String ISSUER_A = "issuer-a";
    private static final String ISSUER_B = "issuer-b";

    @Test
    void singleConsultant_hundredPercent_crossCompany_oneLine() {
        InvoiceItem src = base("item-1", 1200.0, 40.0);
        InvoiceItemAttribution attr = attr("attr-1", "item-1", "mary", "100.0000");
        Map<String, String> users = Map.of("mary", ISSUER_A);

        Map<String, List<InvoiceItem>> out = InternalInvoiceLineGenerator.generate(
                SOURCE_COMPANY, List.of(src), List.of(attr), users);

        assertEquals(1, out.size());
        List<InvoiceItem> lines = out.get(ISSUER_A);
        assertEquals(1, lines.size());
        assertEquals(1200.0, lines.get(0).rate, 0.001);
        assertEquals(40.0, lines.get(0).hours, 0.001);
        assertEquals("item-1", lines.get(0).sourceItemUuid);
        assertEquals("attr-1", lines.get(0).sourceAttributionUuid);
        assertEquals(InvoiceItemOrigin.BASE, lines.get(0).origin);
    }

    @Test
    void sixtyFortySplit_oneCrossCompany_returnsCrossCompanyShareOnly() {
        // 60% to Joe (source company — skipped), 40% to Mary (ISSUER_A — kept).
        InvoiceItem src = base("item-1", 1000.0, 10.0);
        InvoiceItemAttribution joe = attr("attr-j", "item-1", "joe", "60.0000");
        InvoiceItemAttribution mary = attr("attr-m", "item-1", "mary", "40.0000");
        Map<String, String> users = Map.of("joe", SOURCE_COMPANY, "mary", ISSUER_A);

        Map<String, List<InvoiceItem>> out = InternalInvoiceLineGenerator.generate(
                SOURCE_COMPANY, List.of(src), List.of(joe, mary), users);

        assertEquals(1, out.size());
        List<InvoiceItem> lines = out.get(ISSUER_A);
        assertEquals(1, lines.size());
        // 40% of 10h = 4h, residual absorption is zero (single-line group).
        assertEquals(4.0, lines.get(0).hours, 0.001);
        assertEquals(1000.0, lines.get(0).rate, 0.001);
    }

    @Test
    void twoItems_oneIssuer_groupedUnderSameIssuer() {
        InvoiceItem s1 = base("i1", 500.0, 10.0);
        InvoiceItem s2 = base("i2", 500.0, 20.0);
        InvoiceItemAttribution a1 = attr("a1", "i1", "mary", "100.0000");
        InvoiceItemAttribution a2 = attr("a2", "i2", "mary", "100.0000");
        Map<String, String> users = Map.of("mary", ISSUER_A);

        Map<String, List<InvoiceItem>> out = InternalInvoiceLineGenerator.generate(
                SOURCE_COMPANY, List.of(s1, s2), List.of(a1, a2), users);

        assertEquals(1, out.size());
        assertEquals(2, out.get(ISSUER_A).size());
    }

    @Test
    void twoItems_twoIssuers_producesTwoIssuerGroups() {
        InvoiceItem s1 = base("i1", 500.0, 10.0);
        InvoiceItem s2 = base("i2", 500.0, 20.0);
        InvoiceItemAttribution a1 = attr("a1", "i1", "mary", "100.0000");
        InvoiceItemAttribution a2 = attr("a2", "i2", "bob", "100.0000");
        Map<String, String> users = Map.of("mary", ISSUER_A, "bob", ISSUER_B);

        Map<String, List<InvoiceItem>> out = InternalInvoiceLineGenerator.generate(
                SOURCE_COMPANY, List.of(s1, s2), List.of(a1, a2), users);

        assertEquals(2, out.size());
        assertEquals(1, out.get(ISSUER_A).size());
        assertEquals(1, out.get(ISSUER_B).size());
    }

    @Test
    void threeWaySplit_roundingResidualAbsorbedIntoLargestShare_sumMatches() {
        // 33.3333 / 33.3333 / 33.3334 of 100h; naive sum is 99.9999 + adjustment.
        InvoiceItem src = base("i1", 1200.0, 100.0);
        // Two consultants with tied 33.3333 shares — deterministic: lexicographically smallest uuid wins the tie.
        InvoiceItemAttribution a1 = attr("attr-a", "i1", "u1", "33.3333");
        InvoiceItemAttribution a2 = attr("attr-b", "i1", "u2", "33.3333");
        InvoiceItemAttribution a3 = attr("attr-c", "i1", "u3", "33.3334");
        Map<String, String> users = Map.of("u1", ISSUER_A, "u2", ISSUER_A, "u3", ISSUER_A);

        Map<String, List<InvoiceItem>> out = InternalInvoiceLineGenerator.generate(
                SOURCE_COMPANY, List.of(src), List.of(a1, a2, a3), users);

        List<InvoiceItem> lines = out.get(ISSUER_A);
        assertEquals(3, lines.size());
        // Sum of hours should equal expectedTotalHours: 100.0000 × (33.3333 + 33.3333 + 33.3334) / 100 = 100.0000 (HALF_UP 2dp).
        double sum = lines.stream().mapToDouble(l -> l.hours).sum();
        assertEquals(100.00, sum, 0.001);
        // Largest-share line (u3 @ 33.3334) must have absorbed any residual. Here 33.33 + 33.33 + residual.
        InvoiceItem largest = lines.stream()
                .filter(l -> "u3".equals(l.consultantuuid))
                .findFirst().orElseThrow();
        assertTrue(largest.hours >= 33.33);
    }

    @Test
    void zeroSharePct_isSkipped() {
        InvoiceItem src = base("i1", 1000.0, 10.0);
        InvoiceItemAttribution a1 = attr("a1", "i1", "mary", "0.0000");
        Map<String, String> users = Map.of("mary", ISSUER_A);

        Map<String, List<InvoiceItem>> out = InternalInvoiceLineGenerator.generate(
                SOURCE_COMPANY, List.of(src), List.of(a1), users);

        assertTrue(out.isEmpty(), "Zero-share attribution must not produce any line");
    }

    @Test
    void calculatedNegativeRate_splitsProportionally() {
        // Discount item: rate = -200 per 1 hour. 40% to Mary (cross-company) → -80.
        InvoiceItem src = calculated("i1", -200.0);
        src.calculationRef = "discount-42";
        src.ruleId = "RULE-DISC";
        src.label = "Spring-special";
        InvoiceItemAttribution a1 = attr("a1", "i1", "mary", "40.0000");
        Map<String, String> users = Map.of("mary", ISSUER_A);

        Map<String, List<InvoiceItem>> out = InternalInvoiceLineGenerator.generate(
                SOURCE_COMPANY, List.of(src), List.of(a1), users);

        List<InvoiceItem> lines = out.get(ISSUER_A);
        assertEquals(1, lines.size());
        assertEquals(InvoiceItemOrigin.CALCULATED, lines.get(0).origin);
        assertEquals(-80.0, lines.get(0).rate, 0.01);
        assertEquals(1.0, lines.get(0).hours, 0.001);
        // CALCULATED metadata passthrough per spec §5.1
        assertEquals("discount-42", lines.get(0).calculationRef);
        assertEquals("RULE-DISC", lines.get(0).ruleId);
        assertEquals("Spring-special", lines.get(0).label);
    }

    @Test
    void allSharesWithinSourceCompany_returnsEmpty() {
        InvoiceItem src = base("i1", 1000.0, 10.0);
        InvoiceItemAttribution a = attr("a1", "i1", "joe", "100.0000");
        Map<String, String> users = Map.of("joe", SOURCE_COMPANY);

        Map<String, List<InvoiceItem>> out = InternalInvoiceLineGenerator.generate(
                SOURCE_COMPANY, List.of(src), List.of(a), users);

        assertTrue(out.isEmpty(), "No cross-company attribution → empty map");
    }

    @Test
    void unresolvedConsultantCompany_isSkipped() {
        InvoiceItem src = base("i1", 1000.0, 10.0);
        InvoiceItemAttribution a = attr("a1", "i1", "mystery", "100.0000");
        // "mystery" absent from userCompanies map → skipped
        Map<String, String> users = Map.of();

        Map<String, List<InvoiceItem>> out = InternalInvoiceLineGenerator.generate(
                SOURCE_COMPANY, List.of(src), List.of(a), users);

        assertTrue(out.isEmpty());
    }

    @Test
    void belowOneCentThreshold_isSkipped() {
        // 10% of 0.01h = 0.001h at rate 0 → well below threshold.
        InvoiceItem src = base("i1", 0.01, 0.001);
        InvoiceItemAttribution a = attr("a1", "i1", "mary", "10.0000");
        Map<String, String> users = Map.of("mary", ISSUER_A);

        Map<String, List<InvoiceItem>> out = InternalInvoiceLineGenerator.generate(
                SOURCE_COMPANY, List.of(src), List.of(a), users);

        // With rate=0.01 and hours rounded HALF_UP to 2dp = 0.00 → amount = 0.00 < 0.01 → skipped.
        assertTrue(out.isEmpty() || out.values().stream().allMatch(List::isEmpty));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static InvoiceItem base(String uuid, double rate, double hours) {
        InvoiceItem i = new InvoiceItem();
        i.uuid = uuid;
        i.rate = rate;
        i.hours = hours;
        i.itemname = "item-" + uuid;
        i.description = "desc-" + uuid;
        i.origin = InvoiceItemOrigin.BASE;
        return i;
    }

    private static InvoiceItem calculated(String uuid, double rate) {
        InvoiceItem i = new InvoiceItem();
        i.uuid = uuid;
        i.rate = rate;
        i.hours = 1.0;
        i.itemname = "calc-" + uuid;
        i.origin = InvoiceItemOrigin.CALCULATED;
        return i;
    }

    private static InvoiceItemAttribution attr(String uuid, String itemUuid, String consultantUuid, String sharePct) {
        InvoiceItemAttribution a = new InvoiceItemAttribution();
        a.uuid = uuid;
        a.invoiceitemUuid = itemUuid;
        a.consultantUuid = consultantUuid;
        a.sharePct = new BigDecimal(sharePct);
        a.source = AttributionSource.AUTO;
        return a;
    }
}
