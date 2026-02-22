package dk.trustworks.intranet.signing.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.documentservice.model.TemplateSigningStoreEntity;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseStatus;
import dk.trustworks.intranet.utils.services.SigningService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    @Inject
    SharePointService sharePointService;

    @Inject
    SlackService slackService;

    private static final DateTimeFormatter FILENAME_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

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

                // Check if signing is complete and needs SharePoint upload
                if (isSigningComplete(status) && shouldUploadToSharePoint(signingCase)) {
                    uploadSignedDocumentToSharePoint(signingCase);
                }

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

    // ========================================================================
    // SHAREPOINT AUTO-UPLOAD METHODS
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

    /**
     * Checks if the case should be uploaded to SharePoint.
     *
     * @param signingCase The signing case entity
     * @return true if upload is needed
     */
    private boolean shouldUploadToSharePoint(SigningCase signingCase) {
        // Must have a signing store configured
        if (signingCase.getSigningStoreUuid() == null || signingCase.getSigningStoreUuid().isBlank()) {
            return false;
        }
        // Must not already be uploaded or partially uploaded
        String uploadStatus = signingCase.getSharepointUploadStatus();
        return !"UPLOADED".equals(uploadStatus) && !"PARTIAL_FAILURE".equals(uploadStatus);
    }

    /**
     * Downloads ALL signed documents from NextSign and uploads them to SharePoint.
     * Handles multi-document cases by iterating over every signed document.
     * Uses partial failure handling: continues uploading remaining documents if one fails.
     *
     * @param signingCase The signing case entity
     */
    private void uploadSignedDocumentToSharePoint(SigningCase signingCase) {
        String caseKey = signingCase.getCaseKey();
        String signingStoreUuid = signingCase.getSigningStoreUuid();

        log.infof("Starting SharePoint upload for case: %s (store: %s)", caseKey, signingStoreUuid);

        try {
            // 1. Get signing store configuration
            TemplateSigningStoreEntity store = TemplateSigningStoreEntity.findById(signingStoreUuid);
            if (store == null) {
                String error = "Signing store not found: " + signingStoreUuid;
                log.errorf(error);
                markSharePointUploadFailed(signingCase, error);
                return;
            }

            if (!Boolean.TRUE.equals(store.getIsActive())) {
                log.warnf("Signing store %s is inactive, skipping upload for case %s",
                    signingStoreUuid, caseKey);
                return;
            }

            // 2. Download ALL signed documents from NextSign (single status call)
            log.debugf("Downloading all signed documents for case: %s", caseKey);
            List<SigningService.SignedDocumentDownload> documents =
                signingService.downloadAllSignedDocuments(caseKey);

            if (documents.isEmpty()) {
                String error = "No signed documents downloaded for case: " + caseKey;
                log.errorf(error);
                markSharePointUploadFailed(signingCase, error);
                return;
            }

            log.infof("Downloaded %d signed documents for case %s", documents.size(), caseKey);

            // 3. Construct folder path with user subdirectory if enabled
            String uploadFolderPath = buildUploadFolderPath(store, signingCase);

            // 4. Ensure folder exists (create if necessary)
            if (uploadFolderPath != null && !uploadFolderPath.isBlank()) {
                log.debugf("Ensuring folder exists before upload: %s", uploadFolderPath);
                try {
                    sharePointService.ensureFolderExists(
                        store.getSiteUrl(),
                        store.getDriveName(),
                        uploadFolderPath
                    );
                    log.debugf("Folder verified/created successfully");
                } catch (Exception e) {
                    log.warnf(e, "Could not ensure folder exists: %s - will attempt upload anyway", uploadFolderPath);
                }
            }

            // 5. Upload each document, tracking successes and failures
            String timestamp = LocalDateTime.now().format(FILENAME_DATE_FORMATTER);
            List<String> uploadedUrls = new ArrayList<>();
            List<String> failedDocNames = new ArrayList<>();

            int uploadIdx = 0;
            for (SigningService.SignedDocumentDownload doc : documents) {
                uploadIdx++;
                String docDisplayName = doc.name() != null ? doc.name() : "document_" + doc.index();
                try {
                    // Use original document name from NextSign
                    String baseFilename = sanitizeFilename(doc.name());
                    String filename = String.format("%s_signed_%s.pdf", baseFilename, timestamp);

                    log.infof("Uploading document %d/%d to SharePoint: %s",
                        uploadIdx, documents.size(), filename);

                    DriveItem result = sharePointService.uploadFile(
                        store.getSiteUrl(),
                        store.getDriveName(),
                        uploadFolderPath,
                        filename,
                        doc.pdfBytes()
                    );

                    uploadedUrls.add(result.webUrl());
                    log.infof("Successfully uploaded document '%s' to SharePoint: %s",
                        docDisplayName, result.webUrl());

                } catch (Exception e) {
                    log.errorf(e, "Failed to upload document '%s' (index %d) for case %s: %s",
                        docDisplayName, doc.index(), caseKey, e.getMessage());
                    failedDocNames.add(docDisplayName);
                }
            }

            // 6. Update case status based on results
            if (uploadedUrls.isEmpty()) {
                // All documents failed
                String error = String.format("All %d documents failed to upload", documents.size());
                markSharePointUploadFailed(signingCase, error);
            } else if (!failedDocNames.isEmpty()) {
                // Partial success
                signingCase.setSharepointUploadStatus("PARTIAL_FAILURE");
                signingCase.setSharepointFileUrl(String.join(" | ", uploadedUrls));
                signingCase.setSharepointUploadError(
                    "Failed documents: " + String.join(", ", failedDocNames));
                signingCaseRepository.persist(signingCase);
                log.warnf("Partial upload for case %s: %d/%d documents uploaded",
                    caseKey, uploadedUrls.size(), documents.size());
            } else {
                // All documents uploaded successfully
                signingCase.setSharepointUploadStatus("UPLOADED");
                signingCase.setSharepointFileUrl(String.join(" | ", uploadedUrls));
                signingCase.setSharepointUploadError(null);
                signingCaseRepository.persist(signingCase);
                log.infof("Successfully uploaded all %d signed documents for case %s to SharePoint",
                    uploadedUrls.size(), caseKey);
            }

            // 7. Send Slack notification (only if at least some documents uploaded)
            if (!uploadedUrls.isEmpty()) {
                notifyUserOfSignedDocumentUpload(signingCase);
            }

        } catch (Exception e) {
            log.errorf(e, "Failed to upload signed documents to SharePoint for case: %s", caseKey);
            markSharePointUploadFailed(signingCase, e.getMessage());
        }
    }

    /**
     * Marks a case as having failed SharePoint upload.
     *
     * @param signingCase The signing case entity
     * @param error The error message
     */
    private void markSharePointUploadFailed(SigningCase signingCase, String error) {
        signingCase.setSharepointUploadStatus("FAILED");
        signingCase.setSharepointUploadError(error);
        signingCaseRepository.persist(signingCase);
    }

    /**
     * Builds the upload folder path, optionally appending username subdirectory.
     *
     * Logic:
     * - If store.userDirectory = false: return original folder path
     * - If store.userDirectory = true: append "/{username}" to folder path
     * - Handles null/empty folder paths gracefully
     * - Sanitizes username to prevent path injection
     *
     * Examples:
     * - basePath="Contracts/2025", userDirectory=false → "Contracts/2025"
     * - basePath="Contracts/2025", userDirectory=true, username="hans.lassen" → "Contracts/2025/hans.lassen"
     * - basePath=null, userDirectory=true, username="hans.lassen" → "hans.lassen"
     *
     * @param store The signing store entity
     * @param signingCase The signing case entity
     * @return The constructed folder path
     */
    private String buildUploadFolderPath(TemplateSigningStoreEntity store, SigningCase signingCase) {
        String basePath = store.getFolderPath();

        // If user directory not enabled, return original path
        if (!Boolean.TRUE.equals(store.getUserDirectory())) {
            return basePath;
        }

        // User directory enabled - need to append username
        String userUuid = signingCase.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            log.warnf("User directory enabled but signingCase.userUuid is null for case %s - using base path",
                signingCase.getCaseKey());
            return basePath;
        }

        // Lookup user entity to get username
        Optional<User> userOpt = User.findByIdOptional(userUuid);
        if (userOpt.isEmpty()) {
            log.warnf("User directory enabled but user %s not found for case %s - using base path",
                userUuid, signingCase.getCaseKey());
            return basePath;
        }

        User user = userOpt.get();
        String username = user.getUsername();

        if (username == null || username.isBlank()) {
            log.warnf("User directory enabled but username is null for user %s, case %s - using base path",
                userUuid, signingCase.getCaseKey());
            return basePath;
        }

        // Sanitize username to prevent path injection attacks
        String sanitizedUsername = sanitizePathSegment(username);

        // Build combined path
        if (basePath == null || basePath.isBlank()) {
            log.debugf("Empty base path, using username subdirectory only: %s", sanitizedUsername);
            return sanitizedUsername;
        }

        // Normalize path separators and combine
        String normalizedBase = basePath.trim().replaceAll("[\\\\/]+$", ""); // Remove trailing slashes
        String finalPath = normalizedBase + "/" + sanitizedUsername;

        log.infof("User directory enabled: base=%s + username=%s → final=%s",
            basePath, sanitizedUsername, finalPath);

        return finalPath;
    }

    /**
     * Sanitizes a path segment to prevent directory traversal attacks.
     * Removes or replaces dangerous characters while preserving readable usernames.
     *
     * Examples:
     * - "hans.lassen" → "hans.lassen" (safe, preserved)
     * - "../admin" → "admin" (path traversal removed)
     * - "user/../../etc" → "user_etc" (slashes replaced)
     *
     * @param segment The path segment to sanitize
     * @return Sanitized path segment safe for use in file paths
     */
    private String sanitizePathSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return "unknown_user";
        }

        // Remove path traversal attempts
        String cleaned = segment
            .replaceAll("\\.\\.", "")  // Remove ".."
            .replaceAll("^[\\\\/]+", "")  // Remove leading slashes
            .replaceAll("[\\\\/]+$", ""); // Remove trailing slashes

        // Replace potentially problematic characters with underscore
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|@#$%^&]", "_");

        // Collapse multiple underscores
        cleaned = cleaned.replaceAll("_+", "_");

        // Trim and ensure not empty
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            return "unknown_user";
        }

        return cleaned;
    }

    /**
     * Sanitizes a filename by removing/replacing invalid characters.
     *
     * @param filename The original filename
     * @return Sanitized filename safe for filesystem
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "document";
        }
        // Remove file extension if present
        String name = filename;
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0) {
            name = name.substring(0, lastDot);
        }
        // Replace invalid characters with underscores
        return name.replaceAll("[^a-zA-Z0-9æøåÆØÅ._-]", "_")
                   .replaceAll("_+", "_")
                   .replaceAll("^_|_$", "");
    }

    // ========================================================================
    // SLACK NOTIFICATION METHODS
    // ========================================================================

    /**
     * Sends a Slack notification to the case owner that their document
     * has been signed and uploaded to SharePoint.
     *
     * Graceful degradation: notification failures are logged but do not
     * fail the overall upload process.
     *
     * @param signingCase The signing case that was uploaded
     */
    private void notifyUserOfSignedDocumentUpload(SigningCase signingCase) {
        try {
            // Lookup user
            String userUuid = signingCase.getUserUuid();
            if (userUuid == null || userUuid.isBlank()) {
                log.warnf("Cannot send notification: no userUuid for case %s",
                    signingCase.getCaseKey());
                return;
            }

            Optional<User> userOpt = User.findByIdOptional(userUuid);
            if (userOpt.isEmpty()) {
                log.warnf("Cannot send notification: user %s not found for case %s",
                    userUuid, signingCase.getCaseKey());
                return;
            }

            User user = userOpt.get();

            // Check if user has Slack configured
            if (user.getSlackusername() == null || user.getSlackusername().isBlank()) {
                log.debugf("User %s has no Slack username, skipping notification",
                    user.getUsername());
                return;
            }

            // Send notification
            slackService.sendSignedDocumentNotification(
                user,
                signingCase.getDocumentName(),
                signingCase.getUpdatedAt()
            );

            log.infof("Sent Slack notification to %s for signed document: %s",
                user.getUsername(), signingCase.getDocumentName());

        } catch (Exception e) {
            // Graceful degradation - log but don't fail the upload
            log.warnf(e, "Failed to send Slack notification for case %s: %s",
                signingCase.getCaseKey(), e.getMessage());
        }
    }
}
