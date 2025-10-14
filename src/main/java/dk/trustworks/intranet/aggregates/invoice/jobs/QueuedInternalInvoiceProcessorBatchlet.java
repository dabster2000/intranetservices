package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
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
 *       <li>Uploads to both issuing company and debtor company e-conomics</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * @see InvoiceService#queueInternalInvoice(String)
 * @see InvoiceService#createQueuedInvoice(Invoice)
 */
@JBossLog
@Named("queuedInternalInvoiceProcessorBatchlet")
@Dependent
@BatchExceptionTracking
public class QueuedInternalInvoiceProcessorBatchlet extends AbstractBatchlet {

    @Inject
    InvoiceService invoiceService;

    @Override
    @Transactional
    public String process() throws Exception {
        log.info("QueuedInternalInvoiceProcessorBatchlet started");

        // Find all QUEUED INTERNAL invoices that reference another invoice
        List<Invoice> queuedInvoices = Invoice.list(
                "status = ?1 AND type = ?2 AND invoiceref > 0",
                InvoiceStatus.QUEUED, InvoiceType.INTERNAL
        );

        log.infof("Found %d queued internal invoices to process", queuedInvoices.size());

        int processed = 0;
        int skipped = 0;
        int failed = 0;

        for (Invoice queuedInvoice : queuedInvoices) {
            try {
                // Find the referenced external invoice
                Invoice referencedInvoice = Invoice.find(
                        "invoicenumber = ?1",
                        queuedInvoice.getInvoiceref()
                ).firstResult();

                if (referencedInvoice == null) {
                    log.warnf("Queued invoice %s references non-existent invoice number %d - skipping",
                            queuedInvoice.getUuid(), queuedInvoice.getInvoiceref());
                    skipped++;
                    continue;
                }

                // Check if referenced invoice is PAID in e-conomics
                if (referencedInvoice.getEconomicsStatus() != EconomicsInvoiceStatus.PAID) {
                    log.debugf("Queued invoice %s waiting for invoice %d to be PAID (current status: %s)",
                            queuedInvoice.getUuid(),
                            referencedInvoice.getInvoicenumber(),
                            referencedInvoice.getEconomicsStatus());
                    skipped++;
                    continue;
                }

                // Referenced invoice is PAID - process this queued invoice
                log.infof("Processing queued invoice %s (references paid invoice %d)",
                        queuedInvoice.getUuid(), referencedInvoice.getInvoicenumber());

                // Set dates: invoicedate = today, duedate = tomorrow
                queuedInvoice.setInvoicedate(LocalDate.now());
                queuedInvoice.setDuedate(LocalDate.now().plusDays(1));

                // Create the invoice (this handles PDF generation and e-conomics upload)
                Invoice createdInvoice = invoiceService.createQueuedInvoice(queuedInvoice);

                log.infof("Successfully created queued invoice %s with number %d",
                        createdInvoice.getUuid(), createdInvoice.getInvoicenumber());
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
