package dk.trustworks.intranet.recruitmentservice.domain.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "recruitment_outbox")
public class OutboxEntry extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String uuid;

    @Column(name = "kind", length = 40, nullable = false)
    public String kind;  // OutboxKind.name()

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "JSON", nullable = false)
    public String payload;

    @Column(name = "target_ref", length = 255)
    public String targetRef;

    @Column(name = "status", length = 40, nullable = false)
    public String status;  // OutboxStatus.name()

    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    public LocalDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    public String lastError;

    @Column(name = "created_at", insertable = false, updatable = false)
    public LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    public LocalDateTime updatedAt;
}
