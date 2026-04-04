package dk.trustworks.intranet.aggregates.finance.dto.analytics;

import java.util.List;

/**
 * Gender pay equality analysis grouped by practice and career band.
 * Privacy rule: groups with fewer than 3 individuals per gender are suppressed.
 */
public record SalaryEqualityDTO(
        /** Salary equality grouped by practice. */
        List<SalaryEqualityGroupDTO> byPractice,
        /** Salary equality grouped by career band. */
        List<SalaryEqualityGroupDTO> byCareerBand,
        /** Month key for the analysis snapshot. */
        String monthKey
) {
    /** A single group's gender pay equality metrics. */
    public record SalaryEqualityGroupDTO(
            String groupId,
            String groupLabel,
            /** Average salary for male employees, null if suppressed. */
            Double maleAvgSalary,
            /** Average salary for female employees, null if suppressed. */
            Double femaleAvgSalary,
            int maleCount,
            int femaleCount,
            /** Gender pay gap as percentage, null if suppressed or insufficient data. */
            Double gapPct,
            /** True if group has fewer than 3 per gender (salary values hidden). */
            boolean isSuppressed
    ) {}
}
