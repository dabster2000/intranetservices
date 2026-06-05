package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit test (no DB, no Quarkus boot) for the phantom line mapper that turns
 * an invoice_item_attributions row into the InvoiceLineDTO the controlling grid
 * renders. Guards the riskiest mapping: consultant name -> itemName (grid's
 * Consultant column), attributed_amount -> amountNoTax, crossCompany=true.
 */
class InternalInvoiceControllingServicePhantomTest {

    @Test
    void phantomLineDTO_mapsAttributionToCrossCompanyLine() {
        InvoiceLineDTO line = InternalInvoiceControllingService.phantomLineDTO(
                "attr-1", "Jane Doe", 12.5, 18750.0, "consultant-1");

        assertEquals("attr-1", line.uuid());
        assertEquals("Jane Doe", line.itemName());        // shown in the grid's Consultant column
        assertNull(line.description());
        assertEquals(12.5, line.hours());
        assertNull(line.rate());                          // phantom lines have no rate
        assertEquals(18750.0, line.amountNoTax());
        assertEquals("consultant-1", line.consultantuuid());
        assertTrue(line.crossCompany());                  // phantoms are inherently cross-company
        assertNull(line.consultantCompanyUuid());
        assertNull(line.consultantCompanyName());
    }

    @Test
    void phantomLineDTO_roundsAmountToTwoDecimals() {
        InvoiceLineDTO line = InternalInvoiceControllingService.phantomLineDTO(
                "attr-2", "John Roe", null, 100.005, "consultant-2");
        assertEquals(100.01, line.amountNoTax());         // round2 HALF_UP
        assertNull(line.hours());
    }
}
