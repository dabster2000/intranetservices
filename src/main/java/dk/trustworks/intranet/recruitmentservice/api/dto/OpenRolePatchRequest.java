package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.WorkstreamStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OpenRolePatchRequest(
        String title,
        String hiringReason,
        LocalDate targetStartDate,
        BigDecimal expectedAllocation,
        String expectedRateBand,
        Integer salaryMin,
        Integer salaryMax,
        Integer priority,
        WorkstreamStatus advertisingStatus,
        WorkstreamStatus searchStatus
) {}
