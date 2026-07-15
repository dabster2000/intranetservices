package dk.trustworks.intranet.aggregates.invoice.events;

import dk.trustworks.intranet.aggregates.invoice.services.InvoiceAttributionService;
import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceAttributionDirtyObserverTest {
    @Mock InvoiceAttributionService attributionService;
    @Mock PracticeRevenueDirtyMarker dirtyMarker;
    @Mock ManagedExecutor managedExecutor;

    InvoiceAttributionDirtyObserver observer;

    @BeforeEach
    void setUp() {
        observer = new InvoiceAttributionDirtyObserver();
        observer.attributionService = attributionService;
        observer.dirtyMarker = dirtyMarker;
        observer.managedExecutor = managedExecutor;
        observer.asyncTimeout = Duration.ofSeconds(1);
    }

    @Test
    void committedEventPersistsWatermarkBeforeBoundedExecutorDispatch() {
        when(dirtyMarker.beginAsyncMutation(PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION))
                .thenReturn("owner-1");
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(managedExecutor).execute(any(Runnable.class));

        observer.onInvoiceCommitted(new InvoiceAttributionsDirtyEvent("invoice-1"));

        var order = inOrder(dirtyMarker, managedExecutor, attributionService);
        order.verify(dirtyMarker).beginAsyncMutation(PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION);
        order.verify(managedExecutor).execute(any(Runnable.class));
        order.verify(attributionService).computeAttributions("invoice-1");
        order.verify(dirtyMarker).completeAsyncMutation(
                PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION, "owner-1");
        verify(dirtyMarker, never()).mark(any(), any());
    }

    @Test
    void executorRejectionDoesNotLoseDurableWatermark() {
        when(dirtyMarker.beginAsyncMutation(PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION))
                .thenReturn("owner-2");
        doThrow(new RejectedExecutionException("saturated"))
                .when(managedExecutor).execute(any(Runnable.class));

        observer.onInvoiceCommitted(new InvoiceAttributionsDirtyEvent("invoice-2"));

        verify(dirtyMarker).failAsyncMutation(
                PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION, "owner-2");
        verify(dirtyMarker, never()).mark(any(), any());
    }

    @Test
    void secondCommittedEventIsNotDroppedAndOutOfOrderCompletionUsesItsOwnToken() {
        when(dirtyMarker.beginAsyncMutation(PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION))
                .thenReturn("owner-1", "owner-2");
        List<Runnable> dispatched = new ArrayList<>();
        doAnswer(invocation -> {
            dispatched.add(invocation.getArgument(0));
            return null;
        }).when(managedExecutor).execute(any(Runnable.class));

        observer.onInvoiceCommitted(new InvoiceAttributionsDirtyEvent("invoice-1"));
        observer.onInvoiceCommitted(new InvoiceAttributionsDirtyEvent("invoice-2"));

        assertEquals(2, dispatched.size());
        dispatched.get(1).run();
        dispatched.get(0).run();

        var completionOrder = inOrder(dirtyMarker);
        completionOrder.verify(dirtyMarker).completeAsyncMutation(
                PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION, "owner-2");
        completionOrder.verify(dirtyMarker).completeAsyncMutation(
                PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION, "owner-1");
        verify(attributionService).computeAttributions("invoice-1");
        verify(attributionService).computeAttributions("invoice-2");
        verify(dirtyMarker, never()).mark(any(), any());
    }
}
