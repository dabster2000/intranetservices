package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Tracks per-(client,company) sync failures. The retry batchlet iterates this table.
 * One row per (client_uuid, company_uuid). Schema from V290__Add_client_economics_sync_failures.sql.
 * SPEC-INV-001 §7.1 Phase G2, §6.8.
 */
@Entity
@Table(
        name = "client_economics_sync_failures",
        uniqueConstraints = @UniqueConstraint(columnNames = {"client_uuid", "company_uuid"})
)
@Getter @Setter
public class ClientEconomicsSyncFailure {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    @Column(name = "company_uuid", length = 36, nullable = false)
    private String companyUuid;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "last_attempted_at")
    private LocalDateTime lastAttemptedAt;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
