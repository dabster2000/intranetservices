package dk.trustworks.intranet.aggregates.invoice.services.v2;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceV2;
import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.repositories.InvoiceV2Repository;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Service for promoting queued internal invoices.
 *
 * Internal invoices (expenses billed to customers) are created in DRAFT state
 * and queued (QUEUED processing state with AWAIT_SOURCE_PAID reason) until
 * the source invoice (the invoice from vendor to company) is paid.
 *
 * This scheduled job:
 * 1. Finds queued internal invoices
 * 2. Checks if source invoice is PAID
 * 3. Promotes internal invoice to CREATED (finalizes it)
 *
 * Feature flag: invoice.promotion.use-finance-status (default: true)
 * - When true: Uses finance_status to determine if source is paid
 * - When false: Uses lifecycle_status to determine if source is paid
 */
@ApplicationScoped
public class InternalInvoicePromotionService {

    @Inject
    InvoiceV2Repository repository;

    @Inject
    FinalizationService finalizationService;

    @ConfigProperty(name = "invoice.promotion.use-finance-status", defaultValue = "true")
    boolean useFinanceStatus;

    /**
     * Scheduled job to promote queued internal invoices.
     * Default: Runs every 30 seconds.
     *
     * Configuration property: Not exposed (fixed schedule)
     * Schedule: Every 30 seconds
     */
    @Scheduled(every = "30s")
    @Transactional
    public void promoteQueuedInternals() {
        List<InvoiceV2> queued = repository.findQueuedForPromotion();

        if (queued.isEmpty()) {
            Log.debug("No queued internal invoices to promote");
            return;
        }

        Log.infof("Found %d queued internal invoices to evaluate for promotion", queued.size());

        int promoted = 0, waiting = 0, errors = 0;

        for (InvoiceV2 invoice : queued) {
            try {
                if (shouldPromote(invoice)) {
                    finalizationService.finalize(invoice.getUuid());
                    promoted++;
                    Log.infof("Promoted internal invoice %s (source invoice paid)", invoice.getUuid());
                } else {
                    waiting++;
                    Log.debugf("Internal invoice %s still waiting for source invoice payment", invoice.getUuid());
                }
            } catch (Exception e) {
                errors++;
                Log.errorf(e, "Failed to promote internal invoice %s", invoice.getUuid());
            }
        }

        if (promoted > 0 || errors > 0) {
            Log.infof(
                "Promotion cycle complete: %d promoted, %d waiting, %d errors",
                promoted, waiting, errors
            );
        }
    }

    /**
     * Determine if an internal invoice should be promoted.
     *
     * Checks if the source invoice is paid based on feature flag:
     * - use-finance-status=true: Check if source finance_status is PAID
     * - use-finance-status=false: Check if source lifecycle_status is PAID
     *
     * @param invoice The internal invoice to evaluate
     * @return true if source invoice is paid and internal should be promoted
     */
    private boolean shouldPromote(InvoiceV2 invoice) {
        String sourceInvoiceUuid = invoice.getSourceInvoiceUuid();
        if (sourceInvoiceUuid == null) {
            Log.warnf("Queued internal invoice %s has no source invoice UUID", invoice.getUuid());
            return false;
        }

        InvoiceV2 sourceInvoice = repository.findById(sourceInvoiceUuid);
        if (sourceInvoice == null) {
            Log.warnf(
                "Source invoice not found for internal invoice %s: %s",
                invoice.getUuid(), sourceInvoiceUuid
            );
            return false;
        }

        // Check if source is paid based on feature flag
        boolean isPaid = useFinanceStatus
                        ? sourceInvoice.getFinanceStatus() == FinanceStatus.PAID
                        : sourceInvoice.getLifecycleStatus() == LifecycleStatus.PAID;

        Log.debugf(
            "Source invoice %s paid status: %b (using %s)",
            sourceInvoiceUuid, isPaid,
            useFinanceStatus ? "finance_status" : "lifecycle_status"
        );

        return isPaid;
    }

    /**
     * Manually promote a specific internal invoice (admin operation).
     *
     * @param invoiceUuid The UUID of the internal invoice to promote
     * @return The finalized invoice
     */
    @Transactional
    public InvoiceV2 manuallyPromote(String invoiceUuid) {
        InvoiceV2 invoice = repository.findById(invoiceUuid);
        if (invoice == null) {
            throw new IllegalArgumentException("Invoice not found: " + invoiceUuid);
        }

        if (invoice.getLifecycleStatus() != LifecycleStatus.DRAFT) {
            throw new IllegalStateException(
                String.format(
                    "Cannot manually promote invoice %s: not in DRAFT state (current: %s)",
                    invoiceUuid, invoice.getLifecycleStatus()
                )
            );
        }

        InvoiceV2 promoted = finalizationService.finalize(invoiceUuid);
        Log.infof("Manually promoted internal invoice %s", invoiceUuid);

        return promoted;
    }
}
