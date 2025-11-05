package dk.trustworks.intranet.aggregates.invoice.services.v2;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.network.dto.InvoiceDtoV2;
import dk.trustworks.intranet.aggregates.invoice.repositories.InvoiceRepository;
import dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceMapperService;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Service for asynchronous PDF generation.
 *
 * This service runs a scheduled job that:
 * 1. Finds invoices in CREATED state without PDF artifacts
 * 2. Generates PDF documents for each invoice via external InvoiceAPI
 * 3. Uploads PDFs to AWS S3 in the dedicated invoice bucket
 * 4. Updates invoice records with PDF URL and SHA-256 hash for integrity verification
 *
 * PDF generation is decoupled from invoice finalization to avoid blocking
 * the finalization operation. PDFs are stored in a separate S3 bucket
 * (invoicepdffiles) for security and compliance.
 *
 * Configuration:
 * - bucket.invoices: S3 bucket name for invoice PDFs (from application.yml)
 * - invoice.pdf.generation-job.cron: Cron schedule for batch job
 * - AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY: AWS credentials (env vars)
 * - AWS region: eu-west-1 (configured in application.yml)
 */
@ApplicationScoped
@JBossLog
public class PdfGenerationService {

    @Inject
    InvoiceRepository repository;

    @Inject
    InvoiceMapperService mapper;

    @Inject
    S3Client s3;

    @ConfigProperty(name = "bucket.invoices")
    String invoiceBucket;

    // TODO: Inject InvoiceAPI REST client (external PDF generator service)
    // This should be the REST client interface that calls the invoice-generator API
    // @RestClient InvoiceAPI invoiceAPI;

    /**
     * Scheduled job to generate PDFs for invoices that need them.
     * Default: Runs every 5 minutes.
     *
     * Configuration property: invoice.pdf.generation-job.cron
     */
    //@Scheduled(cron = "0 */5 * * * ?")
    @Transactional
    public void generatePendingPdfs() {
        List<Invoice> pending = repository.findPendingPdfGeneration();

        if (pending.isEmpty()) {
            Log.debug("No invoices pending PDF generation");
            return;
        }

        Log.infof("Found %d invoices pending PDF generation", pending.size());

        int success = 0, failed = 0;

        for (Invoice invoice : pending) {
            try {
                generatePdf(invoice);
                success++;
            } catch (Exception e) {
                Log.errorf(e, "Failed to generate PDF for invoice %s", invoice.getUuid());
                failed++;
            }
        }

        Log.infof("PDF generation batch complete: %d succeeded, %d failed", success, failed);
    }

    /**
     * Generate PDF for a single invoice.
     *
     * Process:
     * 1. Convert Invoice entity to InvoiceDtoV2 DTO
     * 2. Call external invoice-generator API with invoice data
     * 3. Receive PDF byte array from generator
     * 4. Compute SHA-256 hash for integrity verification
     * 5. Upload PDF to AWS S3 (invoicepdffiles bucket)
     * 6. Update invoice entity with pdf_url and pdf_sha256
     *
     * Errors during PDF generation are caught and logged without re-throwing.
     * This allows the scheduled job to continue processing other invoices
     * even if one fails.
     *
     * @param invoice The invoice to generate PDF for
     * @throws Exception if PDF generation or S3 upload fails
     */
    @Transactional
    public void generatePdf(Invoice invoice) {
        String invoiceUuid = invoice.getUuid();

        try {
            log.infof("Starting PDF generation for invoice %s", invoiceUuid);

            // Step 1: Convert entity to DTO
            InvoiceDtoV2 dto = mapper.toV2Dto(invoice);

            // Step 2: Call external PDF generator
            // NOTE: InvoiceAPI is NOT YET INJECTED - uncomment when REST client is configured
            // byte[] pdfBytes = invoiceAPI.createInvoicePDF(dtoAsJson);

            // TEMPORARY PLACEHOLDER: In production, replace with actual API call above
            byte[] pdfBytes = generateMockPdf(invoice);
            log.debugf("Generated PDF bytes: %d bytes for invoice %s", pdfBytes.length, invoiceUuid);

            // Step 3: Compute SHA-256 hash for data integrity
            String sha256Hash = computeSha256(pdfBytes);
            log.debugf("Computed SHA-256 hash: %s for invoice %s", sha256Hash, invoiceUuid);

            // Step 4: Upload to S3 bucket
            String s3Key = "invoices/" + invoiceUuid + ".pdf";
            uploadToS3(s3Key, pdfBytes);

            // Step 5: Construct S3 URL
            String pdfUrl = "s3://" + invoiceBucket + "/" + s3Key;

            // Step 6: Update invoice with PDF metadata
            invoice.setPdfUrl(pdfUrl);
            invoice.setPdfSha256(sha256Hash);
            repository.persist(invoice);

            log.infof("PDF generation completed for invoice %s (URL: %s, Hash: %s)",
                     invoiceUuid, pdfUrl, sha256Hash);

        } catch (S3Exception s3Error) {
            log.errorf(s3Error, "S3 upload failed for invoice %s: %s",
                      invoiceUuid, s3Error.awsErrorDetails().errorMessage());
            // Don't re-throw - let scheduler continue with next invoice

        } catch (Exception e) {
            log.errorf(e, "PDF generation failed for invoice %s", invoiceUuid);
            // Don't re-throw - let scheduler continue with next invoice
        }
    }

