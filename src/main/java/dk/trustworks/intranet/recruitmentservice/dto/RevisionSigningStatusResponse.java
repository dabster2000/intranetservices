package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Wire shape for {@code GET /candidates/{uuid}/dossier/revisions/{revUuid}/signing-status}.
 * <p>
 * Mirrors data already polled by {@code NextSignStatusSyncBatchlet} and
 * cached in {@code signing_cases} — freshness is bounded by that batchlet's
 * cadence (≤5 minutes), matching the same trade-off used by
 * {@code AdminSigningCasesTab}. The endpoint serves cached data; no
 * NextSign API call is issued per request.
 *
 * @param caseKey       NextSign case key (immutable for the case lifetime)
 * @param caseStatus    {@code PENDING | COMPLETED | DECLINED | EXPIRED}
 * @param createdAt     when the case was created in NextSign
 * @param expiryAt      when the case expires (nullable — depends on NextSign config)
 * @param lastSyncedAt  when the local cache was last refreshed by the sync batchlet
 * @param recipients    one entry per signer in the same order as configured
 */
public record RevisionSigningStatusResponse(
        String caseKey,
        String caseStatus,
        LocalDateTime createdAt,
        LocalDateTime expiryAt,
        LocalDateTime lastSyncedAt,
        List<Recipient> recipients
) {

    /**
     * Per-signer status snapshot.
     *
     * @param status   {@code SIGNED | PENDING | DECLINED | VIEWED}
     * @param signedAt non-null only when {@code status == SIGNED}
     * @param identity non-null only when the signer completed identity verification
     * @param auditLog timeline of NextSign events for this signer
     */
    public record Recipient(
            String name,
            String email,
            String status,
            LocalDateTime signedAt,
            Identity identity,
            List<AuditEntry> auditLog
    ) { }

    /** Identity verification details (e.g. MitID). */
    public record Identity(boolean verified, String method) { }

    /** One NextSign audit event for a signer. */
    public record AuditEntry(String event, LocalDateTime at, String ip) { }
}
