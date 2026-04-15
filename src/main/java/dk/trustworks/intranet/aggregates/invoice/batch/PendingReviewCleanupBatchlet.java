package dk.trustworks.intranet.aggregates.invoice.batch;

import dk.trustworks.intranet.aggregates.invoice.economics.notifications.PendingReviewNotificationService;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceFinalizationOrchestrator;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily batchlet that cancels e-conomics drafts that have been sitting in
 * PENDING_REVIEW for more than 7 days without being booked by an accountant.
 *
 * <p>For each stale invoice:
 * <ol>
 *   <li>Calls {@link InvoiceFinalizationOrchestrator#cancelFinalization(String)} which
 *       deletes the e-conomics draft (swallowing 404) and reverts status to DRAFT.</li>
 *   <li>Sends an operator notification via {@link PendingReviewNotificationService}.</li>
 * </ol>
 *
 * <p>A failure on any single invoice is caught and logged; remaining invoices are
 * still processed.
 *
 * <p>Staleness is measured against {@code invoicedate}: the Invoice entity has no generic
 * {@code updatedAt} timestamp. Since {@code invoicedate} is set before draft creation it is
 * a conservative lower bound — see {@link InvoiceRepository#listPendingReviewOlderThan}.
 *
 * <p>SPEC-INV-001 §9.5.
 */
@ApplicationScoped
public class PendingReviewCleanupBatchlet {

    private static final Logger LOG = Logger.getLogger(PendingReviewCleanupBatchlet.class);

    @Inject
    InvoiceRepository invoices;

    @Inject
    InvoiceFinalizationOrchestrator orchestrator;

    @Inject
    PendingReviewNotificationService notifier;

    /**
     * Scheduled daily at 03:45 (low-traffic window).
     * Cron: second minute hour day-of-month month day-of-week
     */
    @Scheduled(cron = "0 45 3 * * ?", identity = "pending-review-cleanup")
    @Transactional
    void run() {
        LocalDate cutoff = LocalDate.now().minusDays(7);
        List<Invoice> stale = invoices.listPendingReviewOlderThan(cutoff);

        LOG.infof("PendingReviewCleanupBatchlet: found %d stale PENDING_REVIEW invoice(s) older than %s",
                stale.size(), cutoff);

        int cancelled = 0;
        int failed = 0;

        for (Invoice inv : stale) {
            try {
                orchestrator.cancelFinalization(inv.getUuid());
                notifier.notifyAutoReverted(inv);
                cancelled++;
                LOG.infof("Auto-cancelled stale PENDING_REVIEW invoice %s (draftNumber=%s)",
                        inv.getUuid(), inv.getEconomicsDraftNumber());
            } catch (Exception e) {
                LOG.warnf(e, "Could not auto-cancel stale PENDING_REVIEW invoice %s — will retry next run",
                        inv.getUuid());
                failed++;
                // Do not rethrow — continue with remaining invoices
            }
        }

        LOG.infof("PendingReviewCleanupBatchlet completed: total=%d, cancelled=%d, failed=%d",
                stale.size(), cancelled, failed);
    }
}
