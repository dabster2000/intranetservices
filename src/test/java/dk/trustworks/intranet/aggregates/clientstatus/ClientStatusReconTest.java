package dk.trustworks.intranet.aggregates.clientstatus;

import dk.trustworks.intranet.aggregates.clientstatus.ClientStatusRecon.AttributionShare;
import dk.trustworks.intranet.aggregates.clientstatus.ClientStatusRecon.InvoiceItemLine;
import dk.trustworks.intranet.aggregates.clientstatus.ClientStatusRecon.RegisteredLine;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusConsultantRecon;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests for the DB-free per-consultant reconciliation merge. */
class ClientStatusReconTest {

    private static ClientStatusConsultantRecon findByUuid(List<ClientStatusConsultantRecon> rows, String uuid) {
        return rows.stream().filter(r -> uuid.equals(r.consultantUuid())).findFirst().orElseThrow();
    }

    private static ClientStatusConsultantRecon unmatched(List<ClientStatusConsultantRecon> rows) {
        return rows.stream().filter(r -> r.consultantUuid() == null).findFirst().orElse(null);
    }

    @Test
    void attributionRows_creditEachConsultant_sourceIsAttribution() {
        var registered = List.of(new RegisteredLine("A", 100, 100_000));
        var item = new InvoiceItemLine(100_000, 1, null,
                List.of(new AttributionShare("A", 60_000), new AttributionShare("B", 40_000)));

        var rows = ClientStatusRecon.merge(registered, List.of(item), Map.of("A", "Alice", "B", "Bob"));

        assertNull(unmatched(rows), "fully attributed item leaves no residual");
        var a = findByUuid(rows, "A");
        assertEquals(60_000, a.invoicedValue(), 0.01);
        assertEquals(40_000, a.missingValue(), 0.01); // 100k registered - 60k invoiced
        assertEquals("ATTRIBUTION", a.invoicedSource());
        assertEquals("Alice", a.consultantName());
        var b = findByUuid(rows, "B");
        assertEquals(40_000, b.invoicedValue(), 0.01);
        assertEquals("ATTRIBUTION", b.invoicedSource());
    }

    @Test
    void itemConsultantFallback_whenNoAttribution_sourceIsItemConsultant() {
        var item = new InvoiceItemLine(50_000, 1, "C", List.of());
        var rows = ClientStatusRecon.merge(List.of(), List.of(item), Map.of("C", "Carol"));
        var c = findByUuid(rows, "C");
        assertEquals(50_000, c.invoicedValue(), 0.01);
        assertEquals(0, c.registeredValue(), 0.01); // invoiced-but-no-work appears
        assertEquals(-50_000, c.missingValue(), 0.01);
        assertEquals("ITEM_CONSULTANT", c.invoicedSource());
    }

    @Test
    void noAttributionNoConsultant_goesToUnmatchedBucket() {
        var item = new InvoiceItemLine(30_000, 1, null, List.of());
        var rows = ClientStatusRecon.merge(List.of(), List.of(item), Map.of());
        var u = unmatched(rows);
        assertNotNull(u);
        assertEquals(30_000, u.invoicedValue(), 0.01);
        assertNull(u.consultantName());
        assertEquals("NONE", u.invoicedSource());
    }

    @Test
    void partialAttribution_residualGoesToUnmatched() {
        // item 100k, only 70k attributed → 30k residual to unmatched bucket
        var item = new InvoiceItemLine(100_000, 1, null, List.of(new AttributionShare("A", 70_000)));
        var rows = ClientStatusRecon.merge(List.of(), List.of(item), Map.of("A", "Alice"));
        assertEquals(70_000, findByUuid(rows, "A").invoicedValue(), 0.01);
        assertEquals(30_000, unmatched(rows).invoicedValue(), 0.01);
    }

    @Test
    void mixedSource_consultantCreditedViaBothPaths_isMixed() {
        var itemAttr = new InvoiceItemLine(60_000, 1, null, List.of(new AttributionShare("A", 60_000)));
        var itemFallback = new InvoiceItemLine(40_000, 1, "A", List.of());
        var rows = ClientStatusRecon.merge(List.of(), List.of(itemAttr, itemFallback), Map.of("A", "Alice"));
        var a = findByUuid(rows, "A");
        assertEquals(100_000, a.invoicedValue(), 0.01);
        assertEquals("MIXED", a.invoicedSource());
    }

    @Test
    void creditNoteSign_negatesInvoicedValue_andResidual() {
        // registered work 100k; a CREDIT_NOTE item of 20k attributed to A → invoiced -20k
        var registered = List.of(new RegisteredLine("A", 20, 20_000));
        var item = new InvoiceItemLine(20_000, -1, null, List.of(new AttributionShare("A", 20_000)));
        var rows = ClientStatusRecon.merge(registered, List.of(item), Map.of("A", "Alice"));
        var a = findByUuid(rows, "A");
        assertEquals(-20_000, a.invoicedValue(), 0.01);
        assertEquals(40_000, a.missingValue(), 0.01); // 20k registered - (-20k)
        assertNull(unmatched(rows));
    }

    @Test
    void tiesToHeadline_sumInvoicedEqualsSumSignedItems() {
        var items = List.of(
                new InvoiceItemLine(100_000, 1, null, List.of(new AttributionShare("A", 60_000))), // 40k residual
                new InvoiceItemLine(50_000, 1, "B", List.of()),
                new InvoiceItemLine(30_000, -1, null, List.of())); // -30k unmatched
        double headline = 100_000 + 50_000 - 30_000;
        var rows = ClientStatusRecon.merge(List.of(), items, Map.of());
        double sum = rows.stream().mapToDouble(ClientStatusConsultantRecon::invoicedValue).sum();
        assertEquals(headline, sum, 0.01, "Σ recon.invoicedValue (incl. unmatched) must equal the headline");
    }

    @Test
    void sortedByMissingDescending() {
        var registered = List.of(
                new RegisteredLine("A", 10, 10_000),
                new RegisteredLine("B", 100, 300_000));
        var rows = ClientStatusRecon.merge(registered, List.of(), Map.of("A", "Alice", "B", "Bob"));
        assertEquals("B", rows.get(0).consultantUuid(), "largest missing first");
        assertEquals("A", rows.get(1).consultantUuid());
    }
}
