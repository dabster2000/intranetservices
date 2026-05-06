package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;

/**
 * Lightweight signing-status summary for the collapsed view of
 * {@code RevisionSigningStatusPanel}. Read entirely from the local
 * {@code signing_cases} cache — no NextSign API call. Per-signer
 * status (audit log, identity) only available via the full
 * {@code /signing-status} endpoint.
 *
 * @param caseKey         NextSign case key
 * @param caseStatus      cached case status (e.g. PENDING, COMPLETED)
 * @param totalSigners    count of signers configured on the case
 * @param completedSigners count that have signed; (total - completed) = pending
 * @param lastStatusFetch when the local cache was last refreshed by the
 *                        {@code NextSignStatusSyncBatchlet}
 */
public record RevisionSigningStatusSummary(
        String caseKey,
        String caseStatus,
        int totalSigners,
        int completedSigners,
        LocalDateTime lastStatusFetch
) { }
