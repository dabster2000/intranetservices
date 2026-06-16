package dk.trustworks.intranet.aggregates.invoice.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueuedInternalInvoiceProcessorBatchletTest {

    @Mock QueuedInternalInvoiceFinalizer finalizer;
    @InjectMocks QueuedInternalInvoiceProcessorBatchlet batchlet;

    @Test
    void one_invoice_failure_does_not_stop_the_loop() throws Exception {
        when(finalizer.findFirstPassUuids()).thenReturn(List.of("A", "B"));
        when(finalizer.findSettlementUuids()).thenReturn(List.of());
        when(finalizer.processOne("A")).thenThrow(new RuntimeException("boom"));
        when(finalizer.processOne("B")).thenReturn(QueuedInternalInvoiceFinalizer.Outcome.PROCESSED);

        batchlet.process();

        verify(finalizer).processOne("A");
        verify(finalizer).processOne("B"); // loop continued past A's failure — proves isolation contract
    }
}
