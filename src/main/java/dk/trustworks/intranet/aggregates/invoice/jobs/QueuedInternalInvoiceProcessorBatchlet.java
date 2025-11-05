package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.ProcessingState;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceEconomicsUploadService;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.batch.monitoring.BatchExceptionTracking;
import jakarta.batch.api.AbstractBatchlet;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;

/**
 * Processes queued INTERNAL invoices, creating them automatically when their
 * referenced external invoice has been PAID in e-conomics.
 *
 * <p>This batch job runs nightly and:
 * <ol>
 *   <li>Finds all INTERNAL invoices with status QUEUED</li>
 *   <li>Checks if their referenced invoice (via invoice_ref) has economics_status = PAID</li>
 *   <li>For each eligible invoice:
 *     <ul>
 *       <li>Sets invoicedate to today and duedate to tomorrow</li>
 *       <li>Creates the invoice (assigns number, generates PDF)</li>
 *       <li>Queues uploads to e-conomics for tracking and retry</li>
 *       <li>Attempts uploads to both issuing company and debtor company</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Upload failures are tracked in invoice_economics_uploads table and automatically
 * retried by EconomicsUploadRetryBatchlet with exponential backoff.
 *
 * @see InvoiceService#queueInternalInvoice(String)
 * @see InvoiceService#createQueuedInvoiceWithoutUpload(Invoice)
 * @see InvoiceEconomicsUploadService
 */
@JBossLog
@Named("queuedInternalInvoiceProcessorBatchlet")
@Dependent
@BatchExceptionTracking
public class QueuedInternalInvoiceProcessorBatchlet extends AbstractBatchlet {

    @Inject
    InvoiceService invoiceService;

    @Inject
    InvoiceEconomicsUploadService uploadService;

    @Override
    @Transactional
    public String process() throws Exception {
        log.info("QueuedInternalInvoiceProcessorBatchlet started");

        // Find all QUEUED INTERNAL invoices that reference another invoice
        List<Invoice> queuedInvoices = Invoice.list(
                "processingState = ?1 AND type = ?2 AND sourceInvoiceUuid IS NOT NULL",
                ProcessingState.QUEUED, InvoiceType.INTERNAL
        );

        log.infof("Found %d queued internal invoices to process", queuedInvoices.size());

        int processed = 0;
        int skipped = 0;
        int failed = 0;

        for (Invoice queuedInvoice : queuedInvoices) {
            try {
                // Find the referenced external invoice
                Invoice referencedInvoice = Invoice.find(
                        "uuid = ?1",
                        queuedInvoice.getSourceInvoiceUuid()
                ).firstResult();

                if (referencedInvoice == null) {
                    log.warnf("Queued invoice %s references non-existent invoice uuid %s - skipping",
                            queuedInvoice.getUuid(), queuedInvoice.getSourceInvoiceUuid());
                    skipped++;
                    continue;
                }

                // Check if referenced invoice is PAID in e-conomics
                if (referencedInvoice.getFinanceStatus() != FinanceStatus.PAID) {
                    log.debugf("Queued invoice %s waiting for invoice %d to be PAID (current finance status: %s)",
                            queuedInvoice.getUuid(),
                            referencedInvoice.getInvoicenumber(),
                            referencedInvoice.getFinanceStatus());
                    skipped++;
                    continue;
                }

                // Referenced invoice is PAID - process this queued invoice
                log.infof("Processing queued invoice %s (references paid invoice %d)",
                        queuedInvoice.getUuid(), referencedInvoice.getInvoicenumber());

                // Set dates: invoicedate = today, duedate = tomorrow
                queuedInvoice.setInvoicedate(LocalDate.now());
                queuedInvoice.setDuedate(LocalDate.now().plusDays(1));

                // Create the invoice (assigns number, generates PDF - NO upload yet)
                Invoice createdInvoice = invoiceService.createQueuedInvoiceWithoutUpload(queuedInvoice);

                log.infof("Created queued invoice %s with number %d - now queuing uploads",
                        createdInvoice.getUuid(), createdInvoice.getInvoicenumber());

                // Queue uploads for both issuing and debtor companies
                uploadService.queueUploads(createdInvoice);

                // Process uploads immediately (failures will be retried by separate job)
                InvoiceEconomicsUploadService.UploadResult result = uploadService.processUploads(createdInvoice.getUuid());

                log.infof("Invoice %d upload result: %d succeeded, %d failed (total: %d)",
                        createdInvoice.getInvoicenumber(),
                        result.successCount(), result.failedCount(), result.totalCount());

                if (result.allSucceeded()) {
                    log.infof("Successfully created and uploaded invoice %d to all companies",
                            createdInvoice.getInvoicenumber());
                } else if (result.partialSuccess()) {
                    log.warnf("Invoice %d partially uploaded (%d of %d succeeded) - failures will be retried",
                            createdInvoice.getInvoicenumber(), result.successCount(), result.totalCount());
                } else if (result.allFailed()) {
                    log.errorf("Invoice %d creation succeeded but all uploads failed - will retry automatically",
                            createdInvoice.getInvoicenumber());
                }

                processed++;

            } catch (Exception e) {
                log.errorf(e, "Failed to process queued invoice %s", queuedInvoice.getUuid());
                failed++;
                // Continue processing other invoices
            }
        }

        String summary = String.format(
                "QueuedInternalInvoiceProcessorBatchlet completed: total=%d, processed=%d, skipped=%d, failed=%d",
                queuedInvoices.size(), processed, skipped, failed
        );
        log.info(summary);

        return "COMPLETED";
    }
}
