package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * One-time batch job to migrate invoice PDFs from database BLOBs to AWS S3.
 *
 * <p>This job processes invoices from the legacy {@code invoices} table that have PDF data
 * stored as BLOBs and migrates them to S3 storage, updating the new {@code invoices_v2} table
 * with S3 URLs and SHA-256 checksums.
 *
 * <h2>Migration Process</h2>
 * <ol>
 *   <li>Query invoices with non-null PDF BLOBs that haven't been migrated yet</li>
 *   <li>Process in chunks of 25 invoices per transaction (configurable via BATCH_SIZE)</li>
 *   <li>For each invoice:
 *     <ul>
 *       <li>Upload PDF bytes to S3 bucket with key: {@code migrated/{uuid}.pdf}</li>
 *       <li>Compute SHA-256 hash for integrity verification</li>
 *       <li>Update {@code invoices_v2} table with {@code pdf_url} and {@code pdf_sha256}</li>
 *     </ul>
 *   </li>
 *   <li>Original BLOB data remains intact in {@code invoices} table for safety</li>
 * </ol>
 *
 * <h2>Execution Strategy</h2>
 * <ul>
 *   <li><b>Manual trigger only:</b> No scheduled execution - must be triggered via REST API</li>
 *   <li><b>Fail-fast:</b> Job stops on first error and can be re-run after fixing issues</li>
 *   <li><b>Idempotent:</b> Safe to re-run - skips already-migrated invoices</li>
 *   <li><b>Guard logic:</b> Prevents accidental re-runs if migration already completed</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code bucket.invoices} - S3 bucket name (default: invoicepdffiles)</li>
 *   <li>AWS credentials from environment (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * Trigger via REST API:
 * <pre>
 * POST /admin/migration/invoice-pdfs/start
 * Authorization: Bearer {admin-jwt-token}
 * </pre>
 *
 * Monitor progress:
 * <pre>
 * GET /admin/migration/invoice-pdfs/status/{executionId}
 * </pre>
 *
 * @see dk.trustworks.intranet.aggregates.invoice.resources.InvoiceMigrationResource
 * @see dk.trustworks.intranet.aggregates.invoice.model.Invoice Legacy invoice table
 * @see dk.trustworks.intranet.aggregates.invoice.model.Invoice New invoice table
 */
@Named("invoicePdfMigrationBatchlet")
@Dependent
@BatchExceptionTracking
@JBossLog
public class InvoicePdfMigrationBatchlet extends AbstractBatchlet {

    /**
     * Number of invoices to process per transaction.
     * Smaller batches = safer, more granular progress, more frequent commits.
     */
    private static final int BATCH_SIZE = 25;

    /**
     * S3 key prefix for migrated PDFs. Allows identifying migrated files.
     */
    private static final String S3_KEY_PREFIX = "migrated/";

    @Inject
    EntityManager entityManager;

    Region regionNew = Region.EU_WEST_1;

    ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
    ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder().proxyConfiguration(proxyConfig.build());
    S3Client s3Client = S3Client.builder().region(regionNew).httpClientBuilder(httpClientBuilder).build();

    @ConfigProperty(name = "bucket.invoices")
    String invoiceBucket;

    /**
     * Main batch processing method. Orchestrates the entire migration process.
     *
     * @return "COMPLETED" if all invoices migrated successfully,
     *         "SKIPPED" if migration already run,
     *         throws exception on any error (fail-fast strategy)
     * @throws Exception if any invoice fails to migrate
     */
    @Override
    public String process() throws Exception {
        log.info("========================================");
        log.info("Starting Invoice PDF Migration Job");
        log.info("Source: invoices.pdf (BLOB column)");
        log.info("Target: S3 bucket '" + invoiceBucket + "'");
        log.info("Batch size: " + BATCH_SIZE + " invoices per transaction");
        log.info("========================================");

        // Guard: Check if migration already completed
        if (isMigrationAlreadyCompleted()) {
            log.warn("Migration already completed - aborting to prevent duplicate work");
            return "SKIPPED";
        }

        // Count total invoices to migrate
        long totalCount = countInvoicesToMigrate();
        log.infof("Found %d invoices with PDFs to migrate", totalCount);

        if (totalCount == 0) {
            log.info("No invoices to migrate - job complete");
            return "COMPLETED";
        }

        // Process in batches
        // NOTE: We always use offset=0 because the WHERE clause filters out already-migrated invoices
        // This prevents the "shrinking result set" bug where offset skips un-migrated invoices
        int totalProcessed = 0;

        while (true) {
            int batchProcessed = processOneBatch(BATCH_SIZE);

            if (batchProcessed == 0) {
                // No more invoices to process
                break;
            }

            totalProcessed += batchProcessed;

            log.infof("Progress: %d/%d invoices migrated (%.1f%%)",
                     totalProcessed, totalCount,
                     (totalProcessed * 100.0 / totalCount));
        }

        log.info("========================================");
        log.infof("Migration completed successfully: %d invoices migrated", totalProcessed);
        log.info("Original BLOB data preserved in invoices table");
        log.info("========================================");

        return "COMPLETED";
    }

    /**
     * Check if migration has already been run by verifying there are no more invoices to migrate.
     *
     * @return true if ALL invoices with PDFs have been migrated (no remaining work)
     */
    @Transactional
    protected boolean isMigrationAlreadyCompleted() {
        // Count how many invoices still need migration
        long remainingCount = countInvoicesToMigrate();

        // Count how many have already been migrated
        long migratedCount = Invoice.count(
            "pdfUrl LIKE ?1",
            "s3://" + invoiceBucket + "/" + S3_KEY_PREFIX + "%"
        );

        log.infof("Migration status check: %d already migrated, %d remaining to migrate",
                 migratedCount, remainingCount);

        if (remainingCount == 0 && migratedCount > 0) {
            log.warnf("Migration already completed - all %d invoices have been migrated", migratedCount);
            return true;
        }

        return false;
    }

    /**
     * Count invoices that need migration: have PDF BLOB but no S3 URL in v2 table.
     *
     * @return count of invoices to migrate
     */
    @Transactional
    protected long countInvoicesToMigrate() {
        Query query = entityManager.createNativeQuery(
            "SELECT COUNT(*) " +
            "FROM invoices i " +
            "WHERE i.pdf IS NOT NULL " +
            "AND NOT EXISTS (" +
            "    SELECT 1 FROM invoices_v2 v2 " +
            "    WHERE v2.uuid = i.uuid " +
            "    AND v2.pdf_url IS NOT NULL" +
            ")"
        );

        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Process one batch of invoices in a single transaction.
     *
     * <p>This method is transactional to ensure atomicity: either all invoices
     * in the batch are migrated successfully, or none are (transaction rollback).
     *
     * <p>Uses fail-fast strategy: any error throws an exception that propagates
     * up and fails the entire batch job. Job can be re-run after fixing issues.
     *
     * <p><b>IMPORTANT:</b> Always queries with OFFSET 0. The WHERE clause automatically
     * excludes already-migrated invoices, so we always get the next un-migrated batch.
     * This prevents the "shrinking result set" bug where incrementing offset skips rows.
     *
     * @param limit number of invoices to process
     * @return number of invoices actually processed in this batch
     * @throws Exception if any invoice fails to migrate (fail-fast)
     */
    @Transactional
    protected int processOneBatch(int limit) throws Exception {
        log.debugf("Processing next batch of up to %d invoices (always from start of un-migrated set)", limit);

        // Query invoices with PDFs that haven't been migrated yet
        // IMPORTANT: No OFFSET - always query from start of filtered result set
        Query query = entityManager.createNativeQuery(
            "SELECT i.uuid, i.pdf " +
            "FROM invoices i " +
            "WHERE i.pdf IS NOT NULL " +
            "AND NOT EXISTS (" +
            "    SELECT 1 FROM invoices_v2 v2 " +
            "    WHERE v2.uuid = i.uuid " +
            "    AND v2.pdf_url IS NOT NULL" +
            ") " +
            "LIMIT :limit"
        )
        .setParameter("limit", limit);

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        if (results.isEmpty()) {
            log.debug("No invoices in this batch - end of data");
            return 0;
        }

        log.infof("Processing batch of %d invoices...", results.size());

        int count = 0;
        for (Object[] row : results) {
            String uuid = (String) row[0];
            byte[] pdfBlob = (byte[]) row[1];

            // Validate PDF data
            if (pdfBlob == null || pdfBlob.length == 0) {
                log.warnf("Skipping invoice %s - PDF BLOB is null or empty", uuid);
                continue;
            }

            try {
                migratePdfToS3(uuid, pdfBlob);
                count++;
                log.debugf("Migrated invoice %s (%d bytes)", uuid, pdfBlob.length);
            } catch (Exception e) {
                // FAIL-FAST: Re-throw exception to fail the job
                log.errorf(e, "FATAL: Failed to migrate PDF for invoice %s", uuid);
                log.error("Job will be aborted due to fail-fast strategy");
                log.errorf("Successfully migrated %d invoices before failure", count);
                throw new RuntimeException(
                    "PDF migration failed for invoice " + uuid + ": " + e.getMessage(),
                    e
                );
            }
        }

        log.infof("Batch complete: %d invoices migrated", count);
        return count;
    }

    /**
     * Migrate a single invoice PDF to S3 and update invoices_v2 table.
     *
     * <p>Steps:
     * <ol>
     *   <li>Upload PDF bytes to S3 with key: {@code migrated/{uuid}.pdf}</li>
     *   <li>Compute SHA-256 hash of PDF content for integrity verification</li>
     *   <li>Update {@code invoices_v2} table with S3 URL and hash</li>
     * </ol>
     *
     * @param invoiceUuid the invoice UUID
     * @param pdfBlob the PDF content as byte array
     * @throws S3Exception if S3 upload fails
     * @throws RuntimeException if SHA-256 computation fails or database update fails
     */
    private void migratePdfToS3(String invoiceUuid, byte[] pdfBlob) {
        log.debugf("Starting migration for invoice %s (%d bytes)", invoiceUuid, pdfBlob.length);

        // Step 1: Upload PDF to S3
        String s3Key = S3_KEY_PREFIX + invoiceUuid + ".pdf";

        try {
            s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(invoiceBucket)
                    .key(s3Key)
                    .contentType("application/pdf")
                    .build(),
                RequestBody.fromBytes(pdfBlob)
            );

            log.debugf("Uploaded PDF to S3: s3://%s/%s", invoiceBucket, s3Key);

        } catch (S3Exception e) {
            log.errorf(e, "S3 upload failed for invoice %s: %s",
                      invoiceUuid, e.awsErrorDetails().errorMessage());
            throw new RuntimeException(
                "S3 upload failed for invoice " + invoiceUuid + ": " +
                e.awsErrorDetails().errorMessage(),
                e
            );
        }

        // Step 2: Compute SHA-256 hash
        String sha256Hash = computeSha256(pdfBlob);
        log.debugf("Computed SHA-256 hash: %s", sha256Hash);

        // Step 3: Update invoices_v2 table
        String pdfUrl = "s3://" + invoiceBucket + "/" + s3Key;

        int updatedRows = entityManager.createNativeQuery(
            "UPDATE invoices_v2 " +
            "SET pdf_url = :url, pdf_sha256 = :hash " +
            "WHERE uuid = :uuid"
        )
        .setParameter("url", pdfUrl)
        .setParameter("hash", sha256Hash)
        .setParameter("uuid", invoiceUuid)
        .executeUpdate();

        if (updatedRows == 0) {
            log.warnf("No rows updated in invoices_v2 for invoice %s - invoice may not exist in v2 table",
                     invoiceUuid);
            throw new RuntimeException(
                "Failed to update invoices_v2 for invoice " + invoiceUuid +
                " - invoice not found in v2 table"
            );
        }

        log.debugf("Updated invoices_v2: uuid=%s, pdf_url=%s", invoiceUuid, pdfUrl);
    }

    /**
     * Compute SHA-256 hash of PDF bytes for integrity verification.
     *
     * @param pdfBytes the PDF content
     * @return hexadecimal string representation of SHA-256 hash (64 characters)
     * @throws RuntimeException if SHA-256 algorithm is not available
     */
    private String computeSha256(byte[] pdfBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(pdfBytes);
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.errorf(e, "SHA-256 algorithm not available");
            throw new RuntimeException("SHA-256 hashing failed - algorithm not available", e);
        }
    }

    /**
     * Convert byte array to hexadecimal string representation.
     *
     * @param bytes the byte array to convert
     * @return lowercase hexadecimal string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
