package dk.trustworks.intranet.signing.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseStatus;
import dk.trustworks.intranet.utils.services.SigningService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * Batch job to fetch pending NextSign case statuses asynchronously.
 *
 * Purpose:
 * Handles race condition where NextSign API returns 404 when fetching case status
 * immediately after creation. Instead of blocking the REST endpoint, cases are saved
 * with PENDING_FETCH status and this batch job fetches the full status asynchronously.
 *
 * Processing Flow:
 * 1. Find cases needing status fetch (PENDING_FETCH or FAILED with retries remaining)
 * 2. For each case, attempt to fetch status from NextSign
 * 3. On success: update database and mark as COMPLETED
 * 4. On 404: mark as FAILED and retry later (NextSign not ready yet)
 * 5. On other errors: mark as FAILED with error message
 * 6. After max retries: log and skip (manual intervention needed)
 *
 * Schedule:
 * Runs every 5 minutes via BatchScheduler.scheduleNextSignStatusSync()
 *
 * Pattern:
 * Extends MonitoredBatchlet for automatic exception tracking and persistence.
 * Based on EconomicsInvoiceStatusSyncBatchlet - proven pattern for external API polling.
 *
 * @see dk.trustworks.intranet.financeservice.jobs.EconomicsInvoiceStatusSyncBatchlet
 * @see dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet
 */
@JBossLog
@Dependent
@Named("nextSignStatusSyncBatchlet")
public class NextSignStatusSyncBatchlet extends MonitoredBatchlet {

    @Inject
    SigningCaseRepository signingCaseRepository;

    @Inject
    SigningService signingService;

    /**
     * Maximum retry attempts before giving up.
     * With 15min delay between retries = ~75min total retry window.
     */
    private static final int MAX_RETRIES = 5;

    /**
     * Minutes to wait before retrying a failed case.
     * Prevents hammering NextSign API when cases aren't ready yet.
     */
    private static final int RETRY_DELAY_MINUTES = 15;

    /**
     * Main processing method.
     * Finds pending cases and fetches their status from NextSign.
     *
     * @return Summary string describing processing results
     * @throws Exception if critical error occurs (will be caught by MonitoredBatchlet)
     */
    @Override
    @Transactional
    protected String doProcess() throws Exception {
        log.info("NextSignStatusSyncBatchlet: Starting status fetch for pending cases");

        // Find cases needing status fetch
        List<SigningCase> pendingCases = signingCaseRepository.findCasesNeedingStatusFetch(
            MAX_RETRIES, RETRY_DELAY_MINUTES
        );

        if (pendingCases.isEmpty()) {
            log.info("No pending cases found. Skipping.");
            return "COMPLETED: 0 cases processed";
        }

        log.infof("Found %d cases needing status fetch", pendingCases.size());

        int successful = 0;
        int failed = 0;
        int skipped = 0;

        // Process each pending case
        for (SigningCase signingCase : pendingCases) {
            String caseKey = signingCase.getCaseKey();

            try {
                log.debugf("Fetching status for case: %s (attempt %d)",
                    caseKey, signingCase.getRetryCount() + 1);

                // Mark as FETCHING to prevent duplicate processing by concurrent job runs
                signingCase.setProcessingStatus("FETCHING");
                signingCaseRepository.persist(signingCase);

                // Fetch status from NextSign
                SigningCaseStatus status = signingService.getStatus(caseKey);

                // Update case with fetched status (marks as COMPLETED)
                signingService.updateCaseWithFetchedStatus(signingCase, status);

                log.infof("Successfully fetched and updated status for case: %s", caseKey);
                successful++;

            } catch (Exception e) {
                String errorMsg = e.getMessage();

                // Check if it's a 404 (case not ready yet - expected during race condition window)
                if (errorMsg != null && errorMsg.contains("404")) {
                    log.warnf("Case %s still not available (404), will retry later", caseKey);
                    signingService.markCaseFetchFailed(signingCase, "Case not yet available in NextSign");
                    failed++;

                } else {
                    // Other error (network issue, auth failure, etc.)
                    log.errorf(e, "Failed to fetch status for case %s: %s", caseKey, errorMsg);
                    signingService.markCaseFetchFailed(signingCase, errorMsg);
                    failed++;
                }

                // Check if max retries exceeded (needs manual intervention)
                if (signingCase.getRetryCount() >= MAX_RETRIES) {
                    log.errorf("Case %s exceeded max retries (%d), manual intervention needed",
                        caseKey, MAX_RETRIES);
                    skipped++;
                }
            }
        }

        // Build result summary
        String result = String.format(
            "COMPLETED: total=%d, successful=%d, failed=%d, skipped=%d",
            pendingCases.size(), successful, failed, skipped
        );

        log.info("NextSignStatusSyncBatchlet finished: " + result);
        return result;
    }

    /**
     * Cleanup callback after job execution (success or failure).
     * Override from MonitoredBatchlet.
     *
     * @param executionId JBeret execution ID
     * @param jobName Job name
     */
    @Override
    protected void onFinally(long executionId, String jobName) {
        log.debugf("Cleanup after job %s (execution %d)", jobName, executionId);
        // No cleanup needed - all database operations are transactional
    }
}
