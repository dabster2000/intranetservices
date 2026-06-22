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

/**
 * Pins the read-side e-conomic kill switches added for the 429-storm fix: when a
 * flag is false (staging deploy) the scheduler refuses to start the corresponding
 * read batch, so a prod-cloned staging DB never re-syncs against the shared real
 * e-conomic tenant. Mirrors BatchSchedulerExpenseUploadGateTest.
 */
@ExtendWith(MockitoExtension.class)
class BatchSchedulerEconomicsReadGateTest {

    @Mock JobOperator jobOperator;

    @InjectMocks BatchScheduler scheduler;

    private void enableRunnable(String jobName) {
        Set<String> jobs = new HashSet<>();
        jobs.add(jobName);
        when(jobOperator.getJobNames()).thenReturn(jobs);
        when(jobOperator.getRunningExecutions(jobName)).thenReturn(List.of());
    }

    // expense.economics-sync.enabled -> expense-sync + expense-orphan-detection

    @Test
    void expenseSync_skips_when_disabled() {
        scheduler.economicsSyncEnabled = false;
        scheduler.scheduleExpenseSync();
        verifyNoInteractions(jobOperator);
    }

    @Test
    void expenseSync_starts_when_enabled() {
        scheduler.economicsSyncEnabled = true;
        enableRunnable("expense-sync");
        scheduler.scheduleExpenseSync();
        verify(jobOperator).start(eq("expense-sync"), any(Properties.class));
    }

    @Test
    void expenseOrphanDetection_skips_when_disabled() {
        scheduler.economicsSyncEnabled = false;
        scheduler.scheduleExpenseOrphanDetection();
        verifyNoInteractions(jobOperator);
    }

    @Test
    void expenseOrphanDetection_starts_when_enabled() {
        scheduler.economicsSyncEnabled = true;
        enableRunnable("expense-orphan-detection");
        scheduler.scheduleExpenseOrphanDetection();
        verify(jobOperator).start(eq("expense-orphan-detection"), any(Properties.class));
    }

    // invoice.economics-sync.enabled -> economics-invoice-status-sync

    @Test
    void invoiceStatusSync_skips_when_disabled() {
        scheduler.invoiceSyncEnabled = false;
        scheduler.scheduleEconomicsInvoiceStatusSync();
        verifyNoInteractions(jobOperator);
    }

    @Test
    void invoiceStatusSync_starts_when_enabled() {
        scheduler.invoiceSyncEnabled = true;
        enableRunnable("economics-invoice-status-sync");
        scheduler.scheduleEconomicsInvoiceStatusSync();
        verify(jobOperator).start(eq("economics-invoice-status-sync"), any(Properties.class));
    }

    // finance.economics-load.enabled -> finance-load-economics

    @Test
    void financeLoad_skips_when_disabled() {
        scheduler.financeLoadEnabled = false;
        scheduler.scheduleFinanceLoadEconomics();
        verifyNoInteractions(jobOperator);
    }

    @Test
    void financeLoad_starts_when_enabled() {
        scheduler.financeLoadEnabled = true;
        enableRunnable("finance-load-economics");
        scheduler.scheduleFinanceLoadEconomics();
        verify(jobOperator).start(eq("finance-load-economics"), any(Properties.class));
    }
}
