package dk.trustworks.intranet.signing.jobs;

import dk.trustworks.intranet.agreementservice.services.AgreementRecorder;
import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.PromotionStatus;
import dk.trustworks.intranet.recruitmentservice.services.S3EmployeePromotionService;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import dk.trustworks.intranet.signing.services.EmployeeSigningArchivalService;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseStatus;
import dk.trustworks.intranet.utils.services.SigningService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Batch job to fetch pending NextSign case statuses asynchronously.
 *
 * Purpose:
 * Handles race condition where NextSign API returns 404 when fetching case status
 * immediately after creation. Instead of blocking the REST endpoint, cases are saved
 * with PENDING_FETCH status and this batch job fetches the full status asynchronously.
 *
 * Processing Flow:
 * 1. Find cases needing status fetch — new cases (PENDING_FETCH), FAILED cases
 *    with retries remaining (plus a slow retry lane for exhausted ones), and
 *    fetched cases whose signing status is not yet terminal. In-flight cases
 *    keep polling until NextSign reports completed/expired/rejected/denied/
 *    cancelled — signatures arrive days after creation, and downstream
 *    consumers (recruitment summary endpoint, completion listener, archival
 *    sweep) all read the polled row.
 * 2. For each case, attempt to fetch status from NextSign
 * 3. On success: update database and mark as COMPLETED
 * 4. On 404: mark as FAILED and retry later (NextSign not ready yet)
 * 5. On other errors: mark as FAILED with error message
 * 6. After max fast retries: 404s are abandoned as SKIPPED (case is gone from
 *    NextSign); other errors drop to the repository's slow retry lane so an
 *    outage cannot orphan cases permanently
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

    @Inject
    SlackService slackService;

    @Inject
    EmployeeSigningArchivalService employeeSigningArchivalService;

    @Inject
    S3EmployeePromotionService s3EmployeePromotionService;

    @Inject
    AgreementRecorder agreementRecorder;

    /** Bound per pass so a stuck case cannot monopolize the sweep. */
    private static final int ARCHIVAL_SWEEP_LIMIT = 25;

    /** Bound per pass — promotions are multi-file, keep the batch small. */
    private static final int PROMOTION_SWEEP_LIMIT = 10;

    /**
     * Maximum fast-lane retry attempts. With 15min delay between retries
     * = ~75min fast retry window. Exhaustion is not a dead end: 404 cases
     * are abandoned as SKIPPED, all other failures drop to the repository's
     * slow retry lane (see SigningCaseRepository#findCasesNeedingStatusFetch).
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

        // S3 archival catch-up + promotion re-drive (employee-documents
        // spec §6.5.1/§6.5.3). Never fail the status-fetch pass over sweep
        // errors.
        //
        // Hoisted above the early return deliberately: these sweeps are not
        // conditional on there being signing cases to poll. An idle poll set
        // is a normal state, and it is exactly when catch-up work is due —
        // sitting below the return meant a passing pass skipped them.
        int archived = 0;
        int promotionsRedriven = 0;
        try {
            archived = runArchivalCatchupSweep();
            promotionsRedriven = runPromotionRedriveSweep();
        } catch (Exception e) {
            log.errorf(e, "employee-documents sweeps failed (status fetch pass unaffected)");
        }

        // Agreement registry catch-up (template-clauses spec §8, Phase 3):
        // completed cases whose clause snapshots never became registry rows
        // — covers completions that predate the Phase 3 deploy and any
        // failed inline recording below. Bounded + idempotent; never
        // fails the pass (the recorder swallows internally too).
        int agreementsRecorded = agreementRecorder.runCatchupSweep();

        // Find cases needing status fetch
        List<SigningCase> pendingCases = signingCaseRepository.findCasesNeedingStatusFetch(
            MAX_RETRIES, RETRY_DELAY_MINUTES
        );

        if (pendingCases.isEmpty()) {
            log.info("No pending cases found. Skipping status fetch.");
            return String.format(
                    "COMPLETED: 0 cases processed, archived=%d, promotionsRedriven=%d, agreementsRecorded=%d",
                    archived, promotionsRedriven, agreementsRecorded);
        }

        log.infof("Found %d cases needing status fetch", pendingCases.size());

        int successful = 0;
        int failed = 0;
        int skipped = 0;

        // Process each pending case
        for (SigningCase signingCase : pendingCases) {
            String caseKey = signingCase.getCaseKey();

            try {
                // Defensive guard: terminal signing cases cannot produce an uploadable
                // document and must not incur another external status request.
                if (signingService.markCaseSkippedIfTerminal(signingCase)) {
                    log.warnf("Skipping terminal signing case %s (status: %s)",
                        caseKey, signingCase.getStatus());
                    skipped++;
                    continue;
                }

                log.debugf("Fetching status for case: %s (attempt %d)",
                    caseKey, signingCase.getRetryCount() + 1);

                // Mark as FETCHING to prevent duplicate processing by concurrent job runs
                signingCase.setProcessingStatus("FETCHING");
                signingCaseRepository.persist(signingCase);

                // Fetch status from NextSign
                SigningCaseStatus status = signingService.getStatus(caseKey);

                // Update case with fetched status (COMPLETED or terminal SKIPPED)
                boolean terminalSkipped = signingService.updateCaseWithFetchedStatus(signingCase, status);

                if (terminalSkipped) {
                    log.warnf("Case %s reached terminal status '%s'; future polling skipped",
                        caseKey, status.status());
                    skipped++;
                    continue;
                }

                log.infof("Successfully fetched and updated status for case: %s", caseKey);
                successful++;

                // Check if signing is complete and needs archival: signed
                // documents archive to S3 (employee store / candidate
                // staging — spec §6.5.1-2).
                if (isSigningComplete(status)) {
                    // Registry rows for the case's clauses (Phase 3) — same
                    // transaction as the completion update; idempotent, and
                    // the recorder never lets a failure roll the update back.
                    agreementsRecorded += agreementRecorder.recordCompletedCase(signingCase);

                    employeeSigningArchivalService.archiveCompletedCase(signingCase);
                }

            } catch (Exception e) {
                String errorMsg = e.getMessage();

                // "Case gone" comes in two shapes: the typed exception for
                // NextSign's documented HTTP-200 {"message":"Case not found"}
                // body (deleted cases, incl. auto-deletion), and an HTTP 404
                // during the create-then-fetch race window.
                boolean notFound = e instanceof SigningService.CaseNotFoundInNextsignException
                    || (errorMsg != null && errorMsg.contains("404"));
                if (notFound) {
                    log.warnf("Case %s not available in NextSign, will retry later", caseKey);
                    signingService.markCaseFetchFailed(signingCase, "Case not available in NextSign");
                    failed++;

                } else {
                    // Other error (network issue, auth failure, etc.)
                    log.errorf(e, "Failed to fetch status for case %s: %s", caseKey, errorMsg);
                    signingService.markCaseFetchFailed(signingCase, errorMsg);
                    failed++;
                }

                if (signingCase.getRetryCount() >= MAX_RETRIES) {
                    if (notFound) {
                        // A whole fast-retry window of consecutive not-founds goes
                        // far beyond the create-then-fetch race this loop was built
                        // for: the case no longer exists in NextSign (deleted, or
                        // never durably created). Abandon it permanently — the slow
                        // retry lane is for outages, not dead case keys. WARN, not
                        // ERROR: this is expected lifecycle for old deleted cases.
                        signingService.markCaseMissingInNextsign(signingCase);
                        log.warnf("Case %s abandoned: not found in NextSign after %d attempts",
                            caseKey, signingCase.getRetryCount());
                    } else {
                        log.errorf("Case %s exceeded fast retries (%d); slow retry lane takes over",
                            caseKey, MAX_RETRIES);
                    }
                    skipped++;
                }
            }
        }

        // Build result summary
        String result = String.format(
            "COMPLETED: total=%d, successful=%d, failed=%d, skipped=%d, archived=%d, promotionsRedriven=%d, agreementsRecorded=%d",
            pendingCases.size(), successful, failed, skipped, archived, promotionsRedriven, agreementsRecorded
        );

        log.info("NextSignStatusSyncBatchlet finished: " + result);
        return result;
    }

    // ========================================================================
    // EMPLOYEE-DOCUMENTS SWEEPS (spec §6.5.1 / §6.5.3)
    // ========================================================================

    /**
     * Archive completed-but-unarchived cases to S3 — the durability-gap
     * catch-up plus any case whose inline archival failed on a previous
     * pass. Bounded per pass, and per case by the archive-attempt cap
     * (V551) inside the archival service.
     *
     * <p>Cases the retired SharePoint auto-upload path already stored are
     * out of this sweep's reach even though the {@code sharepoint_*}
     * predicate that used to exclude them is gone with the entity mapping:
     * V556 moved that population to SKIPPED (their bytes came in with the
     * SharePoint→S3 migration), and the archival service refuses to
     * re-download a case whose migrated {@code _signed_} copies are already
     * in the store ({@code EmployeeSigningArchivalService#legacyMigratedCopies}).</p>
     *
     * @return cases archived this pass
     */
    private int runArchivalCatchupSweep() {
        List<SigningCase> candidates = signingCaseRepository.find(
                "archiveStatus = 'PENDING' AND processingStatus = 'COMPLETED' " +
                "AND status = 'COMPLETED' " +
                "ORDER BY createdAt ASC")
                .page(0, ARCHIVAL_SWEEP_LIMIT)
                .list();
        int archived = 0;
        for (SigningCase signingCase : candidates) {
            if (employeeSigningArchivalService.archiveCompletedCase(signingCase)) {
                archived++;
            }
        }
        if (!candidates.isEmpty()) {
            log.infof("Archival catch-up sweep: %d/%d cases archived", archived, candidates.size());
        }
        return archived;
    }

    /**
     * Re-drive PENDING/FAILED S3→S3 promotions (spec §6.5.3). Bounded per
     * pass; idempotent per file via {@code migrated_from} provenance.
     *
     * @return candidates re-driven this pass
     */
    private int runPromotionRedriveSweep() {
        List<RecruitmentCandidate> pending = RecruitmentCandidate.find(
                "status = ?1 AND (promotionStatus = ?2 OR promotionStatus = ?3)",
                CandidateStatus.HIRED, PromotionStatus.PENDING, PromotionStatus.FAILED)
                .page(0, PROMOTION_SWEEP_LIMIT)
                .list();
        for (RecruitmentCandidate candidate : pending) {
            try {
                s3EmployeePromotionService.runPromotion(UUID.fromString(candidate.getUuid()));
            } catch (Exception e) {
                log.errorf(e, "Promotion re-drive failed candidate=%s", candidate.getUuid());
            }
        }
        if (!pending.isEmpty()) {
            log.infof("Promotion re-drive sweep: %d candidates processed", pending.size());
        }
        return pending.size();
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

    // ========================================================================
    // COMPLETION DETECTION
    // ========================================================================

    /**
     * Checks if all signers have completed signing.
     *
     * @param status The signing case status from NextSign
     * @return true if all signers have signed
     */
    private boolean isSigningComplete(SigningCaseStatus status) {
        if (status == null) {
            return false;
        }
        // Check if status is "completed" or all signers have signed
        if ("completed".equalsIgnoreCase(status.status())) {
            return true;
        }
        return status.totalSigners() > 0 && status.completedSigners() >= status.totalSigners();
    }
}
