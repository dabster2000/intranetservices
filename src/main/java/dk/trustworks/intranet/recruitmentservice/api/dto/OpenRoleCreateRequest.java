package dk.trustworks.intranet.recruitmentservice.api.dto;

import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringCategory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringSource;
import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OpenRoleCreateRequest(
        @NotBlank String title,
        @NotNull HiringCategory hiringCategory,
        Practice practice,
        String careerLevelUuid,
        String companyUuid,
        @NotBlank String teamUuid,
        String functionArea,
        @NotNull HiringSource hiringSource,
        String hiringReason,
        LocalDate targetStartDate,
        BigDecimal expectedAllocation,
        String expectedRateBand,
        Integer salaryMin,
        Integer salaryMax,
        Integer priority
) {}
