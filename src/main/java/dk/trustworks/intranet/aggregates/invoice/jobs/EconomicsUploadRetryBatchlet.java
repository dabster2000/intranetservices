package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.services.InvoiceEconomicsUploadService;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

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
            log.infof("Upload stats before processing: pending=%d, success=%d, failed=%d, retryable=%d",
                    statsBefore.pending(), statsBefore.success(), statsBefore.failed(), statsBefore.retryable());

            // Process brand-new pending uploads (never attempted)
            int pendingProcessed = uploadService.processPendingUploads();

            // Retry previously failed uploads with exponential backoff
            int failedRetried = uploadService.retryFailedUploads();

            // Get stats after processing
            InvoiceEconomicsUploadService.UploadStats statsAfter = uploadService.getUploadStats();
            log.infof("Upload stats after processing: pending=%d, success=%d, failed=%d, retryable=%d",
                    statsAfter.pending(), statsAfter.success(), statsAfter.failed(), statsAfter.retryable());

            log.infof("EconomicsUploadRetryBatchlet completed: %d pending processed, %d failed retried",
                    pendingProcessed, failedRetried);
            return "COMPLETED";
        } catch (Exception e) {
            log.error("EconomicsUploadRetryBatchlet failed", e);
            throw e;
        }
    }
}
