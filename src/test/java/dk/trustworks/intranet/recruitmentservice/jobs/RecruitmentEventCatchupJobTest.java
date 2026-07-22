package dk.trustworks.intranet.recruitmentservice.jobs;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchRuntime;
import jakarta.batch.runtime.BatchStatus;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventTestSupport.awaitTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end run of the {@code recruitment-event-catchup} JBeret job: job
 * XML resolves, the batchlet bean is found, every registered reactor gets a
 * sweep, and the job completes. (The scheduler gate is off in tests —
 * {@code dk.trustworks.recruitment.catchup.enabled=false} — so this is the
 * only place the job runs during the suite; reactor semantics themselves
 * are covered in {@code RecruitmentReactorIntegrationTest}.)
 */
@QuarkusTest
class RecruitmentEventCatchupJobTest {

    @Test
    void catchupJob_runsAllReactors_andCompletes() {
        JobOperator jobOperator = BatchRuntime.getJobOperator();
        long executionId = jobOperator.start("recruitment-event-catchup", new Properties());

        assertTrue(awaitTrue(() -> {
            BatchStatus status = jobOperator.getJobExecution(executionId).getBatchStatus();
            return status == BatchStatus.COMPLETED || status == BatchStatus.FAILED;
        }, 60_000), "recruitment-event-catchup did not finish within 60s");

        assertEquals(BatchStatus.COMPLETED, jobOperator.getJobExecution(executionId).getBatchStatus(),
                "exit status: " + jobOperator.getJobExecution(executionId).getExitStatus());
    }
}
