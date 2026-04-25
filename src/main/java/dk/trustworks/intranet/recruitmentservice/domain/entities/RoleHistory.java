package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "recruitment_role_history")
public class RoleHistory extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String uuid;

    @Column(name = "role_uuid", length = 36, nullable = false)
    public String roleUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 40)
    public RoleStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 40, nullable = false)
    public RoleStatus toStatus;

    @Column(columnDefinition = "TEXT")
    public String reason;

    @Column(name = "actor_uuid", length = 36, nullable = false)
    public String actorUuid;

    @Column(name = "at", nullable = false)
    public LocalDateTime at = LocalDateTime.now();

    public RoleHistory() {}

    public static RoleHistory entry(String roleUuid, RoleStatus from, RoleStatus to, String reason, String actor) {
        RoleHistory h = new RoleHistory();
        h.uuid = UUID.randomUUID().toString();
        h.roleUuid = roleUuid;
        h.fromStatus = from;
        h.toStatus = to;
        h.reason = reason;
        h.actorUuid = actor;
        return h;
    }
}
