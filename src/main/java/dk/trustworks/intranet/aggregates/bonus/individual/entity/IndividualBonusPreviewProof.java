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
@Table(name = "individual_bonus_preview_proof")
public class IndividualBonusPreviewProof extends PanacheEntityBase {
    @Id
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;
    @Column(name = "action", nullable = false, length = 32)
    private String action;
    @Column(name = "actor_uuid", nullable = false, length = 64)
    private String actorUuid;
    @Column(name = "user_uuid", nullable = false, length = 36)
    private String userUuid;
    @Column(name = "rule_uuid", length = 36)
    private String ruleUuid;
    @Column(name = "rule_revision")
    private Long ruleRevision;
    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;
    @Column(name = "target_uuid", length = 36)
    private String targetUuid;
    @Column(name = "target_version")
    private Long targetVersion;
    @Column(name = "idempotency_key", length = 36)
    private String idempotencyKey;
    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;
}
