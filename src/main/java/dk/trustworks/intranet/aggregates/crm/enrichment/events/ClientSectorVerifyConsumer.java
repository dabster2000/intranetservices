package dk.trustworks.intranet.aggregates.crm.enrichment.events;

import dk.trustworks.intranet.aggregates.crm.enrichment.services.ClientEnrichmentService;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * The asynchronous half of "when a client is added or edited with sector OTHER, an AI
 * verifies the sector" — the {@code ExpenseJustificationAiConsumer} shape.
 *
 * <p>{@code ClientResource} publishes the client uuid on {@link
 * ClientEnrichmentService#SECTOR_VERIFY_ADDRESS} after its own write has committed; this
 * consumer runs the model call on a worker thread ({@code blocking = true}) outside any
 * transaction and writes the verdict in one of its own. The HTTP response to the person
 * who saved the form never waits for the model.
 */
@JBossLog
@ApplicationScoped
public class ClientSectorVerifyConsumer {

    @Inject
    ClientEnrichmentService enrichmentService;

    @ConsumeEvent(value = ClientEnrichmentService.SECTOR_VERIFY_ADDRESS, blocking = true)
    public void onSectorVerifyRequested(String clientUuid) {
        try {
            enrichmentService.verifySectorNow(clientUuid);
        } catch (Exception e) {
            // Fail quiet: the nightly pass finds the row again through its PENDING status.
            log.errorf(e, "Sector verification consumer failed for client=%s", clientUuid);
        }
    }
}
