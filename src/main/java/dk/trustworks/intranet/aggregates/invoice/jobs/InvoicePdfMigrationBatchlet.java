package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.InvoicePdfS3Service;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

/**
 * One-time batch job to migrate invoice PDFs from the database LONGBLOB column to S3.
 *
 * <p>Processes invoices in chunks: reads pdf bytes from DB, uploads to S3,
 * sets pdf_storage_key, and nulls out the pdf column.
 *
 * <p>Idempotent: skips invoices that already have a pdf_storage_key set.
 *
 * <p>To run: POST /invoices/admin/migrate-pdfs-to-s3
 */
@JBossLog
@Dependent
@Named("invoicePdfMigrationBatchlet")
public class InvoicePdfMigrationBatchlet extends AbstractBatchlet {

    @Inject
    InvoicePdfS3Service invoicePdfS3Service;

    private static final int CHUNK_SIZE = 50;

    @Override
    @ActivateRequestContext
    public String process() {
        log.info("=== Starting Invoice PDF Migration to S3 ===");

        int totalMigrated = 0;
        int totalErrors = 0;

        while (true) {
            // Read each batch in its own short transaction
            List<Invoice> batch = QuarkusTransaction.requiringNew().call(() ->
                    Invoice.find("pdf IS NOT NULL AND pdfStorageKey IS NULL")
                            .page(0, CHUNK_SIZE).list()
            );

            if (batch.isEmpty()) {
                break;
            }

            log.infof("Processing batch of %d invoices...", batch.size());

            for (Invoice invoice : batch) {
                try {
                    // Each invoice migrated in its own transaction
                    QuarkusTransaction.requiringNew().run(() -> {
                        byte[] pdfBytes = invoice.getPdf();
                        if (pdfBytes == null || pdfBytes.length == 0) {
                            log.warnf("Invoice %s has null/empty pdf bytes, skipping", invoice.getUuid());
                            return;
                        }

                        String storageKey = invoicePdfS3Service.savePdf(invoice.getUuid(), pdfBytes);
                        Invoice.update("pdfStorageKey = ?1, pdf = NULL WHERE uuid = ?2", storageKey, invoice.getUuid());

                        log.debugf("Migrated invoice %s PDF (%d bytes) to S3 key: %s",
                                invoice.getUuid(), pdfBytes.length, storageKey);
                    });
                    totalMigrated++;
                    if (totalMigrated % 100 == 0) {
                        log.infof("Progress: %d invoices migrated so far", totalMigrated);
                    }
                } catch (Exception e) {
                    totalErrors++;
                    log.errorf("Failed to migrate PDF for invoice %s: %s", invoice.getUuid(), e.getMessage());
                }
            }
        }

        String result = String.format("PDF Migration complete: %d migrated, %d errors", totalMigrated, totalErrors);
        log.info("=== " + result + " ===");
        return result;
    }
}
