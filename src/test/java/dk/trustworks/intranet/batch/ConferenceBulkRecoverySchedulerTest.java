package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.communicationsservice.services.BulkEmailService;
import jakarta.batch.operations.JobOperator;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.Properties;
import static org.mockito.Mockito.*;

class ConferenceBulkRecoverySchedulerTest {
    private BatchScheduler scheduler() {
        BatchScheduler scheduler = new BatchScheduler();
        scheduler.jobOperator = mock(JobOperator.class);
        scheduler.bulkEmailService = mock(BulkEmailService.class);
        when(scheduler.jobOperator.getJobNames()).thenReturn(Set.of("bulk-mail-send"));
        return scheduler;
    }
    @Test void activeBulkExecutionPreventsRecoveryAndNewDispatch() {
        BatchScheduler scheduler = scheduler();
        when(scheduler.jobOperator.getRunningExecutions("bulk-mail-send")).thenReturn(List.of(1L));
        scheduler.scheduleBulkMailSend();
        verifyNoInteractions(scheduler.bulkEmailService);
        verify(scheduler.jobOperator, never()).start(anyString(), any(Properties.class));
    }
    @Test void orphanRecoveryPrecedesFreshJobStartAtExistingSchedule() {
        BatchScheduler scheduler = scheduler();
        when(scheduler.jobOperator.getRunningExecutions("bulk-mail-send")).thenReturn(List.of());
        scheduler.scheduleBulkMailSend();
        var ordered = inOrder(scheduler.bulkEmailService, scheduler.jobOperator);
        ordered.verify(scheduler.bulkEmailService).recoverInterruptedConferenceJobs();
        ordered.verify(scheduler.jobOperator).start(eq("bulk-mail-send"), any(Properties.class));
    }
}
