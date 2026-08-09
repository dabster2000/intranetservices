package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.services.InvoiceEconomicsUploadService;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.stream.Collectors;

/**
 * Batch job that processes pending e-conomics uploads and retries failed ones.
 *
 * <p>This job runs every 1 minute and:
 * <ol>
 *   <li>Processes brand-new PENDING uploads (never attempted) - initial upload attempt</li>
 *   <li>Retries FAILED uploads with exponential backoff:
 *     <ul>
 *       <li>Attempt 1: 1 minute wait</li>
 *       <li>Attempt 2: 5 minutes wait</li>
 *       <li>Attempt 3: 15 minutes wait</li>
 *       <li>Attempt 4: 1 hour wait</li>
 *       <li>Attempt 5: 4 hours wait</li>
 *     </ul>
 *   </li>
 *   <li>Updates status based on result</li>
 * </ol>
 *
 * <p>Uploads that exceed max_attempts remain in FAILED status and require manual intervention.
 *
 * @see InvoiceEconomicsUploadService#processPendingUploads()
 * @see InvoiceEconomicsUploadService#retryFailedUploads()
 */
@JBossLog
@Named("economicsUploadRetryBatchlet")
@Dependent
@BatchExceptionTracking
public class EconomicsUploadRetryBatchlet extends AbstractBatchlet {

    @Inject
    InvoiceEconomicsUploadService uploadService;

    @Override
    @Transactional
    public String process() throws Exception {
        log.info("EconomicsUploadRetryBatchlet started");

        try {
            // Get current stats before processing
            InvoiceEconomicsUploadService.UploadStats statsBefore = uploadService.getUploadStats();
            log.infof("Upload stats before processing: pending=%d, success=%d, failed=%d, retryable=%d, terminal=%d",
                    statsBefore.pending(), statsBefore.success(), statsBefore.failed(),
                    statsBefore.retryable(), statsBefore.terminal());

            // Process brand-new pending uploads (never attempted)
            int pendingProcessed = uploadService.processPendingUploads();

            // Retry previously failed uploads with exponential backoff
            int failedRetried = uploadService.retryFailedUploads();

            // Get stats after processing
            InvoiceEconomicsUploadService.UploadStats statsAfter = uploadService.getUploadStats();
            log.infof("Upload stats after processing: pending=%d, success=%d, failed=%d, retryable=%d, terminal=%d",
                    statsAfter.pending(), statsAfter.success(), statsAfter.failed(),
                    statsAfter.retryable(), statsAfter.terminal());

            alertTerminalFailures(statsBefore, statsAfter);

            log.infof("EconomicsUploadRetryBatchlet completed: %d pending processed, %d failed retried",
                    pendingProcessed, failedRetried);
            return "COMPLETED";
        } catch (Exception e) {
            log.error("EconomicsUploadRetryBatchlet failed", e);
            throw e;
        }
    }

    /**
     * Surfaces permanently-failed uploads instead of letting them sit in an INFO counter.
     *
     * <p>The token {@code ECONOMICS_UPLOAD_TERMINAL_FAILED} feeds the CloudWatch metric filter
     * created by {@code scripts/setup-invoice-booking-alarms.sh}; the alarm stays red for as
     * long as the dead-letter set is non-empty. The line is ERROR on the run where an upload
     * first exhausts its retries (a new, actionable event) and a WARN heartbeat on every later
     * run, so log-based error monitoring sees each terminal failure exactly once.
     *
     * <p>Resolution: inspect {@code GET /invoices/{uuid}/economics-upload-status}, then either
     * re-arm via {@code POST /invoices/{uuid}/retry-economics-upload} (after fixing the cause)
     * or close the row as ABANDONED if the document already exists in e-conomic.
     */
    private void alertTerminalFailures(InvoiceEconomicsUploadService.UploadStats before,
                                       InvoiceEconomicsUploadService.UploadStats after) {
        if (after.terminal() == 0) {
            return;
        }
        String detail = uploadService.findTerminalFailures().stream()
                .limit(20)
                .map(u -> "invoice=" + u.getInvoiceuuid() + " type=" + u.getUploadType()
                        + " attempts=" + u.getAttemptCount() + "/" + u.getMaxAttempts())
                .collect(Collectors.joining("; "));

        if (after.terminal() > before.terminal()) {
            log.errorf("ECONOMICS_UPLOAD_TERMINAL_FAILED: %d upload(s) permanently failed, %d exhausted retries this run. "
                            + "These will NEVER retry automatically — re-arm via POST /invoices/{uuid}/retry-economics-upload "
                            + "or mark ABANDONED after verifying in e-conomic. [%s]",
                    after.terminal(), after.terminal() - before.terminal(), detail);
        } else {
            log.warnf("ECONOMICS_UPLOAD_TERMINAL_FAILED: %d upload(s) remain permanently failed and need manual resolution. [%s]",
                    after.terminal(), detail);
        }
    }
}
