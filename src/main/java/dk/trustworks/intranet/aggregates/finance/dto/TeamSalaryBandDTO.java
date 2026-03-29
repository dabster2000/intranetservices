package dk.trustworks.intranet.aggregates.finance.dto;

import java.util.List;

/**
 * Salary band positioning for a career level, showing company-wide percentile bands
 * and where each team member falls within the band.
 *
 * <p>Percentile bands are computed across all active consultants at the same career level,
 * not just within the team. Team member positions show where each individual sits.
 */
public record TeamSalaryBandDTO(
        /** Career level name (e.g., SENIOR_CONSULTANT) */
        String careerLevel,
        /** Career track (e.g., DELIVERY) */
        String careerTrack,
        /** Number of company-wide consultants at this level (used for percentile computation) */
        int companyWideCount,
        /** Company-wide 25th percentile salary */
        int p25,
        /** Company-wide median (50th percentile) salary */
        int p50,
        /** Company-wide 75th percentile salary */
        int p75,
        /** Company-wide minimum salary at this level */
        int minSalary,
        /** Company-wide maximum salary at this level */
        int maxSalary,
        /** Team members at this career level with their salary positions */
        List<MemberSalaryPosition> members
) {

    /**
     * A team member's position within the salary band for their career level.
     */
    public record MemberSalaryPosition(
            String userId,
            String firstname,
            String lastname,
            /** Current monthly salary */
            int salary,
            /** Percentile rank within company-wide peers at same career level (0-100) */
            double percentileRank
    ) {}
}
