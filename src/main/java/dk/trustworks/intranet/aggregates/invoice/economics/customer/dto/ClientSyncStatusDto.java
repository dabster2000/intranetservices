package dk.trustworks.intranet.aggregates.invoice.economics.customer.dto;

import java.time.LocalDateTime;

/**
 * Per-(client, company) e-conomic sync status, returned by
 * {@code GET /economics/sync-status?clientUuid=…}. One row per configured
 * Trustworks company — agreements without a paired customer surface as
 * {@code UNPAIRED}, and rows whose sync is being retried surface as
 * {@code PENDING}. {@code ABANDONED} indicates 6+ failed attempts; manual
 * intervention is required.
 *
 * <p>The string values of {@code status} intentionally match the
 * {@code TSyncStatus} union in the frontend
 * ({@code trustworks-intranet-v2/src/lib/types/economics-sync.ts}).
 *
 * SPEC-INV-001 §7.1 Phase G2, §8.6.
 *
 * @param clientUuid         owning Trustworks client UUID
 * @param companyUuid        Trustworks company / agreement UUID
 * @param companyName        human-readable company label (e.g. "Trustworks A/S")
 * @param status             OK | PENDING | ABANDONED | UNPAIRED
 * @param attemptCount       number of retry attempts consumed so far (0 for OK/UNPAIRED)
 * @param lastError          last HTTP / runtime error, null for OK/UNPAIRED
 * @param nextRetryAt        next scheduled retry (only populated while PENDING)
 * @param lastAttemptedAt    timestamp of the most recent attempt, null for OK/UNPAIRED
 */
public record ClientSyncStatusDto(
        String clientUuid,
        String companyUuid,
        String companyName,
        String status,
        int attemptCount,
        String lastError,
        LocalDateTime nextRetryAt,
        LocalDateTime lastAttemptedAt
) {
    public static final String STATUS_OK = "OK";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ABANDONED = "ABANDONED";
    public static final String STATUS_UNPAIRED = "UNPAIRED";
}
