package dk.trustworks.intranet.batch;

import jakarta.batch.operations.JobOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchSchedulerInvoiceUploadGateTest {

    @Mock JobOperator jobOperator;
    @InjectMocks BatchScheduler scheduler;

    @Test
    void queuedInternalInvoiceProcessor_skips_when_invoice_upload_disabled() {
        scheduler.invoiceUploadEnabled = false;
        scheduler.scheduleQueuedInternalInvoiceProcessor();
        verifyNoInteractions(jobOperator);
    }

    @Test
    void economicsUploadRetry_skips_when_invoice_upload_disabled() {
        scheduler.invoiceUploadEnabled = false;
        scheduler.scheduleEconomicsUploadRetry();
        verifyNoInteractions(jobOperator);
    }

    @Test
    void queuedInternalInvoiceProcessor_starts_when_enabled() {
        scheduler.invoiceUploadEnabled = true;
        Set<String> jobs = new HashSet<>();
        jobs.add("queued-internal-invoice-processor");
        when(jobOperator.getJobNames()).thenReturn(jobs);
        when(jobOperator.getRunningExecutions("queued-internal-invoice-processor")).thenReturn(List.of());
        scheduler.scheduleQueuedInternalInvoiceProcessor();
        verify(jobOperator).start(eq("queued-internal-invoice-processor"), any(Properties.class));
    }
}
