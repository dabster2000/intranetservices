package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.RoleAssignment;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ResponsibilityKind;

import java.time.LocalDateTime;

public record RoleAssignmentResponse(
        String uuid,
        String roleUuid,
        String userUuid,
        ResponsibilityKind responsibilityKind,
        LocalDateTime assignedAt,
        String assignedByUuid) {

    public static RoleAssignmentResponse from(RoleAssignment a) {
        return new RoleAssignmentResponse(
                a.uuid, a.roleUuid, a.userUuid, a.responsibilityKind, a.assignedAt, a.assignedByUuid);
    }
}
