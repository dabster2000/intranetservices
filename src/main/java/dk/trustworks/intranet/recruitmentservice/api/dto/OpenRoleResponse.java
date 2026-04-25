package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringCategory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringSource;
import dk.trustworks.intranet.recruitmentservice.domain.enums.PipelineKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import dk.trustworks.intranet.recruitmentservice.domain.enums.WorkstreamStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record OpenRoleResponse(
        String uuid,
        String title,
        HiringCategory hiringCategory,
        PipelineKind pipelineKind,
        Practice practice,
        String careerLevelUuid,
        String companyUuid,
        String teamUuid,
        String functionArea,
        HiringSource hiringSource,
        String hiringReason,
        LocalDate targetStartDate,
        BigDecimal expectedAllocation,
        String expectedRateBand,
        Integer salaryMin,
        Integer salaryMax,
        String currency,
        Integer priority,
        RoleStatus status,
        WorkstreamStatus advertisingStatus,
        WorkstreamStatus searchStatus,
        String createdByUuid,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static OpenRoleResponse from(OpenRole r) {
        return new OpenRoleResponse(
                r.uuid, r.title, r.hiringCategory, r.pipelineKind, r.practice,
                r.careerLevelUuid, r.companyUuid, r.teamUuid, r.functionArea,
                r.hiringSource, r.hiringReason, r.targetStartDate, r.expectedAllocation,
                r.expectedRateBand, r.salaryMin, r.salaryMax, r.currency, r.priority,
                r.status, r.advertisingStatus, r.searchStatus,
                r.createdByUuid, r.createdAt, r.updatedAt);
    }
}
