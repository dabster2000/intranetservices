package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Retry loop for e-conomic customer syncs that previously failed. Every minute
 * the batchlet scans {@code client_economics_sync_failures} for rows whose
 * {@code status='PENDING'} and whose {@code next_retry_at <= NOW()}, then calls
 * {@link EconomicsCustomerSyncService#syncToCompany(Client, String)} for each
 * one. A success inside the sync service clears the failure row; a failure
 * re-records it with the next {@code next_retry_at} per the §6.8 backoff
 * schedule (1m / 5m / 15m / 1h / 4h / 24h — 6 attempts then ABANDONED).
 *
 * <p>The batchlet is deliberately thin — all retry / backoff / failure-row
 * bookkeeping is handled by the sync service. This class only orchestrates
 * "which rows are due right now".
 *
 * <p>Absent clients (deleted after a failure was recorded) cause the failure
 * row to be removed so it never retries forever.
 *
 * SPEC-INV-001 §6.8, §7.1 Phase G2.
 */
@ApplicationScoped
public class EconomicsCustomerSyncRetryBatchlet {

    private static final Logger LOG = Logger.getLogger(EconomicsCustomerSyncRetryBatchlet.class);

    @Inject
    ClientEconomicsSyncFailureRepository failures;

    @Inject
    ClientService clientService;

    @Inject
    EconomicsCustomerSyncService syncService;

    @Scheduled(every = "1m", identity = "economics-sync-retry")
    void retryDueFailures() {
        List<ClientEconomicsSyncFailure> due = failures.listDueForRetry(LocalDateTime.now());
        if (due.isEmpty()) {
            return;
        }
        LOG.infof("Retrying %d e-conomic customer syncs", due.size());
        for (ClientEconomicsSyncFailure f : due) {
            retryOne(f);
        }
    }

    /**
     * Retry a single failure row. Extracted for testability and to keep one
     * error from derailing the rest of the batch.
     */
    void retryOne(ClientEconomicsSyncFailure f) {
        Client client = clientService.findByUuid(f.getClientUuid());
        if (client == null) {
            LOG.warnf("Client %s no longer exists — removing failure entry", f.getClientUuid());
            failures.delete(f);
            return;
        }
        try {
            // Success clears the failure row from inside syncToCompany.
            // Failure re-persists it with a fresh next_retry_at and re-throws
            // SyncFailedException — which we swallow here so one bad row
            // doesn't break the whole batch.
            syncService.syncToCompany(client, f.getCompanyUuid());
        } catch (SyncFailedException e) {
            LOG.debugf("Sync retry still failing for client %s / company %s — next_retry_at rescheduled",
                    f.getClientUuid(), f.getCompanyUuid());
        } catch (RuntimeException e) {
            LOG.warnf(e, "Unexpected error retrying sync for client %s / company %s",
                    f.getClientUuid(), f.getCompanyUuid());
        }
    }
}