    /**
     * Upload PDF bytes to AWS S3.
     * Uses the dedicated invoicepdffiles bucket for invoice documents.
     *
     * @param s3Key The S3 object key (path) for the PDF
     * @param pdfBytes The PDF file content as bytes
     * @throws S3Exception if upload fails
     */
    private void uploadToS3(String s3Key, byte[] pdfBytes) {
        try {
            s3.putObject(
                PutObjectRequest.builder()
                    .bucket(invoiceBucket)
                    .key(s3Key)
                    .build(),
                RequestBody.fromBytes(pdfBytes)
            );
            log.debugf("Uploaded PDF to S3: s3://%s/%s (%d bytes)",
                      invoiceBucket, s3Key, pdfBytes.length);

        } catch (S3Exception e) {
            log.errorf(e, "Failed to upload PDF to S3 bucket %s with key %s",
                      invoiceBucket, s3Key);
            throw e;
        }
    }

    /**
     * Compute SHA-256 hash of PDF bytes for data integrity verification.
     * Allows detecting if PDF has been tampered with after storage.
     *
     * @param pdfBytes The PDF file content
     * @return Hex-encoded SHA-256 hash string
     * @throws RuntimeException if SHA-256 algorithm is not available
     */
    private String computeSha256(byte[] pdfBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(pdfBytes);
            return bytesToHex(hashBytes);

        } catch (NoSuchAlgorithmException e) {
            log.errorf(e, "SHA-256 algorithm not available");
            throw new WebApplicationException("SHA-256 hashing failed",
                jakarta.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Convert byte array to hexadecimal string representation.
     * Used for SHA-256 hash output and other binary data.
     *
     * @param bytes The byte array to convert
     * @return Lowercase hexadecimal string representation
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Generate mock PDF for testing/placeholder purposes.
     * In production, this should be replaced with actual InvoiceAPI call.
     *
     * @param invoice The invoice to generate mock PDF for
     * @return Mock PDF bytes
     */
    private byte[] generateMockPdf(Invoice invoice) {
        // Simple mock PDF (valid PDF header for testing)
        String mockPdfContent = "%PDF-1.4\n" +
            "1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n" +
            "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n" +
            "3 0 obj<</Type/Page/Parent 2 0 R/Resources<<>>>>endobj\n" +
            "xref\n0 4\n" +
            "0000000000 65535 f\n" +
            "0000000009 00000 n\n" +
            "0000000058 00000 n\n" +
            "0000000115 00000 n\n" +
            "trailer<</Size 4/Root 1 0 R>>\n" +
            "startxref\n179\n%%EOF";

        return mockPdfContent.getBytes();
    }

    /**
     * Force regeneration of PDF for a specific invoice.
     * Useful for manual retries or when invoice content changes.
     *
     * @param invoiceUuid The UUID of the invoice
     */
    @Transactional
    public void regeneratePdf(String invoiceUuid) {
        Invoice invoice = repository.findById(invoiceUuid);
        if (invoice == null) {
            Log.warnf("Cannot regenerate PDF: invoice %s not found", invoiceUuid);
            return;
        }

        // Clear existing PDF artifacts to trigger regeneration
        invoice.setPdfUrl(null);
        invoice.setPdfSha256(null);
        repository.persist(invoice);

        Log.infof("Cleared PDF artifacts for invoice %s, will regenerate on next job run", invoiceUuid);
    }
}
