package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.resources.dto.EInvoicingListItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EInvoicingService}.
 * Verifies query parameter binding and row mapping.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EInvoicingServiceTest {

    @InjectMocks EInvoicingService service;
    @Mock EntityManager em;
    @Mock Query query;

    @BeforeEach
    void wireQuery() {
        doReturn(query).when(em).createNativeQuery(anyString());
        doReturn(query).when(query).setParameter(anyString(), any());
    }

    @Test
    void listEanInvoices_binds_parameters_and_maps_rows() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);

        Object[] row = new Object[]{
                "inv-uuid-1",                           // uuid
                80123,                                  // invoicenumber
                "Acme A/S",                             // billing_client_name
                "5790000123456",                        // billing_client_ean
                Date.valueOf(LocalDate.of(2026, 2, 15)),// invoicedate
                150000.0,                               // sum_no_tax
                25.0,                                   // vat
                "BOOKED"                                // economics_status
        };
        doReturn(List.of((Object) row)).when(query).getResultList();

        List<EInvoicingListItem> result = service.listEanInvoices(from, to);

        assertEquals(1, result.size());
        EInvoicingListItem item = result.getFirst();
        assertEquals("inv-uuid-1", item.invoiceUuid());
        assertEquals(80123, item.invoicenumber());
        assertEquals("Acme A/S", item.billingClientName());
        assertEquals("5790000123456", item.billingClientEan());
        assertEquals(LocalDate.of(2026, 2, 15), item.invoicedate());
        assertEquals(150000.0, item.sumNoTax());
        assertEquals(25.0, item.vat());
        assertEquals("BOOKED", item.economicsStatus());

        verify(query).setParameter("from", from);
        verify(query).setParameter("to", to);
    }

    @Test
    void listEanInvoices_returns_empty_list_when_no_results() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);

        doReturn(List.of()).when(query).getResultList();

        List<EInvoicingListItem> result = service.listEanInvoices(from, to);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void listEanInvoices_handles_null_billing_client_gracefully() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);

        // billing_client_uuid points to a deleted client — LEFT JOIN yields nulls
        Object[] row = new Object[]{
                "inv-uuid-2",
                80124,
                null,   // billing_client_name
                null,   // billing_client_ean
                Date.valueOf(LocalDate.of(2026, 3, 1)),
                75000.0,
                25.0,
                "PAID"
        };
        doReturn(List.of((Object) row)).when(query).getResultList();

        List<EInvoicingListItem> result = service.listEanInvoices(from, to);

        assertEquals(1, result.size());
        EInvoicingListItem item = result.getFirst();
        assertNull(item.billingClientName());
        assertNull(item.billingClientEan());
        assertEquals("PAID", item.economicsStatus());
    }
}
