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
 * Batch job that retries failed e-conomics uploads with exponential backoff.
 *
 * <p>This job runs every 15 minutes and:
 * <ol>
 *   <li>Finds failed uploads that are eligible for retry (attempt_count < max_attempts)</li>
 *   <li>Applies exponential backoff based on attempt count:
 *     <ul>
 *       <li>Attempt 1: 1 minute wait</li>
 *       <li>Attempt 2: 5 minutes wait</li>
 *       <li>Attempt 3: 15 minutes wait</li>
 *       <li>Attempt 4: 1 hour wait</li>
 *       <li>Attempt 5: 4 hours wait</li>
 *     </ul>
 *   </li>
 *   <li>Retries eligible uploads</li>
 *   <li>Updates status based on retry result</li>
 * </ol>
 *
 * <p>Uploads that exceed max_attempts remain in FAILED status and require manual intervention.
 *
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
            // Get current stats before retry
            InvoiceEconomicsUploadService.UploadStats statsBefore = uploadService.getUploadStats();
            log.infof("Upload stats before retry: pending=%d, success=%d, failed=%d, retryable=%d",
                    statsBefore.pending(), statsBefore.success(), statsBefore.failed(), statsBefore.retryable());

            // Perform retries
            int processed = uploadService.retryFailedUploads();

            // Get stats after retry
            InvoiceEconomicsUploadService.UploadStats statsAfter = uploadService.getUploadStats();
            log.infof("Upload stats after retry: pending=%d, success=%d, failed=%d, retryable=%d",
                    statsAfter.pending(), statsAfter.success(), statsAfter.failed(), statsAfter.retryable());

            log.infof("EconomicsUploadRetryBatchlet completed: processed %d retries", processed);
            return "COMPLETED";
        } catch (Exception e) {
            log.error("EconomicsUploadRetryBatchlet failed", e);
            throw e;
        }
    }
}
