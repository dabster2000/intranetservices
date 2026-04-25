package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.ResponsibilityKind;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "recruitment_role_assignment")
public class RoleAssignment extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    public String uuid;

    @Column(name = "role_uuid", length = 36, nullable = false)
    public String roleUuid;

    @Column(name = "user_uuid", length = 36, nullable = false)
    public String userUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "responsibility_kind", length = 30, nullable = false)
    public ResponsibilityKind responsibilityKind;

    @Column(name = "assigned_at", nullable = false)
    public LocalDateTime assignedAt = LocalDateTime.now();

    @Column(name = "assigned_by_uuid", length = 36, nullable = false)
    public String assignedByUuid;

    public RoleAssignment() {}

    public static RoleAssignment fresh(String roleUuid, String userUuid, ResponsibilityKind kind, String actor) {
        RoleAssignment a = new RoleAssignment();
        a.uuid = UUID.randomUUID().toString();
        a.roleUuid = roleUuid;
        a.userUuid = userUuid;
        a.responsibilityKind = kind;
        a.assignedByUuid = actor;
        return a;
    }
}
