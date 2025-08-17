package dk.trustworks.intranet.expenseservice.ai.batch;

import io.quarkus.scheduler.Scheduled;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchRuntime;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.JobExecution;
import jakarta.batch.runtime.JobInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Properties;

@JBossLog
@ApplicationScoped
public class ExpenseInsightBackfillScheduler {

    @Inject
    JobOperator injectedJobOperator; // may be null in some environments; fallback to BatchRuntime

    @ConfigProperty(name = "expense.backfill.limit", defaultValue = "50")
    int limit;

    @ConfigProperty(name = "expense.backfill.sleep-millis", defaultValue = "1500")
    long sleepMillis;

    private JobOperator jobOperator() {
        try {
            return injectedJobOperator != null ? injectedJobOperator : BatchRuntime.getJobOperator();
        } catch (Exception e) {
            return BatchRuntime.getJobOperator();
        }
    }

    @Scheduled(cron = "0 17 17 * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void runNightlyBackfill() {
        try {
            JobOperator op = jobOperator();
            if (isJobRunning(op, "expense-insight-backfill")) {
                log.warnf("expense-insight-backfill already running; skipping launch");
                return;
            }
            Properties props = new Properties();
            props.setProperty("limit", String.valueOf(limit));
            props.setProperty("sleepMillis", String.valueOf(sleepMillis));
            long execId = op.start("expense-insight-backfill", props);
            log.infof("Started expense-insight-backfill executionId=%d with limit=%d sleepMillis=%d", execId, limit, sleepMillis);
        } catch (Exception e) {
            log.error("Failed to start expense-insight-backfill", e);
        }
    }

    private boolean isJobRunning(JobOperator op, String jobName) {
        try {
            // Check last few instances for a STARTING/STARTED execution
            List<JobInstance> instances = op.getJobInstances(jobName, 0, 5);
            for (JobInstance ji : instances) {
                List<JobExecution> execs = op.getJobExecutions(ji);
                for (JobExecution je : execs) {
                    BatchStatus s = je.getBatchStatus();
                    if (s == BatchStatus.STARTING || s == BatchStatus.STARTED) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }
}
