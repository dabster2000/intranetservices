package dk.trustworks.intranet.recruitmentservice.domain.integration;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "recruitment_external_outbox")
public class RecruitmentOutboxRow extends PanacheEntityBase {

    @Id
    @Column(name = "uuid")
    public String uuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 48)
    public OutboxKind kind;

    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    public String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    public OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    @Column(name = "next_retry_at", nullable = false)
    public LocalDateTime nextRetryAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;

    @Column(name = "last_attempt_at")
    public LocalDateTime lastAttemptAt;

    @Column(name = "idempotency_key", nullable = false, length = 160, unique = true)
    public String idempotencyKey;

    @Column(name = "related_uuid", length = 36)
    public String relatedUuid;

    @Column(name = "created_at", nullable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public LocalDateTime updatedAt;

    public static RecruitmentOutboxRow create(OutboxKind kind, String idempotencyKey,
                                              String relatedUuid, String payloadJson) {
        RecruitmentOutboxRow r = new RecruitmentOutboxRow();
        r.uuid = UUID.randomUUID().toString();
        r.kind = kind;
        r.idempotencyKey = idempotencyKey;
        r.relatedUuid = relatedUuid;
        r.payloadJson = payloadJson;
        r.status = OutboxStatus.PENDING;
        r.attemptCount = 0;
        LocalDateTime now = LocalDateTime.now();
        r.nextRetryAt = now;
        r.createdAt = now;
        r.updatedAt = now;
        return r;
    }
}
