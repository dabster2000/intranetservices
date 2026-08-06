package dk.trustworks.intranet.security;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One row of the append-only authorization audit trail (`authz_audit`, V463).
 *
 * <p>Written exclusively by {@link AuthzAuditService} inside the transaction of the
 * authorization mutation it records (Phase 7, task 7.2). <strong>Append-only by
 * construction:</strong> no update or delete path exists anywhere — not in this
 * entity, not in any service, not in any REST resource. Do not add one.
 *
 * <p>{@code before_json} / {@code after_json} are JSON columns in the database but
 * mapped as {@code String} — {@code @JdbcTypeCode(JSON)} has previously crashed boot
 * in this codebase (V463 header note).
 */
@Entity
@Table(name = "authz_audit")
public class AuthzAudit extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_uuid")
    private String actorUuid;

    @Column(name = "action")
    private String action;

    @Column(name = "target_type")
    private String targetType;

    @Column(name = "target_id")
    private String targetId;

    @Column(name = "before_json")
    private String beforeJson;

    @Column(name = "after_json")
    private String afterJson;

    @Column(name = "at")
    private LocalDateTime at;

    public AuthzAudit() {
    }

    public Long getId() {
        return id;
    }

    public String getActorUuid() {
        return actorUuid;
    }

    public void setActorUuid(String actorUuid) {
        this.actorUuid = actorUuid;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public void setBeforeJson(String beforeJson) {
        this.beforeJson = beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public void setAfterJson(String afterJson) {
        this.afterJson = afterJson;
    }

    public LocalDateTime getAt() {
        return at;
    }

    public void setAt(LocalDateTime at) {
        this.at = at;
    }
}
