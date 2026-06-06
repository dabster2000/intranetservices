package dk.trustworks.intranet.aggregates.invoice.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvoiceSettlementFieldsTest {

    @Test
    void settlementFields_roundTripThroughLombokAccessors() {
        Invoice i = new Invoice();
        i.setSettlementBillingClientUuid("client-1");
        i.setSettlementDebtorCompanyuuid("comp-1");
        i.setSettlementYear(2026);
        i.setSettlementMonth(3);

        assertEquals("client-1", i.getSettlementBillingClientUuid());
        assertEquals("comp-1", i.getSettlementDebtorCompanyuuid());
        assertEquals(2026, i.getSettlementYear());
        assertEquals(3, i.getSettlementMonth());
    }
}
