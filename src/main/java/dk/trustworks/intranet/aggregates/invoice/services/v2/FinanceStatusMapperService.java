package dk.trustworks.intranet.aggregates.invoice.services.v2;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.repositories.InvoiceRepository;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Service for mapping e-conomic upload status to finance_status.
 *
 * This service handles the integration between the ERP system (e-conomic)
 * and the invoice lifecycle. It:
 * 1. Maps ERP upload status to internal finance_status enum
 * 2. Optionally auto-advances lifecycle_status to PAID when ERP confirms payment
 *
 * Feature flag: invoice.lifecycle.auto-advance-on-erp-paid (default: true)
 */
@ApplicationScoped
public class FinanceStatusMapperService {

    @Inject
    InvoiceRepository repository;

    @Inject
    InvoiceStateMachine stateMachine;

    @ConfigProperty(name = "invoice.lifecycle.auto-advance-on-erp-paid", defaultValue = "true")
    boolean autoAdvanceOnErpPaid;

    /**
     * Update invoice finance_status based on e-conomic upload status.
     *
     * Optionally auto-advances lifecycle_status to PAID when ERP reports
     * the invoice as paid (controlled by feature flag).
     *
     * @param invoiceUuid The UUID of the invoice to update
     * @param uploadStatus The upload status from e-conomic (SUCCESS, PAID, ERROR, etc.)
     * @param voucherNumber The voucher number from e-conomic (if booked)
     */
    @Transactional
    public void updateFinanceStatus(String invoiceUuid, String uploadStatus, String voucherNumber) {
        Invoice invoice = repository.findById(invoiceUuid);
        if (invoice == null) {
            Log.warnf("Invoice not found for finance status update: %s", invoiceUuid);
            return;
        }

        FinanceStatus newFinanceStatus = mapUploadStatusToFinanceStatus(uploadStatus, voucherNumber);
        FinanceStatus oldFinanceStatus = invoice.getFinanceStatus();

        if (oldFinanceStatus == newFinanceStatus) {
            Log.debugf("Finance status unchanged for invoice %s: %s", invoiceUuid, newFinanceStatus);
            return;
        }

        invoice.setFinanceStatus(newFinanceStatus);

        Log.infof(
            "Updated finance status for invoice %s: %s → %s",
            invoiceUuid, oldFinanceStatus, newFinanceStatus
        );

        // Auto-advance lifecycle to PAID if ERP says paid and feature flag enabled
        if (newFinanceStatus == FinanceStatus.PAID && autoAdvanceOnErpPaid) {
            if (invoice.getLifecycleStatus() != LifecycleStatus.PAID) {
                if (stateMachine.canTransition(invoice.getLifecycleStatus(), LifecycleStatus.PAID)) {
                    stateMachine.transition(invoice, LifecycleStatus.PAID);
                    Log.infof("Auto-advanced invoice %s to PAID based on ERP status", invoiceUuid);
                } else {
                    Log.warnf(
                        "Cannot auto-advance invoice %s to PAID: invalid transition from %s",
                        invoiceUuid, invoice.getLifecycleStatus()
                    );
                }
            }
        }

        repository.persist(invoice);
    }

    /**
     * Map e-conomic upload status to internal finance_status enum.
     *
     * Mapping rules:
     * - null, "PENDING", "NONE" → NONE
     * - "SUCCESS" with voucher number → BOOKED
     * - "SUCCESS" without voucher number → UPLOADED
     * - "PAID" → PAID
     * - "ERROR", "FAILURE" → ERROR
     *
     * @param uploadStatus The upload status from e-conomic
     * @param voucherNumber The voucher number (indicates booking)
     * @return The corresponding FinanceStatus
     */
    private FinanceStatus mapUploadStatusToFinanceStatus(String uploadStatus, String voucherNumber) {
        if (uploadStatus == null || uploadStatus.equals("PENDING") || uploadStatus.equals("NONE")) {
            return FinanceStatus.NONE;
        }

        if (uploadStatus.equals("SUCCESS")) {
            // If voucher number present, invoice is BOOKED; otherwise just UPLOADED
            boolean hasVoucher = voucherNumber != null && !voucherNumber.trim().isEmpty();
            return hasVoucher ? FinanceStatus.BOOKED : FinanceStatus.UPLOADED;
        }

        if (uploadStatus.equals("PAID")) {
            return FinanceStatus.PAID;
        }

        if (uploadStatus.equals("ERROR") || uploadStatus.equals("FAILURE")) {
            return FinanceStatus.ERROR;
        }

        Log.warnf("Unknown upload status: %s, defaulting to NONE", uploadStatus);
        return FinanceStatus.NONE;
    }

    /**
     * Update finance status with minimal parameters (voucher number optional).
     *
     * @param invoiceUuid The UUID of the invoice to update
     * @param uploadStatus The upload status from e-conomic
     */
    @Transactional
    public void updateFinanceStatus(String invoiceUuid, String uploadStatus) {
        updateFinanceStatus(invoiceUuid, uploadStatus, null);
    }

    /**
     * Reset finance status to NONE (useful for retries or manual corrections).
     *
     * @param invoiceUuid The UUID of the invoice to reset
     */
    @Transactional
    public void resetFinanceStatus(String invoiceUuid) {
        Invoice invoice = repository.findById(invoiceUuid);
        if (invoice == null) {
            Log.warnf("Invoice not found for finance status reset: %s", invoiceUuid);
            return;
        }

        FinanceStatus oldStatus = invoice.getFinanceStatus();
        invoice.setFinanceStatus(FinanceStatus.NONE);
        repository.persist(invoice);

        Log.infof("Reset finance status for invoice %s: %s → NONE", invoiceUuid, oldStatus);
    }
}
