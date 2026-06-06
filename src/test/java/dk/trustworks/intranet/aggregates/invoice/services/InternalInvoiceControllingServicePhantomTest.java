package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void loadItemTotals_preservesSignedCreditNoteTotals() throws Exception {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(1, "phantom-credit")).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.singletonList(new Object[]{"phantom-credit", -4000.0}));

        InternalInvoiceControllingService service = new InternalInvoiceControllingService();
        service.em = em;

        Method loadItemTotals = InternalInvoiceControllingService.class
                .getDeclaredMethod("loadItemTotals", Set.class);
        loadItemTotals.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Double> totals = (Map<String, Double>) loadItemTotals.invoke(service, Set.of("phantom-credit"));

        assertEquals(-4000.0, totals.get("phantom-credit"));
    }
}
