package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.aggregates.client.events.ClientGraduatedEvent;
import dk.trustworks.intranet.dao.crm.model.Client;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

/**
 * Creates the e-conomic customer for a prospect that has just become a customer.
 *
 * <p>This is where the sync that used to run on client CREATION now runs for a prospect.
 * The move is the point of the whole type: a company somebody had a coffee with used to
 * become a customer in both e-conomic agreements the same minute it was written down, and
 * now it becomes one the minute it is actually billable.
 *
 * <p>{@link TransactionPhase#AFTER_SUCCESS}, so nothing is created in e-conomic for a
 * contract that rolled back — and the HTTP calls happen with no transaction open, which is
 * the rule every Graph and model call in this codebase already follows.
 *
 * <p>A failure here is recorded per agreement by the sync service for the retry batchlet
 * and never propagated: the contract is saved and the customer exists in Intra either way.
 */
@ApplicationScoped
@JBossLog
public class ClientGraduationObserver {

    @Inject
    EconomicsCustomerSyncService syncService;

    public void onClientGraduated(@Observes(during = TransactionPhase.AFTER_SUCCESS) ClientGraduatedEvent event) {
        Client client = Client.findById(event.clientUuid());
        if (client == null) {
            log.warnf("Graduated client %s (%s) no longer exists — nothing synced to e-conomic",
                    event.clientUuid(), event.clientName());
            return;
        }
        try {
            syncService.syncToAllCompanies(client);
            log.infof("Prospect graduated to customer and synced to e-conomic: uuid=%s name=%s",
                    client.getUuid(), client.getName());
        } catch (RuntimeException e) {
            log.warnf(e, "e-conomic customer sync failed for graduated client uuid=%s; "
                    + "retry batchlet will pick up any recorded failures", client.getUuid());
        }
    }
}
