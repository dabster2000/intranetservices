package dk.trustworks.intranet.aggregates.invoice.events;

import dk.trustworks.intranet.aggregates.invoice.services.InvoiceAttributionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.concurrent.CompletableFuture;

/**
 * Observes {@link InvoiceAttributionsDirtyEvent} only after the originating
 * transaction has successfully committed ({@link TransactionPhase#AFTER_SUCCESS}),
 * then off-loads the attribution computation to a background thread so the
 * HTTP request that triggered the draft write returns immediately.
 *
 * <p>The actual computation runs in its own {@code @Transactional} scope
 * inside {@link InvoiceAttributionService#computeAttributions(String)}, which
 * re-reads the committed invoice from the database. A transient failure is
 * logged; the admin "Recompute attributions" action can recover the invoice.
 */
@ApplicationScoped
@JBossLog
public class InvoiceAttributionDirtyObserver {

    @Inject
    InvoiceAttributionService attributionService;

    public void onInvoiceCommitted(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) InvoiceAttributionsDirtyEvent event) {
        String invoiceUuid = event.invoiceUuid();
        CompletableFuture.runAsync(() -> {
            try {
                attributionService.computeAttributions(invoiceUuid);
            } catch (Exception e) {
                log.errorf(e,
                        "Async attribution computation failed for invoice uuid=%s — admin recompute can recover",
                        invoiceUuid);
            }
        });
    }
}
