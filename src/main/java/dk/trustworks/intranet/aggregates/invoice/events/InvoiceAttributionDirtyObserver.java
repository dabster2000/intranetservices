package dk.trustworks.intranet.aggregates.invoice.events;

import dk.trustworks.intranet.aggregates.invoice.services.InvoiceAttributionService;
import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.Duration;
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

    @Inject
    PracticeRevenueDirtyMarker dirtyMarker;

    @Inject
    ManagedExecutor managedExecutor;

    @ConfigProperty(name = "practices.invoice-attribution.async-timeout", defaultValue = "PT2M")
    Duration asyncTimeout;

    public void onInvoiceCommitted(
            @Observes(during = TransactionPhase.AFTER_SUCCESS) InvoiceAttributionsDirtyEvent event) {
        String invoiceUuid = event.invoiceUuid();
        // The attribution transaction advances the version exactly once with its evidence write.
        // This RUNNING token blocks publication while work is queued/in flight. Timeout does not
        // claim to cancel the underlying query: it fails the watermark closed until recovery.
        String token = dirtyMarker.beginAsyncMutation(
                PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION);
        try {
            CompletableFuture.runAsync(
                            () -> attributionService.computeAttributions(invoiceUuid), managedExecutor)
                    .orTimeout(asyncTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            dirtyMarker.completeAsyncMutation(
                                    PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION, token);
                        } else {
                            dirtyMarker.failAsyncMutation(
                                    PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION, token);
                            log.errorf(failure,
                                    "Async attribution computation failed for invoice uuid=%s; source watermark failed closed",
                                    invoiceUuid);
                        }
                    });
        } catch (RuntimeException dispatchFailure) {
            dirtyMarker.failAsyncMutation(
                    PracticeRevenueDirtyMarker.Source.INVOICE_ATTRIBUTION, token);
            log.errorf(dispatchFailure,
                    "Async attribution computation could not be dispatched for invoice uuid=%s; source watermark failed closed",
                    invoiceUuid);
        }
    }
}
