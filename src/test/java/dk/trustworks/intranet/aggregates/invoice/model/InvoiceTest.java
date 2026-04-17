package dk.trustworks.intranet.aggregates.invoice.model;

import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.model.Company;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvoiceTest {

    @Test
    void constructorDoesNotHardcodeVatForDkk() {
        Company company = new Company();
        Invoice inv = new Invoice(
                InvoiceType.INVOICE,
                "contract-uuid", "project-uuid", "Project",
                0.0, 2026, 4,
                "Client", "Addr", "", "2100 Kbh",
                null, "10000000", "Attn",
                LocalDate.now(), LocalDate.now().plusMonths(1),
                "ref", "contract-ref", "PERIOD",
                company, "DKK", "");

        // vat is no longer set by the constructor. The caller must set it
        // from the VAT zone mapping. Default double value is 0.0.
        assertEquals(0.0, inv.vat);
        assertEquals("DKK", inv.currency);
    }
}
