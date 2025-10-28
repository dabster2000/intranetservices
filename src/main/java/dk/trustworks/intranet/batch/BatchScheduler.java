package dk.trustworks.intranet.batch;

import jakarta.batch.operations.JobOperator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.Properties;

@JBossLog
@ApplicationScoped
public class BatchScheduler {

    @Inject
    JobOperator jobOperator;

    @Scheduled(cron = "0 0 3 ? * 7-1")
    //@Scheduled(every = "12h")
    void trigger() {
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(24);
        LocalDate end   = LocalDate.now().withDayOfMonth(1).plusMonths(24);

        Properties params = new Properties();
        params.setProperty("startDate", start.toString());
        params.setProperty("endDate", end.toString());

        // Let config drive concurrency; or override here if needed:
        params.setProperty("threads", "12");
        log.infof("Starting BI date update with start=%s end=%s", start, end);
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
    //@Scheduled(cron = "0 0 5 * * ?")
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
     */

    @Scheduled(every = "1m")
    void scheduleMailSend() {
        jobOperator.start("mail-send", new Properties());
    }

    @Scheduled(every = "1m")
    void scheduleBulkMailSend() {
        try {
            // Only start if no bulk-mail-send job is currently running
            if (jobOperator.getJobNames().contains("bulk-mail-send")) {
                if (!jobOperator.getRunningExecutions("bulk-mail-send").isEmpty()) {
                    return; // Already running, skip this cycle
                }
            }
            jobOperator.start("bulk-mail-send", new Properties());
        } catch (Exception e) {
            // Log and continue - will retry in next cycle
            log.warn("Could not schedule bulk-mail-send: " + e.getMessage());
        }
    }

    @Scheduled(every = "1h")
    void scheduleExpenseConsume() {
        try {
            // Only start if no expense-consume job is currently running
            if (jobOperator.getJobNames().contains("expense-consume")) {
                if (!jobOperator.getRunningExecutions("expense-consume").isEmpty()) {
                    log.debug("expense-consume job already running, skipping this cycle");
                    return; // Already running, skip this cycle
                }
            }
            log.info("Starting expense-consume job");
            jobOperator.start("expense-consume", new Properties());
        } catch (Exception e) {
            // Log and continue - will retry in next cycle
            log.warn("Could not schedule expense-consume: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
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

    @Scheduled(cron = "0 15 * * * ?") // Run every hour at 15 minutes past
    void scheduleExpenseOrphanDetection() {
        try {
            // Only start if no orphan detection job is currently running
            if (jobOperator.getJobNames().contains("expense-orphan-detection")) {
                if (!jobOperator.getRunningExecutions("expense-orphan-detection").isEmpty()) {
                    log.debug("expense-orphan-detection job already running, skipping this cycle");
                    return;
                }
            }
            log.info("Starting expense-orphan-detection job");
            jobOperator.start("expense-orphan-detection", new Properties());
        } catch (Exception e) {
            log.warn("Could not schedule expense-orphan-detection: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 38 12 * * ?")
    void scheduleEconomicsInvoiceStatusSync() {
        try {
            if (jobOperator.getJobNames().contains("economics-invoice-status-sync")) {
                if (!jobOperator.getRunningExecutions("economics-invoice-status-sync").isEmpty()) {
                    return; // one is already running
                }
            }
            log.info("Starting economics-invoice-status-sync");
            jobOperator.start("economics-invoice-status-sync", new Properties());
        } catch (Exception e) {
            jobOperator.start("economics-invoice-status-sync", new Properties());
        }
    }

    // Queued internal invoice processor - runs daily at 2 AM
    @Scheduled(cron = "0 0 2 * * ?")
    void scheduleQueuedInternalInvoiceProcessor() {
        try {
            if (jobOperator.getJobNames().contains("queued-internal-invoice-processor")) {
                if (!jobOperator.getRunningExecutions("queued-internal-invoice-processor").isEmpty()) {
                    return; // one is already running
                }
            }
            log.info("Starting queued-internal-invoice-processor");
            jobOperator.start("queued-internal-invoice-processor", new Properties());
        } catch (Exception e) {
            log.warn("Could not schedule queued-internal-invoice-processor: " + e.getMessage());
            // Retry on exception
            try {
                jobOperator.start("queued-internal-invoice-processor", new Properties());
            } catch (Exception ex) {
                log.error("Failed to start queued-internal-invoice-processor after retry", ex);
            }
        }
    }

    // Economics upload retry - runs every 15 minutes to retry failed uploads
    @Scheduled(cron = "0 */15 * * * ?")
    void scheduleEconomicsUploadRetry() {
        try {
            if (jobOperator.getJobNames().contains("economics-upload-retry")) {
                if (!jobOperator.getRunningExecutions("economics-upload-retry").isEmpty()) {
                    return; // one is already running
                }
            }
            log.debug("Starting economics-upload-retry");
            jobOperator.start("economics-upload-retry", new Properties());
        } catch (Exception e) {
            // Log at debug level since this runs frequently
            log.debug("Could not schedule economics-upload-retry: " + e.getMessage());
        }
    }

}
