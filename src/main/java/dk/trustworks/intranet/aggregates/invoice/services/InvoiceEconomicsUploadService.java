package dk.trustworks.intranet.aggregates.invoice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceEconomicsUpload;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceEconomicsUpload.UploadStatus;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceEconomicsUpload.UploadType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service for managing e-conomics uploads with robust retry logic and state tracking.
 *
 * <p>This service decouples invoice creation from e-conomics upload, enabling:
 * <ul>
 *   <li>Partial success handling (one upload succeeds, another fails)</li>
 *   <li>Automatic retries with exponential backoff</li>
 *   <li>Idempotent uploads (won't double-upload on retry)</li>
 *   <li>Observable upload status (query current state)</li>
 *   <li>Audit trail (complete history of upload attempts)</li>
 * </ul>
 *
 * <h2>Usage Flow</h2>
 * <pre>
 * 1. Create invoice → CREATED status
 * 2. queueUploads() → Create upload tasks
 * 3. processUploads() → Execute pending uploads
 * 4. Invoice economics_status updated based on results
 * 5. retryFailedUploads() → Batch job retries failures
 * </pre>
 *
 * @see InvoiceEconomicsUpload
 * @see EconomicsInvoiceService
 */
@JBossLog
@ApplicationScoped
public class InvoiceEconomicsUploadService {

    @Inject
    EconomicsInvoiceService economicsInvoiceService;

    @Inject
    EntityManager em;

    /**
     * Result of processing uploads for an invoice.
     */
    public record UploadResult(int successCount, int failedCount, int totalCount) {
        public boolean allSucceeded() {
            return successCount == totalCount && failedCount == 0;
        }

        public boolean partialSuccess() {
            return successCount > 0 && successCount < totalCount;
        }

        public boolean allFailed() {
            return successCount == 0 && failedCount > 0;
        }
    }

    /**
     * Queues e-conomics uploads for an invoice.
     *
     * <p>For normal invoices, creates one upload task (issuer).
     * For internal invoices with debtor company, creates two upload tasks (issuer + debtor).
     *
     * @param invoice Invoice to queue uploads for
     */
    @Transactional
    public void queueUploads(Invoice invoice) {
        log.infof("Queueing uploads for invoice %s (number: %d)",
                invoice.getUuid(), invoice.getInvoicenumber());

        // Issuer upload task
        createUploadTask(
                invoice.getUuid(),
                invoice.getCompany().getUuid(),
                UploadType.ISSUER,
                IntegrationKey.getIntegrationKeyValue(invoice.getCompany()).invoiceJournalNumber()
        );

        // Debtor upload task (for internal invoices)
        if (invoice.getDebtorCompanyuuid() != null && !invoice.getDebtorCompanyuuid().isBlank()) {
            Company debtorCompany = Company.findById(invoice.getDebtorCompanyuuid());
            if (debtorCompany != null) {
                createUploadTask(
                        invoice.getUuid(),
                        debtorCompany.getUuid(),
                        UploadType.DEBTOR,
                        IntegrationKey.getIntegrationKeyValue(debtorCompany).internalJournalNumber()
                );
            }
        }

        // Update invoice status to PENDING
        invoice.setEconomicsStatus(EconomicsInvoiceStatus.PENDING);
        invoice.persist();

        log.infof("Queued uploads for invoice %s", invoice.getUuid());
    }

    /**
     * Creates an upload task if it doesn't already exist.
     */
    private void createUploadTask(String invoiceuuid, String companyuuid,
                                   UploadType uploadType, int journalNumber) {
        // Check if upload task already exists
        InvoiceEconomicsUpload existing = InvoiceEconomicsUpload.find(
                "invoiceuuid = ?1 AND companyuuid = ?2 AND uploadType = ?3",
                invoiceuuid, companyuuid, uploadType
        ).firstResult();

        if (existing != null) {
            log.debugf("Upload task already exists for invoice %s, company %s, type %s",
                    invoiceuuid, companyuuid, uploadType);
            return;
        }

        InvoiceEconomicsUpload upload = new InvoiceEconomicsUpload(
                invoiceuuid, companyuuid, uploadType, journalNumber
        );
        upload.persist();

        log.debugf("Created upload task: %s", upload.getUuid());
    }

    /**
     * Processes all pending uploads for an invoice.
     *
     * @param invoiceuuid Invoice UUID
     * @return Upload result summary
     */
    @Transactional
    public UploadResult processUploads(String invoiceuuid) {
        log.infof("Processing uploads for invoice %s", invoiceuuid);

        Invoice invoice = Invoice.findById(invoiceuuid);
        if (invoice == null) {
            throw new IllegalArgumentException("Invoice not found: " + invoiceuuid);
        }

        List<InvoiceEconomicsUpload> pendingUploads = InvoiceEconomicsUpload.list(
                "invoiceuuid = ?1 AND status = ?2",
                invoiceuuid, UploadStatus.PENDING
        );

        if (pendingUploads.isEmpty()) {
            log.debugf("No pending uploads for invoice %s", invoiceuuid);
            return new UploadResult(0, 0, 0);
        }

        int successCount = 0;
        int failedCount = 0;

        for (InvoiceEconomicsUpload upload : pendingUploads) {
            boolean success = attemptUpload(invoice, upload);
            if (success) {
                successCount++;
            } else {
                failedCount++;
            }
        }

        // Update invoice economics status based on results
        updateInvoiceEconomicsStatus(invoice, successCount, failedCount, pendingUploads.size());

        UploadResult result = new UploadResult(successCount, failedCount, pendingUploads.size());
        log.infof("Upload processing completed for invoice %s: %s", invoiceuuid, result);

        return result;
    }

    /**
     * Attempts to upload an invoice to e-conomics.
     *
     * @param invoice Invoice to upload
     * @param upload Upload task
     * @return true if upload succeeded
     */
    private boolean attemptUpload(Invoice invoice, InvoiceEconomicsUpload upload) {
        log.infof("Attempting upload %s (type: %s, attempt: %d)",
                upload.getUuid(), upload.getUploadType(), upload.getAttemptCount() + 1);

        try {
            Company targetCompany = Company.findById(upload.getCompanyuuid());
            if (targetCompany == null) {
                upload.markFailed("Target company not found: " + upload.getCompanyuuid());
                upload.persist();
                return false;
            }

            // Perform upload to e-conomics
            try (Response response = economicsInvoiceService.sendVoucherToCompany(
                    invoice, targetCompany, upload.getJournalNumber())) {

                if (response.getStatus() >= 200 && response.getStatus() < 300) {
                    // Extract voucher number from response
                    int voucherNumber = extractVoucherNumber(response);
                    upload.markSuccess(voucherNumber);
                    upload.persist();

                    log.infof("Upload %s succeeded (voucher: %d)", upload.getUuid(), voucherNumber);
                    return true;
                } else {
                    String errorBody = response.readEntity(String.class);
                    String error = String.format("HTTP %d: %s", response.getStatus(), errorBody);
                    upload.markFailed(error);
                    upload.persist();

                    log.warnf("Upload %s failed: %s", upload.getUuid(), error);
                    return false;
                }
            }
        } catch (Exception e) {
            String error = e.getClass().getSimpleName() + ": " + e.getMessage();
            upload.markFailed(error);
            upload.persist();

            log.errorf(e, "Upload %s failed with exception", upload.getUuid());
            return false;
        }
    }

    /**
     * Extracts voucher number from e-conomics API response.
     */
    private int extractVoucherNumber(Response response) {
        try {
            String responseBody = response.readEntity(String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode array = mapper.readValue(responseBody, JsonNode.class);
            if (array.isArray() && !array.isEmpty()) {
                return array.get(0).get("voucherNumber").asInt();
            }
            return 0;
        } catch (Exception e) {
            log.warn("Failed to extract voucher number from response", e);
            return 0;
        }
    }

    /**
     * Updates invoice economics_status based on upload results.
     */
    private void updateInvoiceEconomicsStatus(Invoice invoice, int successCount,
                                               int failedCount, int totalCount) {
        EconomicsInvoiceStatus newStatus;

        if (successCount == totalCount) {
            // All uploads succeeded
            newStatus = EconomicsInvoiceStatus.UPLOADED;
        } else if (successCount > 0) {
            // Partial success
            newStatus = EconomicsInvoiceStatus.PARTIALLY_UPLOADED;
        } else if (failedCount == totalCount) {
            // All failed, keep pending or set back to NA
            newStatus = EconomicsInvoiceStatus.PENDING;
        } else {
            // No uploads processed (shouldn't happen)
            newStatus = invoice.getEconomicsStatus();
        }

        log.infof("Updating invoice %s economics_status: %s → %s",
                invoice.getUuid(), invoice.getEconomicsStatus(), newStatus);

        invoice.setEconomicsStatus(newStatus);
        invoice.persist();
    }

    /**
     * Retries failed uploads with exponential backoff.
     *
     * <p>Called by scheduled batch job to automatically retry failed uploads.
     * Uses exponential backoff: 1min, 5min, 15min, 1hr, 4hr between attempts.
     *
     * @return Number of uploads processed
     */
    @Transactional
    public int retryFailedUploads() {
        log.info("Starting failed upload retry process");

        // Calculate backoff time based on attempt count
        // Attempt 1: 1 minute ago
        // Attempt 2: 5 minutes ago
        // Attempt 3: 15 minutes ago
        // Attempt 4: 1 hour ago
        // Attempt 5: 4 hours ago
        LocalDateTime now = LocalDateTime.now();

        List<InvoiceEconomicsUpload> retryableUploads = em.createQuery("""
            SELECT u FROM InvoiceEconomicsUpload u
            WHERE u.status = :failed
              AND u.attemptCount < u.maxAttempts
              AND (
                  u.lastAttemptAt IS NULL
                  OR (u.attemptCount = 0 AND u.lastAttemptAt < :backoff0)
                  OR (u.attemptCount = 1 AND u.lastAttemptAt < :backoff1)
                  OR (u.attemptCount = 2 AND u.lastAttemptAt < :backoff2)
                  OR (u.attemptCount = 3 AND u.lastAttemptAt < :backoff3)
                  OR (u.attemptCount >= 4 AND u.lastAttemptAt < :backoff4)
              )
            ORDER BY u.lastAttemptAt ASC NULLS FIRST
            """, InvoiceEconomicsUpload.class)
                .setParameter("failed", UploadStatus.FAILED)
                .setParameter("backoff0", now.minus(Duration.ofMinutes(1)))
                .setParameter("backoff1", now.minus(Duration.ofMinutes(5)))
                .setParameter("backoff2", now.minus(Duration.ofMinutes(15)))
                .setParameter("backoff3", now.minus(Duration.ofHours(1)))
                .setParameter("backoff4", now.minus(Duration.ofHours(4)))
                .setMaxResults(50) // Process in batches
                .getResultList();

        log.infof("Found %d failed uploads eligible for retry", retryableUploads.size());

        int processed = 0;
        for (InvoiceEconomicsUpload upload : retryableUploads) {
            try {
                // Reset status to pending for retry
                upload.setStatus(UploadStatus.PENDING);
                upload.persist();

                // Process the upload
                processUploads(upload.getInvoiceuuid());
                processed++;
            } catch (Exception e) {
                log.errorf(e, "Failed to retry upload %s", upload.getUuid());
            }
        }

        log.infof("Retry process completed: processed %d uploads", processed);
        return processed;
    }

    /**
     * Gets all uploads for an invoice.
     *
     * @param invoiceuuid Invoice UUID
     * @return List of uploads
     */
    public List<InvoiceEconomicsUpload> getUploadsForInvoice(String invoiceuuid) {
        return InvoiceEconomicsUpload.list("invoiceuuid", invoiceuuid);
    }

    /**
     * Gets upload statistics for monitoring.
     */
    public record UploadStats(long pending, long success, long failed, long retryable) {}

    public UploadStats getUploadStats() {
        long pending = InvoiceEconomicsUpload.count("status", UploadStatus.PENDING);
        long success = InvoiceEconomicsUpload.count("status", UploadStatus.SUCCESS);
        long failed = InvoiceEconomicsUpload.count("status", UploadStatus.FAILED);
        long retryable = InvoiceEconomicsUpload.count("status = ?1 AND attemptCount < maxAttempts",
                UploadStatus.FAILED);

        return new UploadStats(pending, success, failed, retryable);
    }
}
