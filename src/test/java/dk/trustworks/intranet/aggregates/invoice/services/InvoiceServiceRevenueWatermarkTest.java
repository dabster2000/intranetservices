package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvoiceServiceRevenueWatermarkTest {

    @Test
    void documentMutationUsesTheClosedStoredProcedureInsideTheCallerTransaction() {
        EntityManager em=mock(EntityManager.class);
        Query query=mock(Query.class);
        when(em.getDelegate()).thenReturn(new Object());
        when(em.createNativeQuery(any(String.class))).thenReturn(query);
        when(query.setParameter(any(String.class),any())).thenReturn(query);
        InvoiceService service=new InvoiceService();
        service.em=em;
        Invoice invoice=new Invoice();
        invoice.uuid="invoice";
        invoice.invoicedate=LocalDate.parse("2026-02-17");

        service.markRevenueDocumentChanged(invoice,"item");

        verify(em).createNativeQuery(org.mockito.ArgumentMatchers.contains(
                "sp_mark_practice_revenue_document_and_credit_dependents_changed"));
        verify(query).setParameter("documentUuid","invoice");
        verify(query).setParameter("itemUuid","item");
        verify(query).setParameter("sourceMonth",LocalDate.parse("2026-02-01"));
        verify(query).executeUpdate();
    }
}
