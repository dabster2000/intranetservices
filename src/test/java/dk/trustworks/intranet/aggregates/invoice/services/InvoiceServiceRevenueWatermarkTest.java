package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import jakarta.enterprise.event.Event;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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

    @Test
    void crossMonthDocumentMutationMarksOldAndNewMonthsOnceEach() {
        EntityManager em=mock(EntityManager.class);
        Query query=mock(Query.class);
        when(em.getDelegate()).thenReturn(new Object());
        when(em.createNativeQuery(any(String.class))).thenReturn(query);
        when(query.setParameter(any(String.class),any())).thenReturn(query);
        InvoiceService service=new InvoiceService();
        service.em=em;
        Invoice invoice=new Invoice();
        invoice.uuid="invoice";
        invoice.invoicedate=LocalDate.parse("2026-03-17");

        service.markRevenueDocumentChanged(
                invoice, null, LocalDate.parse("2026-02-28"));

        ArgumentCaptor<LocalDate> months=ArgumentCaptor.forClass(LocalDate.class);
        verify(query,times(2)).setParameter(org.mockito.ArgumentMatchers.eq("sourceMonth"),months.capture());
        assertEquals(List.of(LocalDate.parse("2026-02-01"),LocalDate.parse("2026-03-01")),
                months.getAllValues());
        verify(query,times(2)).executeUpdate();
    }

    @Test
    void sameMonthDocumentMutationIsMarkedOnlyOnce() {
        EntityManager em=mock(EntityManager.class);
        Query query=mock(Query.class);
        when(em.getDelegate()).thenReturn(new Object());
        when(em.createNativeQuery(any(String.class))).thenReturn(query);
        when(query.setParameter(any(String.class),any())).thenReturn(query);
        InvoiceService service=new InvoiceService();
        service.em=em;
        Invoice invoice=new Invoice();
        invoice.uuid="invoice";
        invoice.invoicedate=LocalDate.parse("2026-03-17");

        service.markRevenueDocumentChanged(
                invoice, null, LocalDate.parse("2026-03-01"));

        verify(query).setParameter("sourceMonth",LocalDate.parse("2026-03-01"));
        verify(query).executeUpdate();
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistedMonthReadUsesPreFlushDatabaseStateAndNormalizesTheDate() {
        EntityManager em=mock(EntityManager.class);
        TypedQuery<Object[]> query=mock(TypedQuery.class);
        when(em.createQuery(anyString(),eq(Object[].class))).thenReturn(query);
        when(query.setFlushMode(FlushModeType.COMMIT)).thenReturn(query);
        when(query.setParameter("invoiceUuid","invoice")).thenReturn(query);
        when(query.setMaxResults(1)).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.singletonList(
                new Object[]{LocalDate.parse("2026-02-17"),2026,2}));
        InvoiceService service=new InvoiceService();
        service.em=em;

        LocalDate month=service.loadPersistedRecognizedMonth("invoice");

        assertEquals(LocalDate.parse("2026-02-01"),month);
        verify(query).setFlushMode(FlushModeType.COMMIT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void draftUpdateCapturesPersistedMonthBeforeHeaderAndThreadsItToBothWriters() {
        LocalDate oldMonth=LocalDate.parse("2026-02-01");
        List<String> operations=new ArrayList<>();
        AtomicReference<LocalDate> recalculationMonth=new AtomicReference<>();
        AtomicReference<LocalDate> documentMonth=new AtomicReference<>();
        InvoiceService service=new InvoiceService() {
            @Override
            protected LocalDate loadPersistedRecognizedMonth(String invoiceUuid) {
                operations.add("load-old-month");
                return oldMonth;
            }

            @Override
            protected void persistDraftHeader(Invoice invoice) {
                operations.add("persist-header");
            }

            @Override
            protected void recalculateInvoiceItems(
                    Invoice invoice, LocalDate previousRecognizedMonth) {
                operations.add("recalculate");
                recalculationMonth.set(previousRecognizedMonth);
            }

            @Override
            protected void markRevenueDocumentChanged(
                    Invoice invoice, String sourceItemUuid, LocalDate previousRecognizedMonth) {
                operations.add("mark-document");
                documentMonth.set(previousRecognizedMonth);
            }
        };
        service.bonusService=mock(InvoiceBonusService.class);
        service.attributionDirtyEvent=mock(Event.class);
        Invoice invoice=new Invoice();
        invoice.uuid="invoice";
        invoice.type=InvoiceType.INVOICE;
        invoice.status=InvoiceStatus.DRAFT;
        invoice.invoicedate=LocalDate.parse("2026-03-17");
        invoice.invoiceitems=new ArrayList<>();

        service.updateDraftInvoice(invoice);

        assertEquals(List.of("load-old-month","persist-header","recalculate","mark-document"),
                operations);
        assertEquals(oldMonth,recalculationMonth.get());
        assertEquals(oldMonth,documentMonth.get());
    }
}
