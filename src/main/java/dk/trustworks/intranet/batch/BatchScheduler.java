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
     * Kill switch for internal-invoice e-conomic booking — the queued-internal-invoice
     * processor and the economics-upload retry both POST to the shared e-conomic. Staging's
     * deploy sets this to {@code false} so a staging-sync-polluted DB can never book real
     * internal invoices (2026-06-16 duplicate incident).
     */
    @ConfigProperty(name = "dk.trustworks.invoice.economics-upload.enabled", defaultValue = "true")
    boolean invoiceUploadEnabled;

    /**
     * Read-side kill switch for the expense e-conomic read batches (expense-sync
     * and expense-orphan-detection). Staging's deploy sets this to {@code false}
     * so a prod-cloned staging DB never re-syncs against the shared real e-conomic
     * tenant (2026-06-20 429-storm incident).
     */
    @ConfigProperty(name = "dk.trustworks.expense.economics-sync.enabled", defaultValue = "true")
    boolean economicsSyncEnabled;

    /**
     * Read-side kill switch for economics-invoice-status-sync. Staging false.
     */
    @ConfigProperty(name = "dk.trustworks.invoice.economics-sync.enabled", defaultValue = "true")
    boolean invoiceSyncEnabled;

    /**
     * Read-side kill switch for finance-load-economics. Staging false.
     */
    @ConfigProperty(name = "dk.trustworks.finance.economics-load.enabled", defaultValue = "true")
    boolean financeLoadEnabled;

    // Observability heartbeat. The actual nightly BI refresh is run by the
    // MariaDB event ev_bi_nightly_refresh at 03:00 UTC; it runs longer than
    // any reasonable Quarkus JTA transaction timeout.
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
        if (!financeLoadEnabled) {
            log.debug("finance-load-economics skipped: dk.trustworks.finance.economics-load.enabled=false");
            return;
        }
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

    // 5m cadence (was 1m): mail delivery SLA tolerates it and this cuts JBeret JOB_*/STEP_EXECUTION metadata churn ~80%.
    @Scheduled(every = "5m")
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

    // 5m cadence (was 1m): same rationale as scheduleMailSend — reduce empty-cycle job starts and metadata churn.
    @Scheduled(every = "5m")
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

    // 05:00 UTC: must run after the BI nightly refresh window (starts 03:00,
    // typically clears by 04:05) to avoid lock-wait contention on expense rows.
    @Scheduled(cron = "0 0 5 * * ?")
    void scheduleExpenseSync() {
        if (!economicsSyncEnabled) {
            log.debug("expense-sync skipped: dk.trustworks.expense.economics-sync.enabled=false");
            return;
        }
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
        if (!economicsSyncEnabled) {
            log.debug("expense-orphan-detection skipped: dk.trustworks.expense.economics-sync.enabled=false");
            return;
        }
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
        if (!invoiceSyncEnabled) {
            log.debug("economics-invoice-status-sync skipped: dk.trustworks.invoice.economics-sync.enabled=false");
            return;
        }
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
        if (!invoiceUploadEnabled) {
            log.debug("queued-internal-invoice-processor skipped: dk.trustworks.invoice.economics-upload.enabled=false");
            return;
        }
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

    // Economics upload processing - runs every 5 minutes to process pending/failed uploads.
    // (Was every 1 minute; the upload service's own exponential backoff windows (1m→5m→15m→1h→4h)
    //  make sub-5-minute polling pointless and it just churns JBeret job-instance metadata.)
    @Scheduled(cron = "0 */5 * * * ?")
    void scheduleEconomicsUploadRetry() {
        if (!invoiceUploadEnabled) {
            log.debug("economics-upload-retry skipped: dk.trustworks.invoice.economics-upload.enabled=false");
            return;
        }
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

    /**
     * Drain the SharePoint candidate folder move queue.
     * <p>
     * Picks up rows in {@code recruitment_candidates} where status=HIRED and
     * sharepoint_move_status=PENDING and copies the candidate's recruitment
     * folder to the new employee's home folder, then deletes the source.
     * Out-of-band from the convert HTTP transaction because Graph API calls
     * cannot be rolled back. Idempotent — re-running on a row already in
     * a terminal state (COMPLETED / PARTIAL / FAILED) is a no-op since the
     * batchlet's query filters on PENDING.
     * <p>
     * Schedule: every 5 minutes — matches the cadence of nextsign-status-sync,
     * which is the closest analogue (also a queue-drain over an external API).
     */
    @Scheduled(every = "5m")
    void scheduleSharePointEmployeeFolderMove() {
        try {
            if (jobOperator.getJobNames().contains("sharepoint-employee-folder-move")) {
                if (!jobOperator.getRunningExecutions("sharepoint-employee-folder-move").isEmpty()) {
                    log.debug("sharepoint-employee-folder-move already running, skipping");
                    return;
                }
            }
            log.debug("Starting sharepoint-employee-folder-move batch job");
            jobOperator.start("sharepoint-employee-folder-move", new Properties());
        } catch (Exception e) {
            log.debug("Could not schedule sharepoint-employee-folder-move: " + e.getMessage());
        }
    }

    /**
     * Fan out HR notifications when a dossier-linked NextSign signing case
     * completes and its signed documents have been uploaded to SharePoint.
     * Joins signing_cases to candidate_dossier_revisions to detect dossier-
     * linked completions, then queues mails per configured recipient under
     * {@code recruitment.completion-notification.{company-uuid}}.
     * <p>
     * Schedule: every 5 minutes — same cadence as nextsign-status-sync so
     * notifications go out within one cycle of the upstream upload completing.
     * Dedup is in-memory only (cleared on JVM restart) — see the listener's
     * class javadoc for the trade-off.
     */
    @Scheduled(every = "5m")
    void scheduleRecruitmentSignatureCompletion() {
        try {
            if (jobOperator.getJobNames().contains("recruitment-signature-completion")) {
                if (!jobOperator.getRunningExecutions("recruitment-signature-completion").isEmpty()) {
                    log.debug("recruitment-signature-completion already running, skipping");
                    return;
                }
            }
            log.debug("Starting recruitment-signature-completion batch job");
            jobOperator.start("recruitment-signature-completion", new Properties());
        } catch (Exception e) {
            log.debug("Could not schedule recruitment-signature-completion: " + e.getMessage());
        }
    }

}
