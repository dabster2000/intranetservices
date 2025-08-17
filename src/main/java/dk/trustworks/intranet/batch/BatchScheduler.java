package dk.trustworks.intranet.batch;

import jakarta.batch.operations.JobOperator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.scheduler.Scheduled;

import java.time.LocalDate;
import java.util.Properties;

@ApplicationScoped
public class BatchScheduler {

    @Inject
    JobOperator jobOperator;

    @Scheduled(cron = "0 33 23 ? * 2-6")
    void trigger() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(2);
        LocalDate end   = LocalDate.now().withDayOfMonth(1).plusMonths(24);

        Properties params = new Properties();
        params.setProperty("startDate", start.toString());
        params.setProperty("endDate", end.toString());

        // Let config drive concurrency; or override here if needed:
        params.setProperty("threads", "12");

        jobOperator.start("bi-date-update", params);
    }

    // Finance loads
    @Scheduled(cron = "0 0 21 * * ?")
    void scheduleFinanceLoadEconomics() {
        jobOperator.start("finance-load-economics", new Properties());
    }

    @Scheduled(cron = "0 0 22 * * ?")
    void scheduleFinanceInvoiceSync() {
        jobOperator.start("finance-invoice-sync", new Properties());
    }

    // Slack sync
    @Scheduled(cron = "0 30 2 * * ?")
    void scheduleSlackUserSync() {
        jobOperator.start("slack-user-sync", new Properties());
    }

    // Birthdays
    @Scheduled(cron = "0 0 5 * * ?")
    void scheduleBirthdayNotifications() {
        jobOperator.start("birthday-notification", new Properties());
    }

    // Team description monthly (10th at 10:00)
    @Scheduled(cron = "0 0 10 10 * ?")
    void scheduleTeamDescription() {
        jobOperator.start("team-description", new Properties());
    }

    // Project lock daily at midnight
    @Scheduled(cron = "0 0 0 * * ?")
    void scheduleProjectLock() {
        jobOperator.start("project-lock", new Properties());
    }

    // Periodic maintenance
    @Scheduled(every = "24h")
    void scheduleUserResumeUpdate() {
        jobOperator.start("user-resume-update", new Properties());
    }

    //@Scheduled(every = "24h")
    /*
    void scheduleRevenueCacheRefresh() {
        jobOperator.start("revenue-cache-refresh", new Properties());
    }

    @Scheduled(every = "10s")
    void scheduleMailSend() {
        jobOperator.start("mail-send", new Properties());
    }
     */

    @Scheduled(every = "5m")
    void scheduleExpenseConsume() {
        jobOperator.start("expense-consume", new Properties());
    }

    @Scheduled(every = "24h", delayed = "1m")
    void scheduleExpenseSync() {
        try {
            // Only query running executions if the job is known to the repository
            if (jobOperator.getJobNames().contains("expense-sync")) {
                if (!jobOperator.getRunningExecutions("expense-sync").isEmpty()) {
                    return; // one is already running
                }
            }
            jobOperator.start("expense-sync", new Properties());
        } catch (Exception e) {
            // Log and do not fail the scheduler; it will try again in next tick
            // If job still isn’t known, the next cycle will typically succeed
            // once the repository has fully initialized
            // (You can narrow this to NoSuchJobException if you prefer)
            // log.warn("Could not schedule expense-sync now: " + e.getMessage(), e);
            jobOperator.start("expense-sync", new Properties());
        }
    }

}
