package dk.trustworks.intranet.aggregates.invoice.services.v2;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.ProcessingState;
import dk.trustworks.intranet.aggregates.invoice.repositories.InvoiceRepository;
import dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceNumberingService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;

/**
 * Service for finalizing draft invoices.
 *
 * Finalization process:
 * 1. Validate invoice is in DRAFT state (lifecycle guard)
 * 2. Assign invoice number (except for PHANTOM type)
 * 3. Set invoice date and due date if not already set
 * 4. Compute invoice year/month from invoice date
 * 5. Transition lifecycle status to CREATED
 * 6. Set processing state to IDLE
 * 7. PDF generation is deferred to async job
 */
@ApplicationScoped
public class FinalizationService {

    @Inject
    InvoiceRepository repository;

    @Inject
    InvoiceNumberingService numberingService;

    @Inject
    InvoiceStateMachine stateMachine;

    /**
     * Finalize a draft invoice, preparing it for submission.
     *
     * This operation:
     * - Validates the invoice is in DRAFT state
     * - Assigns an invoice number (except for PHANTOM invoices)
     * - Sets invoice date and due date if not already set
     * - Transitions the invoice to CREATED state
     * - Sets processing state to IDLE (no queue)
     *
     * PDF generation happens asynchronously after finalization.
     *
     * @param invoiceUuid The UUID of the invoice to finalize
     * @return The finalized invoice entity
     * @throws WebApplicationException if invoice not found or not in DRAFT state
     */
    @Transactional
    public Invoice finalize(String invoiceUuid) {
        Invoice invoice = repository.findById(invoiceUuid);
        if (invoice == null) {
            throw new WebApplicationException(
                "Invoice not found: " + invoiceUuid,
                Response.Status.NOT_FOUND
            );
        }

        // Guard: Only DRAFT invoices can be finalized
        if (invoice.getLifecycleStatus() != LifecycleStatus.DRAFT) {
            throw new WebApplicationException(
                String.format(
                    "Cannot finalize invoice %s: status is %s (expected DRAFT)",
                    invoiceUuid, invoice.getLifecycleStatus()
                ),
                Response.Status.BAD_REQUEST
            );
        }

        // Assign invoice number (skip for PHANTOM type)
        if (invoice.getType() != InvoiceType.PHANTOM && invoice.getInvoicenumber() == null) {
            Integer nextNumber = numberingService.getNextInvoiceNumber(
                invoice.getIssuerCompanyuuid(),
                invoice.getInvoiceSeries()
            );
            invoice.setInvoicenumber(nextNumber);
            Log.infof("Assigned invoice number %d to invoice %s", nextNumber, invoiceUuid);
        }

        // Set invoice date if not already set
        if (invoice.getInvoicedate() == null) {
            invoice.setInvoicedate(LocalDate.now());
        }

        // Set due date if not already set (default: 30 days from invoice date)
        if (invoice.getDuedate() == null) {
            invoice.setDuedate(invoice.getInvoicedate().plusDays(30));
        }

        // Compute invoice year and month from invoice date
        invoice.setInvoiceYear(invoice.getInvoicedate().getYear());
        invoice.setInvoiceMonth(invoice.getInvoicedate().getMonthValue());

        // Transition to CREATED state
        stateMachine.transition(invoice, LifecycleStatus.CREATED);

        // Set processing state to IDLE (no queue)
        invoice.setProcessingState(ProcessingState.IDLE);
        invoice.setQueueReason(null);

        repository.persist(invoice);

        Log.infof(
            "Finalized invoice %s (number: %d, type: %s, date: %s)",
            invoiceUuid,
            invoice.getInvoicenumber(),
            invoice.getType(),
            invoice.getInvoicedate()
        );

        return invoice;
    }

    /**
     * Check if an invoice can be finalized.
     *
     * @param invoiceUuid The UUID of the invoice to check
     * @return true if invoice is in DRAFT state and can be finalized
     */
    public boolean canFinalize(String invoiceUuid) {
        Invoice invoice = repository.findById(invoiceUuid);
        return invoice != null && invoice.getLifecycleStatus() == LifecycleStatus.DRAFT;
    }
}
