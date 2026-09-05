package dk.trustworks.intranet.aggregates.invoice;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §4.4.2 / §4.4.6 (D2): a declared 0 kr line reaches the invoice as a no-charge line
 * that shows quantity, list value, reason and review date and contributes 0 to the total;
 * an undeclared rate-0 is still not a no-charge line. Credit-note and intercompany
 * carry-over is covered by {@code InvoiceServiceNoChargeCarryOverTest}.
 */
class InvoiceGeneratorNoChargeTest {

    private static ContractConsultant declaredZero() {
        ContractConsultant cc = new ContractConsultant();
        cc.setRate(0.0);
        cc.setZeroRateReason("PILOT_FREE");
        cc.setListRate(600.0);
        cc.setRateReviewDate(LocalDate.of(2026, 10, 31));
        return cc;
    }

    @Test
    void declaredZeroIsRecognised_bareZeroIsNot() {
        assertTrue(declaredZero().isDeclaredZeroRate());
        ContractConsultant bare = new ContractConsultant();
        bare.setRate(0.0);
        assertFalse(bare.isDeclaredZeroRate());
        ContractConsultant paid = new ContractConsultant();
        paid.setRate(600.0);
        paid.setZeroRateReason("PILOT_FREE");
        assertFalse(paid.isDeclaredZeroRate());
    }

    @Test
    void noChargeLineCopyNamesReasonListValueAndReviewDate() {
        String copy = InvoiceGenerator.noChargeDescription("Junior consultant", declaredZero());
        assertEquals("Junior consultant — No charge (pilot period), list value 600 kr/h, price review 31 Oct 2026", copy);
    }

    @Test
    void noChargeLineContributesZeroToTheInvoiceTotal() {
        Invoice invoice = new Invoice();
        invoice.invoiceitems = new ArrayList<>();
        InvoiceItem paid = new InvoiceItem("u1", "Senior", "Advisory", 1450.0, 10.0, 1, "inv");
        InvoiceItem free = new InvoiceItem("u2", "Junior", "Junior consultant — No charge", 0.0, 38.0, 2, "inv");
        free.noChargeReason = "PILOT_FREE";
        free.listRate = 600.0;
        free.rateReviewDate = LocalDate.of(2026, 10, 31);
        invoice.invoiceitems.add(paid);
        invoice.invoiceitems.add(free);

        // 38 h × 600 kr list value = 22,800 kr is printed, never totalled.
        assertEquals(14_500.0, invoice.getSumNoTax(), 1e-9);
        assertTrue(free.isNoCharge());
        assertEquals(38.0, free.hours, 1e-9);
    }

    @Test
    void reasonLabelsAreHuman() {
        assertEquals("pilot period", InvoiceGenerator.noChargeReasonLabel("PILOT_FREE"));
        assertEquals("goodwill", InvoiceGenerator.noChargeReasonLabel("GOODWILL"));
        assertEquals("internal transfer", InvoiceGenerator.noChargeReasonLabel("INTERNAL_TRANSFER"));
        assertEquals("agreed no charge", InvoiceGenerator.noChargeReasonLabel("OTHER"));
        assertNull(InvoiceGenerator.noChargeReasonLabel(null));
    }
}
