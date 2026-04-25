package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.RecruitmentStatusEntity;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RecruitmentStatusValue;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ScopeKind;

import java.time.LocalDateTime;

public record RecruitmentStatusDTO(
        ScopeKind scopeKind, String scopeId, RecruitmentStatusValue status,
        String reason, String changedByUuid, LocalDateTime changedAt) {

    public static RecruitmentStatusDTO from(RecruitmentStatusEntity e) {
        return new RecruitmentStatusDTO(
                e.scopeKind, e.scopeId, e.status, e.reason, e.changedByUuid, e.changedAt);
    }
}
