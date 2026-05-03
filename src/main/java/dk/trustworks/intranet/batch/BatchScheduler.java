package dk.trustworks.intranet.batch;

import jakarta.batch.operations.JobOperator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.util.Properties;

@JBossLog
@ApplicationScoped
public class BatchScheduler {

    @Inject
    JobOperator jobOperator;

    @Inject
    EntityManager em;

    /**
     * Kill switch for expense-consume — the only scheduled job that POSTs vouchers
     * to e-conomics. Staging's deploy sets this to {@code false} so a polluted
     * staging DB can never produce real e-conomics vouchers.
     */
    @ConfigProperty(name = "dk.trustworks.expense.economics-upload.enabled", defaultValue = "true")
    boolean expenseUploadEnabled;

    /**
     * BI nightly refresh observability heartbeat.
     *
     * The actual work is performed by the MariaDB event ev_bi_nightly_refresh,
     * which calls sp_nightly_bi_refresh(3, 24) at 03:00 UTC daily. The procedure
     * routinely runs longer than the Quarkus JTA transaction timeout, so calling
     * it from a @Transactional Java method consistently produced
     * QueryTimeoutException noise without ever completing the work.
     *
     * This method now logs a heartbeat 5 minutes after the MariaDB event starts,
     * so log timelines still show the nightly refresh window.
     */
    @Scheduled(cron = "0 5 3 * * ?")
    void trigger() {
        log.info("BI nightly refresh handled by MariaDB event ev_bi_nightly_refresh; Quarkus side observing only");
    }

    // Finance loads
    //
    // Guarded against concurrent execution: FinanceLoadJob.loadEconomicsData()
    // first calls economicsService.clean() (deleteAll on finance_details) and
    // then re-inserts. Two simultaneous runs can race such that the second's
    // clean() arrives AFTER the first's inserts commit, leaving 2x rows in
    // finance_details — the root cause of V303's dedup migration. Same pattern
    // applied to scheduleFinanceInvoiceSync for consistency.
    @Scheduled(cron = "0 0 21 * * ?")
    void scheduleFinanceLoadEconomics() {
        try {
            if (jobOperator.getJobNames().contains("finance-load-economics")) {
                if (!jobOperator.getRunningExecutions("finance-load-economics").isEmpty()) {
                    log.info("finance-load-economics already running, skipping this cycle");
                    return;
                }
            }
            jobOperator.start("finance-load-economics", new Properties());
        } catch (Exception e) {
            log.warn("Could not schedule finance-load-economics: " + e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 22 * * ?")
    void scheduleFinanceInvoiceSync() {
        try {
            if (jobOperator.getJobNames().contains("finance-invoice-sync")) {
                if (!jobOperator.getRunningExecutions("finance-invoice-sync").isEmpty()) {
                    log.info("finance-invoice-sync already running, skipping this cycle");
                    return;
                }
            }
            jobOperator.start("finance-invoice-sync", new Properties());
        } catch (Exception e) {
            log.warn("Could not schedule finance-invoice-sync: " + e.getMessage());
        }
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
    @Scheduled(cron = "0 15 01 L * ?")
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
        try {
            if (jobOperator.getJobNames().contains("mail-send")) {
                if (!jobOperator.getRunningExecutions("mail-send").isEmpty()) {
                    return; // Already running, skip this cycle
                }
            }
            jobOperator.start("mail-send", new Properties());
        } catch (Exception e) {
            log.warn("Could not schedule mail-send: " + e.getMessage());
        }
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
        if (!expenseUploadEnabled) {
            log.debug("expense-consume skipped: dk.trustworks.expense.economics-upload.enabled=false");
            return;
        }
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

    // Moved from 03:00 to 05:00 UTC: the BI nightly refresh (MariaDB event
    // ev_bi_nightly_refresh) starts at 03:00 and rebuilds fact_user_day /
    // fact_budget_day, holding row locks across the same expense-related
    // tables. Running expense-sync at 03:00 produced 150+
    // "Lock wait timeout exceeded" errors per week. 05:00 is well clear of
    // the BI window (typically completes by 04:05).
    @Scheduled(cron = "0 0 5 * * ?")
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
            log.warn("Could not schedule expense-sync: " + e.getMessage());
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
            log.warn("Could not schedule economics-invoice-status-sync: " + e.getMessage());
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

    // Economics upload processing - runs every 1 minute to process pending/failed uploads
    @Scheduled(cron = "0 * * * * ?")
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

    // CV Tool sync - daily at 04:00
    @Scheduled(cron = "0 0 4 * * ?")
    void scheduleCvToolSync() {
        try {
            if (jobOperator.getJobNames().contains("cvtool-sync")) {
                if (!jobOperator.getRunningExecutions("cvtool-sync").isEmpty()) {
                    log.debug("cvtool-sync already running, skipping");
                    return;
                }
            }
            log.info("Starting cvtool-sync batch job");
            jobOperator.start("cvtool-sync", new Properties());
        } catch (Exception e) {
            log.warn("Could not schedule cvtool-sync: " + e.getMessage());
        }
    }

    /**
     * Fetch pending NextSign case statuses.
     *
     * Handles race condition where NextSign needs time before newly created cases
     * are queryable. Instead of blocking REST endpoints, cases are saved with
     * PENDING_FETCH status and this job fetches the full status asynchronously.
     *
     * Schedule: Every 5 minutes (responsive UX while avoiding excessive API calls)
     * Pattern: Simple batchlet with retry logic and max retry limit
     *
     * Processing:
     * - Finds cases with processing_status = PENDING_FETCH or FAILED
     * - Fetches status from NextSign for each case
     * - Updates database with fetched status (marks as COMPLETED)
     * - On failure: marks as FAILED and retries after delay
     * - After max retries: logs error (manual intervention needed)
     */
    @Scheduled(every = "5m")  // Every 5 minutes
    void scheduleNextSignStatusSync() {
        try {
            // Safety check: prevent duplicate executions
            if (jobOperator.getJobNames().contains("nextsign-status-sync")) {
                if (!jobOperator.getRunningExecutions("nextsign-status-sync").isEmpty()) {
                    log.debug("nextsign-status-sync already running, skipping");
                    return;
                }
            }

            log.debug("Starting nextsign-status-sync batch job");
            jobOperator.start("nextsign-status-sync", new Properties());

        } catch (Exception e) {
            // Log at debug level since this runs frequently
            log.debug("Could not schedule nextsign-status-sync: " + e.getMessage());
        }
    }

}
