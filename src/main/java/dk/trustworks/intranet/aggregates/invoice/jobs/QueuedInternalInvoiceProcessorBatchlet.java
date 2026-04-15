package dk.trustworks.intranet.aggregates.invoice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceOrchestrator;
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
 * Processes queued INTERNAL invoices, creating them automatically (no review step) when
 * their referenced external invoice has been PAID in e-conomics.
 *
 * <p>This batch job runs nightly and:
 * <ol>
 *   <li>Finds all INTERNAL invoices with status QUEUED</li>
 *   <li>Checks if their referenced invoice has economics_status = PAID</li>
 *   <li>For each eligible invoice: delegates to
 *       {@link InternalInvoiceOrchestrator#finalizeAutomatically(String)} which creates the
 *       e-conomics draft and immediately books it in a single transaction (SPEC-INV-001 §9.1).</li>
 * </ol>
 *
 * <p>Individual failures are logged as warnings and do not stop processing of remaining invoices.
 *
 * SPEC-INV-001 §9.1, §9.2.
 */
@JBossLog
@Named("queuedInternalInvoiceProcessorBatchlet")
@Dependent
@BatchExceptionTracking
public class QueuedInternalInvoiceProcessorBatchlet extends AbstractBatchlet {

    @Inject
    InternalInvoiceOrchestrator internalOrchestrator;

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
                        "uuid = ?1",
                        queuedInvoice.getInvoiceRefUuid()
                ).firstResult();

                if (referencedInvoice == null) {
                    log.warnf("Queued invoice %s references non-existent invoice %s - skipping",
                            queuedInvoice.getUuid(), queuedInvoice.getInvoiceRefUuid());
                    skipped++;
                    continue;
                }

                // Only proceed when the referenced invoice is confirmed PAID
                if (referencedInvoice.getEconomicsStatus() != EconomicsInvoiceStatus.PAID) {
                    log.debugf("Queued invoice %s waiting for invoice %s to be PAID (current: %s)",
                            queuedInvoice.getUuid(),
                            referencedInvoice.getUuid(),
                            referencedInvoice.getEconomicsStatus());
                    skipped++;
                    continue;
                }

                // Set dates before auto-finalization: invoicedate = today, duedate = tomorrow
                queuedInvoice.setInvoicedate(LocalDate.now());
                queuedInvoice.setDuedate(LocalDate.now().plusDays(1));

                log.infof("Auto-finalizing queued invoice %s (references paid invoice %s)",
                        queuedInvoice.getUuid(), referencedInvoice.getUuid());

                // Auto-finalize: create draft + book immediately, no review step (SPEC-INV-001 §9.1)
                internalOrchestrator.finalizeAutomatically(queuedInvoice.getUuid());

                log.infof("Successfully auto-finalized queued invoice %s", queuedInvoice.getUuid());
                processed++;

            } catch (Exception e) {
                log.warnf(e, "Auto-finalize failed for queued internal invoice %s", queuedInvoice.getUuid());
                failed++;
                // Continue processing remaining invoices
            }
        }

        log.infof("QueuedInternalInvoiceProcessorBatchlet completed: total=%d, processed=%d, skipped=%d, failed=%d",
                queuedInvoices.size(), processed, skipped, failed);

        return "COMPLETED";
    }
}
