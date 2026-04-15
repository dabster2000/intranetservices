package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

/**
 * Facade over {@link InvoiceBonusService} that provides the Invoice-object-level
 * API the orchestrator needs. Keeps the orchestrator decoupled from the bonus
 * subsystem's string-uuid API and from the parent-invoice mutation logic that
 * currently lives inside InvoiceService.
 *
 * TODO(phase-H-followup): real impl for clearBonusFieldsOnParent and
 *   restoreParentBonusFields — they replicate InvoiceService.clearBonusFields logic.
 */
@JBossLog
@ApplicationScoped
public class BonusService {

    @Inject
    InvoiceBonusService invoiceBonusService;

    @Inject
    InvoiceRepository invoiceRepository;

    /**
     * Recalculates bonus lines for the given invoice.
     * Delegates to InvoiceBonusService.recalcForInvoice(uuid).
     */
    @Transactional
    public void recalcForInvoice(Invoice invoice) {
        invoiceBonusService.recalcForInvoice(invoice.getUuid());
    }

    /**
     * Clears bonus fields on the parent invoice of a credit note.
     * Called during createDraft for CREDIT_NOTE type.
     *
     * TODO(phase-H-followup): real impl — load parent via creditnoteForUuid,
     *   clear fields, persist.
     */
    @Transactional
    public void clearBonusFieldsOnParent(Invoice creditNote) {
        // TODO(phase-H-followup): real impl
        log.debugf("clearBonusFieldsOnParent: creditNoteUuid=%s, parentUuid=%s",
                creditNote.getUuid(), creditNote.getCreditnoteForUuid());
    }

    /**
     * Restores bonus fields on the parent invoice when a credit note finalization
     * is cancelled (cancelFinalization revert).
     *
     * TODO(phase-H-followup): real impl — reload parent, restore bonus fields.
     */
    @Transactional
    public void restoreParentBonusFields(Invoice creditNote) {
        // TODO(phase-H-followup): real impl
        log.debugf("restoreParentBonusFields: creditNoteUuid=%s, parentUuid=%s",
                creditNote.getUuid(), creditNote.getCreditnoteForUuid());
    }
}
