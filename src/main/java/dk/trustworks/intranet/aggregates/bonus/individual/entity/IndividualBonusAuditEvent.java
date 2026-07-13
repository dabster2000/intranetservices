package dk.trustworks.intranet.aggregates.bonus.individual.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "individual_bonus_audit_event")
public class IndividualBonusAuditEvent extends PanacheEntityBase {
    @Id @Column(name = "uuid", nullable = false, length = 36) private String uuid;
    @Column(name = "occurred_at", nullable = false) private LocalDateTime occurredAt;
    @Column(name = "event_type", nullable = false, length = 64) private String eventType;
    @Column(name = "result", nullable = false, length = 32) private String result;
    @Column(name = "actor_uuid", nullable = false, length = 64) private String actorUuid;
    @Column(name = "user_uuid", length = 36) private String userUuid;
    @Column(name = "rule_uuid", length = 36) private String ruleUuid;
    @Column(name = "adjustment_uuid", length = 36) private String adjustmentUuid;
    @Column(name = "earning_month") private LocalDate earningMonth;
    @Column(name = "pay_month") private LocalDate payMonth;
    @Column(name = "before_hash", length = 64) private String beforeHash;
    @Column(name = "after_hash", length = 64) private String afterHash;
    @Column(name = "proof_action", length = 32) private String proofAction;
    @Column(name = "correlation_id", length = 64) private String correlationId;
    @Column(name = "metadata_json") private String metadataJson;
}
