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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the physical kill switch for the expense-consume batch job.
 *
 * <p>Context (2026-04-24 incident): a typo in V258 staging-sync allowed production
 * VALIDATED expenses to be copied into staging DB with status intact; staging then
 * uploaded them to e-conomics journal 15 and poisoned the idempotency keys for the
 * real production run to journal 16. The SQL-only safeguard failed silently.
 *
 * <p>This test pins a second, physical line of defense: a config flag that is read
 * from env var {@code EXPENSE_ECONOMICS_UPLOAD_ENABLED}. When set to {@code false}
 * (staging deployment), the scheduler refuses to start the {@code expense-consume}
 * job regardless of what's in the DB.
 */
@ExtendWith(MockitoExtension.class)
class BatchSchedulerExpenseUploadGateTest {

    @Mock JobOperator jobOperator;

    @InjectMocks BatchScheduler scheduler;

    @Test
    void scheduleExpenseConsume_skips_when_upload_disabled() {
        scheduler.expenseUploadEnabled = false;

        scheduler.scheduleExpenseConsume();

        verifyNoInteractions(jobOperator);
    }

    @Test
    void scheduleExpenseConsume_starts_job_when_upload_enabled() {
        scheduler.expenseUploadEnabled = true;
        Set<String> jobs = new HashSet<>();
        jobs.add("expense-consume");
        when(jobOperator.getJobNames()).thenReturn(jobs);
        when(jobOperator.getRunningExecutions("expense-consume")).thenReturn(List.of());

        scheduler.scheduleExpenseConsume();

        verify(jobOperator).start(eq("expense-consume"), any(Properties.class));
    }

    @Test
    void scheduleExpenseConsume_skips_when_already_running_even_if_enabled() {
        scheduler.expenseUploadEnabled = true;
        Set<String> jobs = new HashSet<>();
        jobs.add("expense-consume");
        when(jobOperator.getJobNames()).thenReturn(jobs);
        when(jobOperator.getRunningExecutions("expense-consume")).thenReturn(List.of(1L));

        scheduler.scheduleExpenseConsume();

        verify(jobOperator, never()).start(eq("expense-consume"), any(Properties.class));
    }
}
