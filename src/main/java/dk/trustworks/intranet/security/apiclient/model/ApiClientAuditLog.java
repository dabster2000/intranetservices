package dk.trustworks.intranet.security.apiclient.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Immutable audit trail entry for API client lifecycle and token events.
 * Rows are append-only: no UPDATE or DELETE operations are permitted
 * at the application layer.
 */
@Entity
@Table(name = "api_client_audit_log", indexes = {
        @Index(name = "idx_audit_client_created", columnList = "client_uuid, created_at")
})
@Getter
@NoArgsConstructor
public class ApiClientAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_uuid", nullable = false, length = 36)
    private String clientUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private AuditEventType eventType;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public ApiClientAuditLog(String clientUuid, AuditEventType eventType, String ipAddress, String details) {
        this.clientUuid = clientUuid;
        this.eventType = eventType;
        this.ipAddress = ipAddress;
        this.details = details;
    }
}
