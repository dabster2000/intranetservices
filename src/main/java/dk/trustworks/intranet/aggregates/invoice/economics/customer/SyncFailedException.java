package dk.trustworks.intranet.aggregates.invoice.economics.customer;

/**
 * Raised when a {@link dk.trustworks.intranet.dao.crm.model.Client Client} could
 * not be synced to e-conomic for a specific agreement. Callers use this to
 * decide whether to surface a non-fatal warning while allowing the local
 * persist/transaction to complete.
 *
 * <p>The sync service records the failure in
 * {@code client_economics_sync_failures} before throwing, so the retry
 * batchlet can pick the failure up later.
 *
 * SPEC-INV-001 §7.1 Phase G2, §6.8.
 */
public class SyncFailedException extends RuntimeException {
    public SyncFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
