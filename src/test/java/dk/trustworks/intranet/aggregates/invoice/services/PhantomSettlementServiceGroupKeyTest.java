package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.model.Company;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhantomSettlementServiceGroupKeyTest {

    private final PhantomSettlementService service = new PhantomSettlementService();

    @Test
    void groupKeyOf_mappedPhantom_buildsKey() {
        Invoice phantom = mock(Invoice.class);
        Company company = mock(Company.class);
        when(phantom.getBillingClientUuid()).thenReturn("client-1");
        when(phantom.getCompany()).thenReturn(company);
        when(company.getUuid()).thenReturn("comp-1");
        when(phantom.getYear()).thenReturn(2026);
        when(phantom.getMonth()).thenReturn(3);

        SettlementGroupKey k = service.groupKeyOf(phantom);
        assertEquals(new SettlementGroupKey("client-1", "comp-1", 2026, 3), k);
    }

    @Test
    void groupKeyOf_unmappedPhantom_returnsNull() {
        Invoice phantom = mock(Invoice.class);
        when(phantom.getBillingClientUuid()).thenReturn(null); // not yet resolved to a client
        assertNull(service.groupKeyOf(phantom));
    }
}
