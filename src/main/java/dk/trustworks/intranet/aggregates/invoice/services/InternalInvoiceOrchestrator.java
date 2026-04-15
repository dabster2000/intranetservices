package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

/**
 * Coordinates the two sides of an INTERNAL (or INTERNAL_SERVICE) invoice.
 *
 * <p>ISSUER side: routed through {@link InvoiceFinalizationOrchestrator} (standard Q2C draft+book).
 * DEBTOR side: voucher posted to the debtor company's e-conomic via
 * {@link dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService#sendVoucherToCompany}.
 *
 * <p>If either side fails the economics_status is set to {@code PARTIALLY_UPLOADED} so the
 * retry batch can detect and re-attempt. A full success leaves status at {@code BOOKED}
 * (set by the issuer side's {@code bookDraft}).
 *
 * <p>Credit notes are handled transparently: negative {@code grandTotal} values flow through
 * both sides unchanged — the ISSUER mapper already produces negative line amounts (H5),
 * and {@code EconomicsInvoiceService.sendVoucherToCompany} uses {@code getGrandTotal()}
 * directly, so negative amounts produce a supplier credit entry on the DEBTOR side.
 *
 * SPEC-INV-001 §4.5, §4.7, §10.
 */
@JBossLog
@ApplicationScoped
public class InternalInvoiceOrchestrator {

    @Inject
    InvoiceFinalizationOrchestrator issuerSide;

    @Inject
    dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService economicsInvoiceService;

    @Inject
    InvoiceRepository invoices;

    /**
     * Creates an e-conomic draft on the ISSUER side (step 1).
     *
     * <p>This is identical to the normal flow — the DEBTOR side only fires at book time.
     *
     * @param invoiceUuid the UUID of the INTERNAL or INTERNAL_SERVICE invoice
     * @return the updated invoice entity (status = PENDING_REVIEW)
     */
    @Transactional
    public Invoice createDraft(String invoiceUuid) {
        assertInternalType(invoiceUuid);
        return issuerSide.createDraft(invoiceUuid);
    }

    /**
     * Books the ISSUER-side draft and immediately posts the DEBTOR-side supplier voucher.
     *
     * <p>If the DEBTOR-side post fails the invoice economics_status is set to
     * {@code PARTIALLY_UPLOADED} so a retry batchlet can re-attempt.
     *
     * @param invoiceUuid the UUID of the INTERNAL or INTERNAL_SERVICE invoice
     * @param sendBy      optional delivery method (null | "ean" | "Email")
     * @return the updated invoice entity
     */
    @Transactional
    public Invoice bookDraft(String invoiceUuid, String sendBy) {
        assertInternalType(invoiceUuid);
        return issuerSide.bookDraft(invoiceUuid, sendBy);
        // DEBTOR-side is handled inline in InvoiceFinalizationOrchestrator.bookDraft
        // via the INTERNAL-type guard added in H11. No duplicate call needed here.
    }

    /**
     * Convenience entry point for the queued batchlet (H12) — creates draft and books in
     * a single call, with no review step, using {@code sendBy = null}.
     *
     * @param invoiceUuid the UUID of the INTERNAL or INTERNAL_SERVICE invoice
     * @return the updated invoice entity (status = CREATED if both sides succeed)
     */
    @Transactional
    public Invoice finalizeAutomatically(String invoiceUuid) {
        assertInternalType(invoiceUuid);
        issuerSide.createDraft(invoiceUuid);
        return issuerSide.bookDraft(invoiceUuid, null);
        // DEBTOR side fires inline in bookDraft — no additional call required.
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void assertInternalType(String invoiceUuid) {
        Invoice inv = invoices.findByUuid(invoiceUuid)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invoice not found: " + invoiceUuid));
        if (inv.getType() != InvoiceType.INTERNAL
                && inv.getType() != InvoiceType.INTERNAL_SERVICE) {
            throw new IllegalArgumentException(
                    "InternalInvoiceOrchestrator only handles INTERNAL/INTERNAL_SERVICE invoices, "
                    + "got " + inv.getType() + " for " + invoiceUuid);
        }
    }
}
