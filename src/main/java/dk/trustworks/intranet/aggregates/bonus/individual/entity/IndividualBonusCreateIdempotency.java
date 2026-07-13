package dk.trustworks.intranet.aggregates.bonus.individual.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "individual_bonus_create_idempotency")
public class IndividualBonusCreateIdempotency extends PanacheEntityBase {
    @Id
    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;
    @Column(name = "actor_uuid", nullable = false, length = 64)
    private String actorUuid;
    @Column(name = "user_uuid", nullable = false, length = 36)
    private String userUuid;
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;
    @Column(name = "state", nullable = false, length = 20)
    private String state;
    @Column(name = "result_rule_uuid", length = 36)
    private String resultRuleUuid;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
