package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Records {@link ClientEconomicsSyncFailure} rows in a separate transaction so
 * they survive a rollback of the calling sync operation.
 *
 * <p>Split out of {@link EconomicsCustomerSyncService} because a {@code REQUIRES_NEW}
 * boundary only works when the method is invoked through a CDI proxy — a call
 * to {@code this.record(...)} inside the same bean would bypass the interceptor.
 *
 * SPEC-INV-001 §6.8, §7.1 Phase G2.
 */
@ApplicationScoped
public class SyncFailureRecorder {

    /** Retries after this many failures → status=ABANDONED (§6.8). */
    public static final int MAX_ATTEMPTS_BEFORE_ABANDON = 6;

    @Inject
    ClientEconomicsSyncFailureRepository failures;

    /**
     * Upserts a failure row for the given (client, company) pair in a new
     * transaction. Callers that are themselves {@code @Transactional} will
     * always see this insert committed even if their own tx later rolls back.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void record(String clientUuid, String companyUuid, String error) {
        ClientEconomicsSyncFailure f = failures.findByClientAndCompany(clientUuid, companyUuid)
                .orElseGet(() -> {
                    ClientEconomicsSyncFailure n = new ClientEconomicsSyncFailure();
                    n.setUuid(UUID.randomUUID().toString());
                    n.setClientUuid(clientUuid);
                    n.setCompanyUuid(companyUuid);
                    return n;
                });
        f.setAttemptCount(f.getAttemptCount() + 1);
        f.setLastError(error);
        f.setLastAttemptedAt(LocalDateTime.now());
        f.setNextRetryAt(nextRetryAfter(f.getAttemptCount()));
        if (f.getAttemptCount() >= MAX_ATTEMPTS_BEFORE_ABANDON) {
            f.setStatus("ABANDONED");
        }
        failures.persist(f);
    }

    /** Removes the failure row on success, in a new transaction. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void clear(String clientUuid, String companyUuid) {
        failures.findByClientAndCompany(clientUuid, companyUuid).ifPresent(failures::delete);
    }

    /**
     * Exponential backoff per §6.8: 1m, 5m, 15m, 1h, 4h, then 24h. Public so
     * other services (e.g. contact sync) can share the same schedule.
     */
    public static LocalDateTime nextRetryAfter(int attempts) {
        int[] minutes = { 1, 5, 15, 60, 240, 1440 };
        int idx = Math.min(Math.max(attempts - 1, 0), minutes.length - 1);
        return LocalDateTime.now().plusMinutes(minutes[idx]);
    }
}
